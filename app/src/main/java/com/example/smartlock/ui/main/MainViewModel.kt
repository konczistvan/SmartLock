package com.example.smartlock.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import com.example.smartlock.service.BleAdvertiserService
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

    private var lastLoggedStatus = ""

    // Geofence
    var geofenceRadiusMeters = 50f
        private set
    var deadzoneRadiusMeters = 10f
        private set
    private var lockLocation: Location? = null
    var isGeofenceEnabled = false
    private var lastGeofenceUnlock = 0L
    private val UNLOCK_COOLDOWN_MS = 30_000L

    private enum class GeoZone { OUTSIDE, GREEN, DEADZONE }
    private var lastZone: GeoZone = GeoZone.OUTSIDE

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var pendingManualOpen: Boolean = false

    private var isAdvertising = false
    private var appContext: Context? = null

    fun init(context: Context, btAdapter: BluetoothAdapter?) {
        appContext = context.applicationContext
        bluetoothAdapter = btAdapter
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        val prefs = context.getSharedPreferences("smartlock_prefs", Context.MODE_PRIVATE)
        deadzoneRadiusMeters = prefs.getInt("geo_deadzone", 10).toFloat()
        geofenceRadiusMeters = prefs.getInt("geo_radius", 50).toFloat()

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
                _statusText.postValue("No locks found. Add one with the + button.")
                _locksLoaded.postValue(true)
                _myLocksLive.postValue(emptyList())
                return@getMyLockIds
            }

            val lockIds = lockIdRolePairs.map { it.first }

            lockRepository.fetchMyLocks(lockIds) { locks ->
                myLocks = locks
                _myLocksLive.postValue(locks)

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
        if (command == "OPEN") pendingManualOpen = true
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
                if (!isGeofenceEnabled) {
                    _statusText.postValue("[$displayName]\n${mapStatus(lockModel.status)}")
                }

                // Log unlock events
                if (lockModel.status == "UNLOCKED" && lastLoggedStatus != "UNLOCKED") {
                    if (pendingManualOpen) {
                        logRepository.logAccess(currentLockId, "MANUAL")
                        pendingManualOpen = false
                    } else {
                        // Check if ESP set a lastUnlockMethod (e.g. AUTO_BLE)
                        checkAndLogEspUnlock()
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

    /**
     * Read lastUnlockMethod from Firebase. If ESP wrote "AUTO_BLE", log it and clear it.
     */
    private fun checkAndLogEspUnlock() {
        if (currentLockId.isEmpty()) return
        val ref = FirebaseClient.getReference("locks/$currentLockId/lastUnlockMethod")
        ref.get().addOnSuccessListener { snap ->
            val method = snap.getValue(String::class.java)
            if (!method.isNullOrEmpty() && method != "NONE") {
                logRepository.logAccess(currentLockId, method)
                // Clear it so we don't log again
                ref.setValue("NONE")
                Log.d(TAG, "Logged ESP unlock: $method")
            }
        }
    }

    private fun mapStatus(status: String) = when (status.uppercase()) {
        "LOCKED" -> "🔒 Locked"
        "UNLOCKED" -> "🔓 Unlocked"
        else -> status
    }

    // ===================== BLE ADVERTISER =====================

    fun setAutoUnlockEnabled(enabled: Boolean) {
        val ctx = appContext ?: return

        if (enabled) {
            val prefs = ctx.getSharedPreferences("smartlock_prefs", Context.MODE_PRIVATE)
            val beaconUUID = prefs.getString("my_beacon_uuid", null)

            if (beaconUUID.isNullOrEmpty()) {
                _toastMessage.postValue("No beacon UUID. Activate a lock first!")
                return
            }

            BleAdvertiserService.start(ctx, beaconUUID)
            isAdvertising = true
            _statusText.postValue("BLE: Advertising beacon for auto-unlock")
            _toastMessage.postValue("Auto-unlock enabled")
        } else {
            BleAdvertiserService.stop(ctx)
            isAdvertising = false
            _statusText.postValue("BLE: Stopped")
        }
    }

    // ===================== GEOFENCE =====================

    fun fetchLockLocation() {
        lockRepository.fetchLockLocation(
            lockId = currentLockId,
            onSuccess = { location ->
                lockLocation = location
                _geofenceStatusText.postValue(
                    "📍 Lock: (${String.format("%.5f", location.latitude)}, ${String.format("%.5f", location.longitude)})"
                )
            },
            onFailure = { _geofenceStatusText.postValue("⚠ $it") }
        )
    }

    @SuppressLint("MissingPermission")
    fun saveCurrentLocationAsLock(context: Context) {
        if (currentLockId.isEmpty()) {
            _toastMessage.postValue("No lock selected")
            return
        }
        if (ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _toastMessage.postValue("Location permission required")
            return
        }

        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            if (location != null) {
                val lockRef = FirebaseClient.getReference("locks/$currentLockId/location")
                lockRef.child("lat").setValue(location.latitude)
                lockRef.child("lng").setValue(location.longitude)

                lockLocation = location
                _toastMessage.postValue("Lock location saved!")
                _geofenceStatusText.postValue(
                    "📍 Lock: (${String.format("%.5f", location.latitude)}, ${String.format("%.5f", location.longitude)})"
                )
                Log.d(TAG, "Lock location saved: ${location.latitude}, ${location.longitude}")
            } else {
                _toastMessage.postValue("Cannot get current location. Try again.")
            }
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val myLocation = result.lastLocation ?: return
                val target = lockLocation

                if (target == null) {
                    _geofenceStatusText.postValue("GPS: Set lock location first (button below)")
                    return
                }

                val distanceMeters = myLocation.distanceTo(target)

                val currentZone = when {
                    distanceMeters <= deadzoneRadiusMeters -> GeoZone.DEADZONE
                    distanceMeters <= geofenceRadiusMeters -> GeoZone.GREEN
                    else -> GeoZone.OUTSIDE
                }

                val zoneLabel = when (currentZone) {
                    GeoZone.DEADZONE -> "🏠 INSIDE (deadzone)"
                    GeoZone.GREEN -> "🟢 UNLOCK ZONE"
                    GeoZone.OUTSIDE -> "⬜ OUTSIDE"
                }

                _geofenceStatusText.postValue(
                    "GPS: ${distanceMeters.toInt()} m | $zoneLabel\n" +
                            "(unlock: ${geofenceRadiusMeters.toInt()}m, dead: ${deadzoneRadiusMeters.toInt()}m)"
                )

                // UNLOCK only when transitioning OUTSIDE → GREEN
                if (currentZone == GeoZone.GREEN && lastZone == GeoZone.OUTSIDE) {
                    val now = System.currentTimeMillis()
                    if (now - lastGeofenceUnlock > UNLOCK_COOLDOWN_MS) {
                        pendingManualOpen = false
                        sendCommand(currentLockId, "OPEN")
                        logRepository.logAccess(currentLockId, "AUTO_GEOFENCE")
                        lastGeofenceUnlock = now
                        _statusText.postValue("GEOFENCE: $currentLockId opened!")
                        _toastMessage.postValue("GEOFENCE AUTO OPEN: approaching lock")
                        Log.d(TAG, "Geofence unlock: OUTSIDE → GREEN at ${distanceMeters.toInt()}m")
                    }
                }

                lastZone = currentZone
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(context: Context) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        lastZone = GeoZone.OUTSIDE

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateDistanceMeters(2f).build()

        fusedLocationClient?.requestLocationUpdates(
            request, locationCallback!!, Looper.getMainLooper()
        )
    }

    fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
        lastZone = GeoZone.OUTSIDE
    }

    fun setBleRssiThreshold(rssi: Int) { bleRssiThreshold = rssi }
    fun setGeofenceRadius(meters: Float) { geofenceRadiusMeters = meters }
    fun setDeadzoneRadius(meters: Float) { deadzoneRadiusMeters = meters }

    fun onToastShown() { _toastMessage.value = null }
    fun onBleIndexHandled() { _bleDetectedLockIndex.value = null }

    override fun onCleared() {
        super.onCleared()
        lockRepository.stopListening()
        stopLocationUpdates()
        appContext?.let { BleAdvertiserService.stop(it) }
    }
}