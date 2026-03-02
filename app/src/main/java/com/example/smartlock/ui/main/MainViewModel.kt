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
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.model.LockModel
import com.example.smartlock.data.repository.AuthRepository
import com.example.smartlock.data.repository.LockRepository
import com.example.smartlock.data.repository.LogRepository
import com.example.smartlock.data.repository.PermissionRepository
import com.google.android.gms.location.*

class MainViewModel : ViewModel() {

    private val TAG = "MainViewModel"

    private val lockRepository = LockRepository()
    private val logRepository = LogRepository()
    val authRepository = AuthRepository()
    private val permissionRepository = PermissionRepository()
    private var bleRssiThreshold: Int = -80

    var myLocks: List<LockModel> = emptyList()
        private set

    private var deviceMap: Map<String, String> = emptyMap()

    val lockDisplayNames: List<String>
        get() = myLocks.map { it.name.ifEmpty { it.id } }

    val lockList: List<String>
        get() = myLocks.map { it.id }

    var currentLockId: String = ""
        private set

    private var myRoleForCurrentLock: String = "guest"

    fun isOwnerOfCurrentLock(): Boolean = myRoleForCurrentLock == "owner"

    private val _locksLoaded = MutableLiveData(false)
    val locksLoaded: LiveData<Boolean> = _locksLoaded

    private val _statusText = MutableLiveData("Loading...")
    val statusText: LiveData<String> = _statusText

    private val _geofenceStatusText = MutableLiveData("GPS: –")
    val geofenceStatusText: LiveData<String> = _geofenceStatusText

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private val _isLoggedIn = MutableLiveData(false)
    val isLoggedIn: LiveData<Boolean> = _isLoggedIn

    private val _bleDetectedLockIndex = MutableLiveData<Int?>()
    val bleDetectedLockIndex: LiveData<Int?> = _bleDetectedLockIndex

    private val _lockStatus = MutableLiveData("LOCKED")
    val lockStatus: LiveData<String> = _lockStatus

    private val _currentLockRole = MutableLiveData("guest")
    val currentLockRole: LiveData<String> = _currentLockRole

    val geofenceStatus: LiveData<String> = _geofenceStatusText

    private val _myLocksLive = MutableLiveData<List<LockModel>>(emptyList())
    val myLocksLive: LiveData<List<LockModel>> = _myLocksLive

    private var isScanning = false
    private var lastUnlockTime = 0L
    private var lastLoggedStatus = ""
    private val UNLOCK_COOLDOWN_MS = 10_000L

    var geofenceRadiusMeters = 50f
        private set
    private var lockLocation: Location? = null
    var isGeofenceEnabled = false
    private var lastGeofenceUnlock = 0L
    private var wasInsideGeofence = false

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var bluetoothAdapter: BluetoothAdapter? = null


    private var pendingManualOpen: Boolean = false

    // ---- BLE Scan Callback ----
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                val cleanMac = scanResult.device.address.replace(":", "").uppercase()
                val rssi = scanResult.rssi
                val targetLockId = deviceMap[cleanMac] ?: return

