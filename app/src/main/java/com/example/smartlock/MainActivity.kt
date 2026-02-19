package com.example.smartlock

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TEST_EMAIL = "admin@smartlock.com"
    private val TEST_PASS = "123456"

    private val PROXIMITY_THRESHOLD = -50
    private val UNLOCK_COOLDOWN_MS = 10000L

    private val deviceMap = mapOf(
        "0CDC7E614162" to "LOCK_0CDC7E614160",
        "0CDC7E5D076E" to "LOCK_0CDC7E5D076C",
        "B8F862E0BCBD" to "LOCK_B8F862E0BCBC"
    )
    private val lockList = deviceMap.values.toList()
    private var currentLockId = lockList[0]

    // Geofence
    private var geofenceRadiusMeters = 50f
    private var lockLocation: Location? = null
    private var isGeofenceEnabled = false
    private var lastGeofenceUnlock = 0L
    private var wasInsideGeofence = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var statusListener: ValueEventListener? = null
    private var statusRef: DatabaseReference? = null

    private var isScanning = false
    private var lastUnlockTime = 0L
    private var lastLoggedStatus = ""

    private lateinit var tvStatus: TextView
    private lateinit var btnOpen: Button
    private lateinit var btnClose: Button
    private lateinit var spinnerLocks: Spinner
    private lateinit var switchAutoUnlock: SwitchMaterial
    private lateinit var switchGeofence: SwitchMaterial
    private lateinit var seekBarRadius: SeekBar
    private lateinit var tvRadius: TextView
    private lateinit var tvGeofenceStatus: TextView

    // ---- Permission launcher ----
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permission required!", Toast.LENGTH_LONG).show()
            switchAutoUnlock.isChecked = false
            switchGeofence.isChecked = false
        }
    }

    // ====================================================
    //  onCreate
    // ====================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus         = findViewById(R.id.tvStatus)
        btnOpen          = findViewById(R.id.btnOpen)
        btnClose         = findViewById(R.id.btnClose)
        spinnerLocks     = findViewById(R.id.spinnerLocks)
        switchAutoUnlock = findViewById(R.id.switchAutoUnlock)
        switchGeofence   = findViewById(R.id.switchGeofence)
        seekBarRadius    = findViewById(R.id.seekBarRadius)
        tvRadius         = findViewById(R.id.tvRadius)
        tvGeofenceStatus = findViewById(R.id.tvGeofenceStatus)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        enableButtons(false)

        auth     = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance(
            "https://smartlock-system-f76d3-default-rtdb.europe-west1.firebasedatabase.app"
        )

        setupSpinner()
        setupGeofenceUI()
        setupLocationCallback()
        loginUser()

        btnOpen.setOnClickListener  { sendCommand(currentLockId, "OPEN") }
        btnClose.setOnClickListener { sendCommand(currentLockId, "CLOSE") }

        switchAutoUnlock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasBluetoothPermissions()) startBLEScan()
                else { requestBluetoothPermissions(); switchAutoUnlock.isChecked = false }
            } else {
                stopBLEScan()
            }
        }
    }

    // ====================================================
    //  Geofence UI + SeekBar
    // ====================================================
    private fun setupGeofenceUI() {
        // SeekBar: 0..490  →  radius: 10..500
        seekBarRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                geofenceRadiusMeters = (progress + 3).toFloat()
                tvRadius.text = "Radius: ${geofenceRadiusMeters.toInt()} m"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        switchGeofence.setOnCheckedChangeListener { _, isChecked ->
            isGeofenceEnabled = isChecked
            if (isChecked) {
                if (hasLocationPermission()) {
                    fetchLockLocationFromFirebase(currentLockId)
                    startLocationUpdates()
                    tvGeofenceStatus.text = "GPS: keresés..."
                } else {
                    requestLocationPermissions()
                    switchGeofence.isChecked = false
                }
            } else {
                stopLocationUpdates()
                tvGeofenceStatus.text = "GPS: –"
            }
        }
    }

    // ====================================================
    //  Location callback – geofence logika
    // ====================================================
    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val myLocation = result.lastLocation ?: return
                val target = lockLocation

                if (target == null) {
                    tvGeofenceStatus.text = "GPS: zár koordinátái hiányoznak a Firebase-ből"
                    return
                }

                val distanceMeters = myLocation.distanceTo(target)
                val inside = distanceMeters <= geofenceRadiusMeters

                tvGeofenceStatus.text = "GPS: ${distanceMeters.toInt()} m a zártól " +
                        "(limit: ${geofenceRadiusMeters.toInt()} m) ${if (inside) "✓ BENT" else "KINT"}"

                if (inside && !wasInsideGeofence) {
                    // Belépés a geofence-be → nyitás
                    val now = System.currentTimeMillis()
                    if (now - lastGeofenceUnlock > UNLOCK_COOLDOWN_MS) {
                        Log.d("GEOFENCE", "Entered zone – auto opening $currentLockId")
                        sendCommand(currentLockId, "OPEN")
                        logAccessToFirebase(currentLockId, "AUTO_GEOFENCE")
                        lastGeofenceUnlock = now
                        tvStatus.text = "GEOFENCE: $currentLockId nyitva!"
                        Toast.makeText(applicationContext,
                            "GEOFENCE AUTO OPEN: $currentLockId", Toast.LENGTH_LONG).show()
                    }
                }
                wasInsideGeofence = inside
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateDistanceMeters(2f).build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        wasInsideGeofence = false
    }

    // ====================================================
    //  Firebase: zár koordinátáinak lekérése
    // ====================================================
    private fun fetchLockLocationFromFirebase(lockId: String) {
        val ref = database.getReference("locks/$lockId/location")

        // Cache letiltás ennél a referenciánál
        ref.keepSynced(false)

        // Explicit online mód kényszerítés
        database.goOnline()

        ref.get().addOnSuccessListener { snapshot ->
            val lat = snapshot.child("lat").getValue(Double::class.java)
            val lng = snapshot.child("lng").getValue(Double::class.java)
            Log.d("GEOFENCE", "Firebase lat=$lat lng=$lng (lockId=$lockId)")

            if (lat != null && lng != null) {
                lockLocation = Location("firebase").apply {
                    latitude  = lat
                    longitude = lng
                }
                tvGeofenceStatus.text = "GPS: koordináták OK ($lat, $lng)"
            } else {
                tvGeofenceStatus.text = "⚠ Hiányzó koordináták: locks/$lockId/location"
            }
        }.addOnFailureListener { e ->
            Log.e("GEOFENCE", "Firebase read error: ${e.message}")
            tvGeofenceStatus.text = "⚠ Hiba: ${e.message}"
        }
    }

    // ====================================================
    //  BLE Scan (változatlan logika)
    // ====================================================
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { scanResult ->
                if (ActivityCompat.checkSelfPermission(this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    val cleanMac = scanResult.device.address.replace(":", "").uppercase()
                    val rssi = scanResult.rssi
                    if (deviceMap.containsKey(cleanMac)) {
                        val targetLockId = deviceMap[cleanMac]!!
                        if (rssi > PROXIMITY_THRESHOLD) {
                            val now = System.currentTimeMillis()
                            if (now - lastUnlockTime > UNLOCK_COOLDOWN_MS) {
                                val index = lockList.indexOf(targetLockId)
                                if (index >= 0 && index != spinnerLocks.selectedItemPosition) {
                                    spinnerLocks.setSelection(index)
                                    currentLockId = targetLockId
                                    listenToLockStatus()
                                }
                                tvStatus.text = "BLE: $targetLockId nyitása..."
                                sendCommand(targetLockId, "OPEN")
                                logAccessToFirebase(targetLockId, "AUTO_BLE", rssi)
                                lastUnlockTime = now
                                Toast.makeText(applicationContext, "AUTO BLE OPEN: $targetLockId", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startBLEScan() {
        if (!hasBluetoothPermissions() || bluetoothAdapter == null || isScanning) return
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            bluetoothAdapter!!.bluetoothLeScanner.startScan(null, settings, scanCallback)
            isScanning = true
            tvStatus.text = "BLE: keresés..."
        } catch (e: SecurityException) { }
    }

    private fun stopBLEScan() {
        if (!hasBluetoothPermissions() || bluetoothAdapter == null || !isScanning) return
        try {
            bluetoothAdapter!!.bluetoothLeScanner.stopScan(scanCallback)
            isScanning = false
            tvStatus.text = "BLE: leállítva"
        } catch (e: SecurityException) { }
    }

    // ====================================================
    //  Firebase helpers
    // ====================================================
    private fun logAccessToFirebase(lockId: String, method: String, rssi: Int = 0) {
        val logRef = database.getReference("logs").push()
        val logData = hashMapOf(
            "timestamp"      to System.currentTimeMillis(),
            "formatted_date" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
            "lock_id"        to lockId,
            "user_email"     to (auth.currentUser?.email ?: "Unknown"),
            "method"         to method,
            "device_model"   to Build.MODEL,
            "rssi"           to rssi
        )
        logRef.setValue(logData).addOnFailureListener { e -> Log.e("LOG", "Error: ${e.message}") }
    }

    private fun setupSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lockList)
        spinnerLocks.adapter = adapter
        spinnerLocks.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                currentLockId = lockList[position]
                if (auth.currentUser != null) {
                    listenToLockStatus()
                    if (isGeofenceEnabled) fetchLockLocationFromFirebase(currentLockId)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun loginUser() {
        auth.signInWithEmailAndPassword(TEST_EMAIL, TEST_PASS).addOnCompleteListener(this) { task ->
            if (task.isSuccessful) {
                enableButtons(true)
                listenToLockStatus()
                tvStatus.text = "Bejelentkezve"
            } else {
                tvStatus.text = "Login hiba!"
                Toast.makeText(this, "Login Hiba!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun listenToLockStatus() {
        statusRef = database.getReference("locks/$currentLockId/status")
        statusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java) ?: return
                if (!isScanning && !isGeofenceEnabled) tvStatus.text = "[$currentLockId]\n$status"
                if (status == "UNLOCKED" && lastLoggedStatus != "UNLOCKED") {
                    logAccessToFirebase(currentLockId, "MANUAL")
                }
                lastLoggedStatus = status
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        statusRef!!.addValueEventListener(statusListener!!)
    }

    private fun sendCommand(lockId: String, command: String) {
        database.getReference("locks/$lockId/command").setValue(command)
    }

    // ====================================================
    //  Permissions
    // ====================================================
    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)  == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestLocationPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        requestPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        } else {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }
    }

    private fun enableButtons(enable: Boolean) {
        btnOpen.isEnabled  = enable
        btnClose.isEnabled = enable
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
    }
}
