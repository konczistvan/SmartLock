package com.example.smartlock.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.smartlock.data.mapper.LockMapper
import com.example.smartlock.data.repository.AuthRepository
import com.example.smartlock.data.repository.LockRepository
import com.example.smartlock.data.repository.LogRepository
import com.google.android.gms.location.*

class MainViewModel : ViewModel() {

    private val TAG = "MainViewModel"

    private val lockRepository  = LockRepository()
    private val logRepository   = LogRepository()
    val authRepository  = AuthRepository()

    val deviceMap = mapOf(
        "0CDC7E614162" to "LOCK_0CDC7E614160",
        "0CDC7E5D076E" to "LOCK_0CDC7E5D076C",
        "B8F862E0BCBD" to "LOCK_B8F862E0BCBC"
    )

    val lockList = deviceMap.values.toList()

    val lockDisplayNames: List<String>
        get() = lockList.map { LockMapper.mapLockIdToName(it) }

    var currentLockId: String = lockList[0]
        private set

    private val PROXIMITY_THRESHOLD = -80
    private val UNLOCK_COOLDOWN_MS  = 10000L

    private val _statusText = MutableLiveData<String>("Betöltés...")
    val statusText: LiveData<String> = _statusText

    private val _geofenceStatusText = MutableLiveData<String>("GPS: –")
    val geofenceStatusText: LiveData<String> = _geofenceStatusText

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private val _isLoggedIn = MutableLiveData<Boolean>(false)
    val isLoggedIn: LiveData<Boolean> = _isLoggedIn

    private var isScanning = false
    private var lastUnlockTime = 0L
    private var lastLoggedStatus = ""

    var geofenceRadiusMeters = 50f
    private var lockLocation: Location? = null
    var isGeofenceEnabled = false
    private var lastGeofenceUnlock = 0L
    private var wasInsideGeofence = false

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null

    private var bluetoothAdapter: BluetoothAdapter? = null

    fun init(context: Context, btAdapter: BluetoothAdapter?) {
        bluetoothAdapter = btAdapter
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        setupLocationCallback(context)
    }

    fun login(email: String, password: String) {
        authRepository.login(
            email = email,
            password = password,
            onSuccess = {
                _isLoggedIn.value = true
                _statusText.value = "Bejelentkezve"
                listenToCurrentLockStatus()
            },
            onFailure = { error ->
                _statusText.value = "Login hiba!"
                _toastMessage.value = "Login hiba: $error"
            }
        )
    }

    fun openLock() {
        sendCommand(currentLockId, "OPEN")
    }

    fun closeLock() {
        sendCommand(currentLockId, "CLOSE")
    }

    fun selectLock(position: Int) {
        currentLockId = lockList[position]
        listenToCurrentLockStatus()
        if (isGeofenceEnabled) fetchLockLocation()
    }

    private fun sendCommand(lockId: String, command: String) {
        lockRepository.sendCommand(lockId, command)
    }

    fun listenToCurrentLockStatus() {
        lockRepository.listenToLockStatus(
            lockId = currentLockId,
            onStatusChanged = { lockModel ->

                val displayText = LockMapper.mapStatusToDisplayText(lockModel.status)

                if (!isScanning && !isGeofenceEnabled) {
                    _statusText.value = "[${currentLockId}]\n$displayText"
                }

                if (lockModel.status == "UNLOCKED" && lastLoggedStatus != "UNLOCKED") {
                    logRepository.logAccess(currentLockId, "MANUAL")
                }
                lastLoggedStatus = lockModel.status
            },
            onError = { errorMsg ->
                _statusText.value = "Hiba: $errorMsg"
            }
        )
    }


    fun fetchLockLocation() {
        lockRepository.fetchLockLocation(
            lockId = currentLockId,
            onSuccess = { location ->
                lockLocation = location
                _geofenceStatusText.value =
                    "GPS: koordináták OK (${location.latitude}, ${location.longitude})"
            },
            onFailure = { msg ->
                _geofenceStatusText.value = "⚠ $msg"
            }
        )
    }

    private fun setupLocationCallback(context: Context) {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val myLocation = result.lastLocation ?: return
                val target = lockLocation

                if (target == null) {
                    _geofenceStatusText.postValue("GPS: zár koordinátái hiányoznak Firebase-ből")
                    return
                }

                val distanceMeters = myLocation.distanceTo(target)
                val inside = distanceMeters <= geofenceRadiusMeters

                _geofenceStatusText.postValue(
                    "GPS: ${distanceMeters.toInt()} m a zártól " +
                            "(limit: ${geofenceRadiusMeters.toInt()} m) ${if (inside) "✓ BENT" else "KINT"}"
                )

                if (inside && !wasInsideGeofence) {
                    val now = System.currentTimeMillis()
                    if (now - lastGeofenceUnlock > UNLOCK_COOLDOWN_MS) {
                        Log.d(TAG, "Geofence zónába lépés → $currentLockId nyitása")
                        sendCommand(currentLockId, "OPEN")
                        logRepository.logAccess(currentLockId, "AUTO_GEOFENCE")
                        lastGeofenceUnlock = now
                        _statusText.postValue("GEOFENCE: $currentLockId nyitva!")
                        _toastMessage.postValue("GEOFENCE AUTO OPEN: $currentLockId")
                    }
                }
                wasInsideGeofence = inside
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(context: Context) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateDistanceMeters(2f).build()

        fusedLocationClient?.requestLocationUpdates(
            request, locationCallback!!, Looper.getMainLooper()
        )
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
        }
        wasInsideGeofence = false
    }

    val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val cleanMac = scanResult.device.address.replace(":", "").uppercase()
                val rssi = scanResult.rssi
                val targetLockId = deviceMap[cleanMac] ?: return

                if (rssi > PROXIMITY_THRESHOLD) {
                    val now = System.currentTimeMillis()
                    if (now - lastUnlockTime > UNLOCK_COOLDOWN_MS) {
                        val index = lockList.indexOf(targetLockId)
                        currentLockId = targetLockId

                        _statusText.postValue("BLE: $targetLockId nyitása...")
                        sendCommand(targetLockId, "OPEN")
                        logRepository.logAccess(targetLockId, "AUTO_BLE", rssi)
                        lastUnlockTime = now
                        _toastMessage.postValue("AUTO BLE OPEN: $targetLockId")

                        _bleDetectedLockIndex.postValue(index)
                    }
                }
            }
        }
    }

    private val _bleDetectedLockIndex = MutableLiveData<Int?>()
    val bleDetectedLockIndex: LiveData<Int?> = _bleDetectedLockIndex

    @SuppressLint("MissingPermission")
    fun startBLEScan() {
        if (bluetoothAdapter == null || isScanning) return
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            bluetoothAdapter!!.bluetoothLeScanner.startScan(null, settings, bleScanCallback)
            isScanning = true
            _statusText.value = "BLE: keresés..."
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan security exception: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBLEScan() {
        if (bluetoothAdapter == null || !isScanning) return
        try {
            bluetoothAdapter!!.bluetoothLeScanner.stopScan(bleScanCallback)
            isScanning = false
            _statusText.value = "BLE: leállítva"
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE stop security exception: ${e.message}")
        }
    }

    fun onToastShown() {
        _toastMessage.value = null
    }

    fun onBleIndexHandled() {
        _bleDetectedLockIndex.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        lockRepository.stopListening()
        stopLocationUpdates()
        stopBLEScan()
        Log.d(TAG, "ViewModel cleared, figyelések leállítva")
    }
}