                if (rssi > bleRssiThreshold) {
                    val now = System.currentTimeMillis()
                    if (now - lastUnlockTime > UNLOCK_COOLDOWN_MS) {
                        val index = lockList.indexOf(targetLockId)
                        currentLockId = targetLockId

                        pendingManualOpen = false
                        _statusText.postValue("BLE: Opening $targetLockId...")
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

    fun init(context: Context, btAdapter: BluetoothAdapter?) {
        bluetoothAdapter = btAdapter
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        setupLocationCallback()
    }

    fun loginAndLoadLocks() {
        val uid = FirebaseClient.auth.currentUser?.uid
        if (uid == null) {
            _statusText.value = "Not logged in"
            return
        }
        _statusText.value = "Loading locks..."
        _isLoggedIn.value = true
        loadMyLocks()
    }

    private fun loadMyLocks() {
        val uid = FirebaseClient.auth.currentUser?.uid ?: return

        permissionRepository.getMyLockIds(uid) { lockIdRolePairs ->
            if (lockIdRolePairs.isEmpty()) {
                myLocks = emptyList()
                deviceMap = emptyMap()
                _statusText.postValue("No locks found. Add one with the + button.")
                _locksLoaded.postValue(true)
                _myLocksLive.postValue(emptyList())
                return@getMyLockIds
            }

            val lockIds = lockIdRolePairs.map { it.first }

            lockRepository.fetchMyLocks(lockIds) { locks ->
                myLocks = locks
                _myLocksLive.postValue(locks)

                deviceMap = locks
                    .filter { it.macAddress.isNotEmpty() }
                    .associate { it.macAddress.uppercase() to it.id }

                if (myLocks.isNotEmpty()) {
                    currentLockId = myLocks[0].id
                    myRoleForCurrentLock = lockIdRolePairs
                        .firstOrNull { it.first == currentLockId }?.second ?: "guest"
                    _currentLockRole.postValue(myRoleForCurrentLock)
                    listenToCurrentLockStatus()
                }

                _locksLoaded.postValue(true)
                Log.d(TAG, "Locks loaded: ${myLocks.map { it.name }}")
            }
        }
    }

    fun reloadLocks() {
        _locksLoaded.value = false
        lockRepository.stopListening()
        loadMyLocks()
    }

    fun openLock() {
        pendingManualOpen = true
        sendCommand(currentLockId, "OPEN")
    }

    fun closeLock() {
        pendingManualOpen = false
        sendCommand(currentLockId, "CLOSE")
    }

    fun sendCommand(command: String) {
        if (command == "OPEN") {
            pendingManualOpen = true
        }
        sendCommand(currentLockId, command)
    }

    private fun sendCommand(lockId: String, command: String) {
        lockRepository.sendCommand(lockId, command)
    }

    fun selectLock(position: Int) {
        if (position >= myLocks.size) return
        selectLock(myLocks[position].id)
    }

    fun selectLock(lockId: String) {
        if (lockId == currentLockId) return
        currentLockId = lockId
        pendingManualOpen = false

        val uid = FirebaseClient.auth.currentUser?.uid ?: ""
        FirebaseClient.getReference("permissions/$currentLockId/$uid/role")
            .get()
            .addOnSuccessListener { snap ->
                val role = snap.getValue(String::class.java) ?: "guest"
                myRoleForCurrentLock = role
                _currentLockRole.postValue(role)
            }

        listenToCurrentLockStatus()
        if (isGeofenceEnabled) fetchLockLocation()
    }

    fun listenToCurrentLockStatus() {
        if (currentLockId.isEmpty()) return

        lockRepository.listenToLockStatus(
            lockId = currentLockId,
            onStatusChanged = { lockModel ->
                val displayName = myLocks.find { it.id == currentLockId }?.name ?: currentLockId
                _lockStatus.postValue(lockModel.status)
                if (!isScanning && !isGeofenceEnabled) {
                    _statusText.postValue("[$displayName]\n${mapStatus(lockModel.status)}")
                }

                if (lockModel.status == "UNLOCKED" && lastLoggedStatus != "UNLOCKED") {
                    if (pendingManualOpen) {
                        logRepository.logAccess(currentLockId, "MANUAL")
                        pendingManualOpen = false
                    }
                }

                if (lockModel.status == "LOCKED") {
                    pendingManualOpen = false
                }

                lastLoggedStatus = lockModel.status
            },
            onError = { _statusText.postValue("Error: $it") }
        )
    }

    private fun mapStatus(status: String) = when (status.uppercase()) {
        "LOCKED" -> "🔒 Locked"
        "UNLOCKED" -> "🔓 Unlocked"
        else -> status
    }

    fun fetchLockLocation() {
        lockRepository.fetchLockLocation(
            lockId = currentLockId,
            onSuccess = { location ->
                lockLocation = location
                _geofenceStatusText.postValue(
                    "GPS: coordinates OK (${location.latitude}, ${location.longitude})"
                )
            },
            onFailure = { _geofenceStatusText.postValue("⚠ $it") }
        )
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val myLocation = result.lastLocation ?: return
                val target = lockLocation

                if (target == null) {
                    _geofenceStatusText.postValue("GPS: missing lock coordinates from Firebase")
                    return
                }

                val distanceMeters = myLocation.distanceTo(target)
                val inside = distanceMeters <= geofenceRadiusMeters

                _geofenceStatusText.postValue(
                    "GPS: ${distanceMeters.toInt()} m from lock " +
                            "(limit: ${geofenceRadiusMeters.toInt()} m) " +
                            if (inside) "✓ INSIDE" else "OUTSIDE"
                )

                if (inside && !wasInsideGeofence) {
                    val now = System.currentTimeMillis()
                    if (now - lastGeofenceUnlock > UNLOCK_COOLDOWN_MS) {
                        pendingManualOpen = false
                        sendCommand(currentLockId, "OPEN")
                        logRepository.logAccess(currentLockId, "AUTO_GEOFENCE")
                        lastGeofenceUnlock = now
                        _statusText.postValue("GEOFENCE: $currentLockId opened!")
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
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateDistanceMeters(2f).build()

        fusedLocationClient?.requestLocationUpdates(
            request, locationCallback!!, Looper.getMainLooper()
        )
    }

    fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        wasInsideGeofence = false
    }

    @SuppressLint("MissingPermission")
    fun startBLEScan() {
        if (bluetoothAdapter == null || isScanning) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner not available yet — is Bluetooth fully on?")
            _statusText.postValue("BLE: Bluetooth not ready. Turn it on and try again.")
            _toastMessage.postValue("Turn on Bluetooth and try again")
            return
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, bleScanCallback)
            isScanning = true
            _statusText.postValue("BLE: scanning...")
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan security exception: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBLEScan() {
        if (!isScanning) return

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BLE scanner not available — can't stop scan")
            isScanning = false
            return
        }

        try {
            scanner.stopScan(bleScanCallback)
            isScanning = false
            _statusText.postValue("BLE: stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE stop security exception: ${e.message}")
        }
    }

    fun setAutoUnlockEnabled(enabled: Boolean) {
        if (enabled) startBLEScan() else stopBLEScan()
    }

    fun setBleRssiThreshold(rssi: Int) {
        bleRssiThreshold = rssi
    }

    fun setGeofenceRadius(meters: Float) {
        geofenceRadiusMeters = meters
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
    }
}