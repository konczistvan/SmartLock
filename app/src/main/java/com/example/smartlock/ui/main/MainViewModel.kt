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

    var currentLockId: String = ""
        private set

    private var myRoleForCurrentLock: String = "guest"

    // --- ÁLLAPOTGÉP (Makro) ---
    enum class UserState { HOME, AWAY, APPROACHING }
    private val _userState = MutableLiveData(UserState.HOME)
    val userState: LiveData<UserState> = _userState

    var isHybridModeEnabled = false
        private set

    // --- SZENZORFÚZIÓ (Mikro + Makro) ---
    private val _microState = MutableLiveData("UNKNOWN")
    private var microStateListener: com.google.firebase.database.ValueEventListener? = null

    private val _unifiedStateText = MutableLiveData<String>("🏠 Betöltés...")
    val unifiedStateText: LiveData<String> = _unifiedStateText
    // -----------------------------------

    private val _locksLoaded = MutableLiveData(false)
    val locksLoaded: LiveData<Boolean> = _locksLoaded

    private val _statusText = MutableLiveData("Loading...")
    val statusText: LiveData<String> = _statusText

    private val _geofenceStatusText = MutableLiveData("GPS: –")
    val geofenceStatusText: LiveData<String> = _geofenceStatusText

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private val _lockStatus = MutableLiveData("LOCKED")
    val lockStatus: LiveData<String> = _lockStatus

    private val _currentLockRole = MutableLiveData("guest")
    val currentLockRole: LiveData<String> = _currentLockRole

    private val _myLocksLive = MutableLiveData<List<LockModel>>(emptyList())
    val myLocksLive: LiveData<List<LockModel>> = _myLocksLive

    private var lastLoggedStatus = ""

    var geofenceRadiusMeters = 50f
        private set
    var deadzoneRadiusMeters = 10f
        private set
    private var lockLocation: Location? = null

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var pendingManualOpen: Boolean = false
    private var manualUnlockStartTime: Long = 0L

    private var isAdvertising = false
    private var appContext: Context? = null

    fun init(context: Context, btAdapter: BluetoothAdapter?) {
        appContext = context.applicationContext
        bluetoothAdapter = btAdapter
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        setupLocationCallback()
    }

    fun loginAndLoadLocks() {
        val uid = FirebaseClient.auth.currentUser?.uid ?: return
        _statusText.value = "Zárak betöltése..."
        loadMyLocks()
    }

    private fun loadMyLocks() {
        val uid = FirebaseClient.auth.currentUser?.uid ?: return
        permissionRepository.getMyLockIds(uid) { lockIdRolePairs ->
            if (lockIdRolePairs.isEmpty()) {
                myLocks = emptyList()
                _statusText.postValue("Nincs zár. Adj hozzá a + gombbal.")
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
                    myRoleForCurrentLock = lockIdRolePairs.firstOrNull { it.first == currentLockId }?.second ?: "guest"
                    _currentLockRole.postValue(myRoleForCurrentLock)
                    listenToCurrentLockStatus()
                }

                _locksLoaded.postValue(true)
                syncBleKeysToFirebase()
            }
        }
    }

    fun reloadLocks() {
        _locksLoaded.value = false
        lockRepository.stopListening()
        loadMyLocks()
    }

    fun openLock() {
        manualUnlockStartTime = System.currentTimeMillis()
        pendingManualOpen = true
        sendCommand(currentLockId, "OPEN")
    }

    fun closeLock() {
        pendingManualOpen = false
        sendCommand(currentLockId, "CLOSE")
    }

    fun sendCommand(command: String) {
        if (command == "OPEN") openLock()
        else sendCommand(currentLockId, command)
    }

    private fun sendCommand(lockId: String, command: String) {
        lockRepository.sendCommand(lockId, command)
    }

    fun selectLock(lockId: String) {
        if (lockId == currentLockId) return
        currentLockId = lockId
        pendingManualOpen = false

        val prefs = appContext?.getSharedPreferences("smartlock_prefs", Context.MODE_PRIVATE)
        geofenceRadiusMeters = prefs?.getInt("geo_radius_$lockId", 50)?.toFloat() ?: 50f
        deadzoneRadiusMeters = prefs?.getInt("geo_deadzone_$lockId", 10)?.toFloat() ?: 10f
        bleRssiThreshold = prefs?.getInt("ble_rssi_$lockId", -80) ?: -80

        val uid = FirebaseClient.auth.currentUser?.uid ?: ""
        FirebaseClient.getReference("permissions/$currentLockId/$uid/role").get().addOnSuccessListener { snap ->
            myRoleForCurrentLock = snap.getValue(String::class.java) ?: "guest"
            _currentLockRole.postValue(myRoleForCurrentLock)
        }

        listenToCurrentLockStatus()
        if (isHybridModeEnabled) fetchLockLocation()
    }

    fun listenToCurrentLockStatus() {
        if (currentLockId.isEmpty()) return

        lockRepository.listenToLockStatus(
            lockId = currentLockId,
            onStatusChanged = { lockModel ->
                val displayName = myLocks.find { it.id == currentLockId }?.name ?: currentLockId
                _lockStatus.postValue(lockModel.status)
                _statusText.postValue("[$displayName]\n${mapStatus(lockModel.status)}")

                if (lockModel.status == "UNLOCKED" && lastLoggedStatus != "UNLOCKED") {

                    // HAZATÉRTÜNK!
                    if (isHybridModeEnabled) {
                        _userState.postValue(UserState.HOME)
                        // JAVÍTVA: Explicit paraméter átadása
                        updateUnifiedState(macroParam = UserState.HOME)
                    }

                    if (pendingManualOpen) {
                        logRepository.logAccess(currentLockId, "MANUAL")
                        pendingManualOpen = false
                    } else {
                        checkAndLogEspUnlock()
                    }
                }

                if (lockModel.status == "LOCKED") pendingManualOpen = false
                lastLoggedStatus = lockModel.status
            }
        )

        // --- ESP32 MIKRO-ÁLLAPOT FIGYELÉSE ---
        microStateListener?.let { FirebaseClient.getReference("locks/$currentLockId/microState").removeEventListener(it) }

        microStateListener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val newMicro = snapshot.getValue(String::class.java) ?: "UNKNOWN"
                _microState.postValue(newMicro)
                // JAVÍTVA: Explicit paraméter átadása, hogy ne a régi _microState.value-t olvassa
                updateUnifiedState(microParam = newMicro)
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        }
        FirebaseClient.getReference("locks/$currentLockId/microState").addValueEventListener(microStateListener!!)
    }

    private fun checkAndLogEspUnlock() {
        if (currentLockId.isEmpty()) return
        val ref = FirebaseClient.getReference("locks/$currentLockId/lastUnlockMethod")
        ref.get().addOnSuccessListener { snap ->
            val method = snap.getValue(String::class.java)
            if (!method.isNullOrEmpty() && method != "NONE") {
                logRepository.logAccess(currentLockId, method)
                ref.setValue("NONE")
            }
        }
    }

    private fun mapStatus(status: String) = when (status.uppercase()) {
        "LOCKED" -> "🔒 Locked"
        "UNLOCKED" -> "🔓 Unlocked"
        else -> status
    }

    // --- SZENZORFÚZIÓ MATEK (JAVÍTVA) ---
    private fun updateUnifiedState(macroParam: UserState? = null, microParam: String? = null) {
        // Ha explicit átadjuk, azt használja, különben a LiveData (esetleg régi) értékét
        val macro = macroParam ?: _userState.value ?: UserState.HOME
        val micro = microParam ?: _microState.value ?: "UNKNOWN"

        val text = when {
            macro == UserState.AWAY -> "🚶 TÁVOL (Házon kívül)"
            macro == UserState.APPROACHING && micro == "AWAY" -> "🎯 KÖZELEDÉS (Zónában, várjuk a BLE jelet)"
            macro == UserState.APPROACHING && micro == "OUTSIDE" -> "🚪 AJTÓ ELŐTT (Nyitás folyamatban...)"
            macro == UserState.HOME && micro == "INSIDE" -> "🛋️ BENT A HÁZBAN (Zárva, Biztonságban)"
            macro == UserState.HOME && micro == "OUTSIDE" -> "🌳 KINT AZ UDVARON (Szemétkivitel)"
            macro == UserState.HOME && micro == "AWAY" -> "🏠 OTTHON (Alszik a rendszer)"
            else -> "🏠 OTTHON (Készenlét)"
        }
        _unifiedStateText.postValue(text)
    }

    // --- HIBRID VEZÉRLÉS (Bluetooth KI/BE) ---
    fun setHybridMode(enabled: Boolean) {
        isHybridModeEnabled = enabled
        if (!enabled) {
            _userState.postValue(UserState.HOME)
            updateUnifiedState(macroParam = UserState.HOME)
            stopBleAdvertising()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleAdvertising() {
        if (isAdvertising) return
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("smartlock_prefs", Context.MODE_PRIVATE)
        val beaconUUID = prefs.getString("my_beacon_uuid", null) ?: return

        BleAdvertiserService.start(ctx, beaconUUID)
        isAdvertising = true
        if (currentLockId.isNotEmpty()) {
            FirebaseClient.getReference("locks/$currentLockId/bleProximityEnabled").setValue(true)
        }
    }

    private fun stopBleAdvertising() {
        if (!isAdvertising) return
        val ctx = appContext ?: return
        BleAdvertiserService.stop(ctx)
        isAdvertising = false
        if (currentLockId.isNotEmpty()) {
            FirebaseClient.getReference("locks/$currentLockId/bleProximityEnabled").setValue(false)
        }
    }

    // --- GEOFENCE (Helymeghatározás) ---
    fun fetchLockLocation() {
        lockRepository.fetchLockLocation(
            lockId = currentLockId,
            onSuccess = { location -> lockLocation = location },
            onFailure = { _geofenceStatusText.postValue("⚠ $it") }
        )
    }

    @SuppressLint("MissingPermission")
    fun saveCurrentLocationAsLock(context: Context) {
        if (currentLockId.isEmpty()) return
        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            if (location != null) {
                val lockRef = FirebaseClient.getReference("locks/$currentLockId/location")
                lockRef.child("lat").setValue(location.latitude)
                lockRef.child("lng").setValue(location.longitude)
                lockLocation = location
                _toastMessage.postValue("Zár helyzete elmentve!")
            }
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val myLocation = result.lastLocation ?: return
                val target = lockLocation ?: return

                val distanceMeters = myLocation.distanceTo(target)
                _geofenceStatusText.postValue("Távolság a zártól: ${distanceMeters.toInt()} m")

                if (isHybridModeEnabled) {
                    val currentState = _userState.value ?: UserState.HOME

                    // Ha elhagytuk a zónát (több mint 50m) -> ALTATÁS
                    if (distanceMeters > geofenceRadiusMeters) {
                        if (currentState != UserState.AWAY) {
                            _userState.postValue(UserState.AWAY)
                            updateUnifiedState(macroParam = UserState.AWAY) // JAVÍTVA
                            stopBleAdvertising()
                        }
                    }
                    // Ha a zónán belül vagyunk (kevesebb mint 50m) -> ÉBRENLÉT
                    else {
                        if (currentState == UserState.AWAY) {
                            _userState.postValue(UserState.APPROACHING)
                            updateUnifiedState(macroParam = UserState.APPROACHING) // JAVÍTVA
                            startBleAdvertising()
                        } else if (currentState == UserState.APPROACHING && distanceMeters < 15f) {
                            _userState.postValue(UserState.HOME)
                            updateUnifiedState(macroParam = UserState.HOME) // JAVÍTVA
                        } else if (currentState == UserState.HOME && !isAdvertising) {
                            startBleAdvertising()
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(context: Context) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateDistanceMeters(2f).build()
        fusedLocationClient?.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient?.removeLocationUpdates(it) }
    }

    fun setBleRssiThreshold(rssi: Int) { bleRssiThreshold = rssi }
    fun setGeofenceRadius(meters: Float) { geofenceRadiusMeters = meters }
    fun setDeadzoneRadius(meters: Float) { deadzoneRadiusMeters = meters }

    fun onToastShown() { _toastMessage.value = null }
    override fun onCleared() {
        super.onCleared()
        lockRepository.stopListening()
        stopLocationUpdates()
        appContext?.let { BleAdvertiserService.stop(it) }
    }

    private fun getOrGenerateBleUuid(context: Context): String {
        val prefs = context.getSharedPreferences("smartlock_prefs", Context.MODE_PRIVATE)
        var uuid = prefs.getString("my_beacon_uuid", null)
        if (uuid == null) {
            uuid = java.util.UUID.randomUUID().toString().replace("-", "").lowercase()
            prefs.edit().putString("my_beacon_uuid", uuid).apply()
        }
        return uuid
    }

    private fun syncBleKeysToFirebase() {
        val ctx = appContext ?: return
        val uid = FirebaseClient.auth.currentUser?.uid ?: return
        val myBleKey = getOrGenerateBleUuid(ctx)
        myLocks.forEach { lock ->
            FirebaseClient.getReference("locks/${lock.id}/authorizedBeacons/$uid").setValue(myBleKey)
        }
    }
}