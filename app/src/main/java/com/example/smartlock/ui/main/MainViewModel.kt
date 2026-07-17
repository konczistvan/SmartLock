package com.example.smartlock.ui.main

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.location.Location
import android.os.CountDownTimer
import android.os.Looper
import android.util.Log
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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener

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

    var isHybridModeEnabled = false
        private set

    private var insideRadius = false
    private var lastDistanceMeters: Float? = null
    private var lastFixTrustworthy = false
    private var awayCandidateSince = 0L

    private val _bleSwitchState = MutableLiveData<Boolean>(false)
    val bleSwitchState: LiveData<Boolean> = _bleSwitchState

    private val _cooldownLeft = MutableLiveData<Int>(0)
    val cooldownLeftLive: LiveData<Int> = _cooldownLeft

    private var cooldownTimer: CountDownTimer? = null
    private var cooldownRef: DatabaseReference? = null
    private var cooldownListener: ValueEventListener? = null

    private val _locksLoaded = MutableLiveData(false)
    val locksLoaded: LiveData<Boolean> = _locksLoaded

    private val _statusText = MutableLiveData("Loading...")
    val statusText: LiveData<String> = _statusText

    private val _unifiedStateText = MutableLiveData("🔒 Locked")
    val unifiedStateText: LiveData<String> = _unifiedStateText

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
        _statusText.value = "Loading locks..."
        loadMyLocks()
    }

    private fun loadMyLocks() {
        val uid = FirebaseClient.auth.currentUser?.uid ?: return
        permissionRepository.getMyLockIds(uid) { lockIdRolePairs ->
            if (lockIdRolePairs.isEmpty()) {
                myLocks = emptyList()
                _statusText.postValue("No locks. Add one with the + button.")
                _locksLoaded.postValue(true)
                _myLocksLive.postValue(emptyList())
                return@getMyLockIds
            }

            val lockIds = lockIdRolePairs.map { it.first }
            lockRepository.fetchMyLocks(lockIds) { locks ->
                myLocks = locks
                _myLocksLive.postValue(locks)

                if (myLocks.isNotEmpty()) {
                    val lockExists = myLocks.any { it.id == currentLockId }
                    if (currentLockId.isEmpty() || !lockExists) {
                        currentLockId = myLocks[0].id
                    }

                    myRoleForCurrentLock = lockIdRolePairs.firstOrNull { it.first == currentLockId }?.second ?: "guest"
                    _currentLockRole.postValue(myRoleForCurrentLock)

                    listenToCurrentLockStatus()
                    listenToCooldown()
                    if (isHybridModeEnabled) fetchLockLocation()
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

        if (isHybridModeEnabled) setProximityFlag(false)

        currentLockId = lockId
        pendingManualOpen = false

        lockLocation = null
        lastDistanceMeters = null
        lastFixTrustworthy = false
        awayCandidateSince = 0L
        insideRadius = isHybridModeEnabled

        val newLockName = myLocks.find { it.id == lockId }?.name ?: lockId
        _statusText.postValue("[$newLockName]\nLoading...")

        val prefs = appContext?.getSharedPreferences("smartlock_prefs", Context.MODE_PRIVATE)
        geofenceRadiusMeters = prefs?.getInt("geo_radius_$lockId", 50)?.toFloat() ?: 50f
        bleRssiThreshold = prefs?.getInt("ble_rssi_$lockId", -80) ?: -80

        val uid = FirebaseClient.auth.currentUser?.uid ?: ""
        FirebaseClient.getReference("permissions/$currentLockId/$uid/role").get().addOnSuccessListener { snap ->
            myRoleForCurrentLock = snap.getValue(String::class.java) ?: "guest"
            _currentLockRole.postValue(myRoleForCurrentLock)
        }

        listenToCurrentLockStatus()
        listenToCooldown()
        if (isHybridModeEnabled) {
            startBleAdvertising()
            fetchLockLocation()
        }
        refreshUnifiedState()
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
                    if (pendingManualOpen) {
                        logRepository.logAccess(currentLockId, "MANUAL")
                        pendingManualOpen = false
                    } else {
                        checkAndLogEspUnlock()
                    }
                }

                if (lockModel.status == "LOCKED") pendingManualOpen = false
                lastLoggedStatus = lockModel.status
                refreshUnifiedState()
            }
        )
    }

    fun listenToCooldown() {
        cooldownListener?.let { cooldownRef?.removeEventListener(it) }
        if (currentLockId.isEmpty()) return

        cooldownRef = FirebaseClient.getReference("locks/$currentLockId/cooldownActive")
        cooldownListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.getValue(Boolean::class.java) ?: false
                if (active) {
                    startLocalCooldownTimer(120)
                } else {
                    cooldownTimer?.cancel()
                    _cooldownLeft.postValue(0)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        cooldownRef?.addValueEventListener(cooldownListener!!)
    }

    private fun startLocalCooldownTimer(seconds: Int) {
        cooldownTimer?.cancel()
        cooldownTimer = object : CountDownTimer((seconds * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _cooldownLeft.postValue((millisUntilFinished / 1000).toInt())
            }
            override fun onFinish() {
                _cooldownLeft.postValue(0)
                if (currentLockId.isNotEmpty()) {
                    FirebaseClient.getReference("locks/$currentLockId/cooldownActive").setValue(false)
                }
            }
        }.start()
    }

    fun resetCooldown() {
        if (currentLockId.isNotEmpty()) {
            FirebaseClient.getReference("locks/$currentLockId/cooldownActive").setValue(false)
        }
    }

    private fun checkAndLogEspUnlock() {
        if (currentLockId.isEmpty()) return
        val methodRef = FirebaseClient.getReference("locks/$currentLockId/lastUnlockMethod")
        val rssiRef = FirebaseClient.getReference("locks/$currentLockId/lastUnlockRssi")

        methodRef.get().addOnSuccessListener { methodSnap ->
            val method = methodSnap.getValue(String::class.java)
            if (!method.isNullOrEmpty() && method != "NONE") {
                rssiRef.get().addOnSuccessListener { rssiSnap ->
                    val rssi = rssiSnap.getValue(Int::class.java) ?: 0
                    logRepository.logAccess(currentLockId, method, rssi)
                    methodRef.setValue("NONE")
                    rssiRef.setValue(0)
                }
            }
        }
    }

    private fun mapStatus(status: String) = when (status.uppercase()) {
        "LOCKED" -> "🔒 Locked"
        "UNLOCKED" -> "🔓 Unlocked"
        else -> status
    }

    private fun refreshUnifiedState() {
        val status = _lockStatus.value ?: "LOCKED"

        val text = if (!isHybridModeEnabled) {
            mapStatus(status)
        } else if (lockLocation == null) {
            "📍 Kérlek, mentsd el a zár helyzetét!"
        } else if (!insideRadius) {
            "🚶 Away — auto-unlock paused"
        } else if (status == "UNLOCKED") {
            "🔓 Unlocked — welcome home"
        } else {
            val d = lastDistanceMeters?.toInt()
            if (lastFixTrustworthy && d != null) "🎯 Armed ($d m) — approach to unlock"
            else "🎯 Armed — approach to unlock"
        }
        _unifiedStateText.postValue(text)
    }

    fun setHybridMode(enabled: Boolean) {
        isHybridModeEnabled = enabled
        awayCandidateSince = 0L
        if (enabled) {
            insideRadius = true
            startBleAdvertising()
            _bleSwitchState.postValue(true)
        } else {
            insideRadius = false
            stopBleAdvertising()
            _bleSwitchState.postValue(false)
        }
        refreshUnifiedState()
    }

    private fun setProximityFlag(enabled: Boolean) {
        if (currentLockId.isNotEmpty()) {
            FirebaseClient.getReference("locks/$currentLockId/bleProximityEnabled").setValue(enabled)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleAdvertising() {
        val ctx = appContext ?: return
        if (!isAdvertising) {
            val prefs = ctx.getSharedPreferences("smartlock_prefs", Context.MODE_PRIVATE)
            val beaconUUID = prefs.getString("my_beacon_uuid", null) ?: return
            BleAdvertiserService.start(ctx, beaconUUID)
            isAdvertising = true
        }
        setProximityFlag(true)
    }

    private fun stopBleAdvertising() {
        setProximityFlag(false)
        if (isAdvertising) {
            appContext?.let { BleAdvertiserService.stop(it) }
            isAdvertising = false
        }
    }

    fun fetchLockLocation() {
        lockRepository.fetchLockLocation(
            lockId = currentLockId,
            onSuccess = { location ->
                lockLocation = location
                refreshUnifiedState()
            },
            onFailure = { err ->
                Log.w(TAG, "Lock location unavailable: $err")
                lockLocation = null
                lastFixTrustworthy = false
                refreshUnifiedState()
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun saveCurrentLocationAsLock(context: Context) {
        if (currentLockId.isEmpty()) return
        _toastMessage.postValue("Koordináták lekérése...")

        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            if (location != null) {
                uploadLockLocation(location)
            } else {
                fusedLocationClient?.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    ?.addOnSuccessListener { freshLocation ->
                        if (freshLocation != null) {
                            uploadLockLocation(freshLocation)
                        } else {
                            _toastMessage.postValue("Sikertelen helymeghatározás. Kapcsold be a GPS-t!")
                        }
                    }?.addOnFailureListener { e ->
                        _toastMessage.postValue("GPS Hiba: ${e.message}")
                    }
            }
        }?.addOnFailureListener { e ->
            _toastMessage.postValue("Hiba: ${e.message}")
        }
    }

    private fun uploadLockLocation(location: Location) {
        val lockRef = FirebaseClient.getReference("locks/$currentLockId/location")
        val locationMap = mapOf(
            "lat" to location.latitude,
            "lng" to location.longitude
        )

        lockRef.setValue(locationMap)
            .addOnSuccessListener {
                lockLocation = location
                _toastMessage.postValue("Zár helyzete elmentve a Firebase-be!")
                refreshUnifiedState()
            }
            .addOnFailureListener { e ->
                _toastMessage.postValue("Sikertelen Firebase mentés: ${e.message}")
                Log.e(TAG, "Hiba a helyzet mentésekor", e)
            }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val myLocation = result.lastLocation ?: return
                val target = lockLocation ?: return
                if (!isHybridModeEnabled) return

                val distanceMeters = myLocation.distanceTo(target)
                lastDistanceMeters = distanceMeters

                val enterR = geofenceRadiusMeters
                val exitR = geofenceRadiusMeters

                val acc = if (myLocation.hasAccuracy()) myLocation.accuracy else 25f
                lastFixTrustworthy = acc <= 30f

                if (lastFixTrustworthy) {
                    when {
                        distanceMeters <= enterR -> {
                            insideRadius = true
                            if (!isAdvertising) startBleAdvertising()
                            _bleSwitchState.postValue(true)
                        }
                        distanceMeters > exitR -> {
                            insideRadius = false
                            stopBleAdvertising()
                            _bleSwitchState.postValue(false)
                        }
                    }
                }

                refreshUnifiedState()
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

    fun onToastShown() { _toastMessage.value = null }

    override fun onCleared() {
        super.onCleared()
        cooldownTimer?.cancel()
        cooldownListener?.let { cooldownRef?.removeEventListener(it) }
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