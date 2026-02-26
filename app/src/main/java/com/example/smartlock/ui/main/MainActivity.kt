package com.example.smartlock.ui.main

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.smartlock.R
import com.example.smartlock.ui.addlock.AddLockActivity
import com.example.smartlock.ui.login.LoginActivity
import com.example.smartlock.ui.manageaccess.ManageAccessActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var tvStatus:         TextView
    private lateinit var btnOpen:          android.widget.Button
    private lateinit var btnClose:         android.widget.Button
    private lateinit var spinnerLocks:     Spinner
    private lateinit var switchAutoUnlock: SwitchMaterial
    private lateinit var switchGeofence:   SwitchMaterial
    private lateinit var seekBarRadius:    SeekBar
    private lateinit var tvRadius:         TextView
    private lateinit var tvGeofenceStatus: TextView
    private lateinit var fabAddLock:       FloatingActionButton
    private lateinit var btnManageAccess:  android.widget.Button
    private lateinit var tvUserEmail: TextView
    private lateinit var btnLogout: android.widget.Button

    // AddLock activity eredményének figyelése
    private val addLockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Új zár lett hozzáadva – újratöltjük a listát
            viewModel.reloadLocks()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions required!", Toast.LENGTH_LONG).show()
            switchAutoUnlock.isChecked = false
            switchGeofence.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        initBluetooth()
        setupSpinner()
        setupGeofenceUI()
        observeViewModel()
        setupListeners()

        // Nem hardkódolt login – a Firebase Auth session már aktív
        // (a LoginActivity gondoskodott róla)
        viewModel.loginAndLoadLocks()
    }

    private fun bindViews() {
        tvStatus         = findViewById(R.id.tvStatus)
        btnOpen          = findViewById(R.id.btnOpen)
        btnClose         = findViewById(R.id.btnClose)
        spinnerLocks     = findViewById(R.id.spinnerLocks)
        switchAutoUnlock = findViewById(R.id.switchAutoUnlock)
        switchGeofence   = findViewById(R.id.switchGeofence)
        seekBarRadius    = findViewById(R.id.seekBarRadius)
        tvRadius         = findViewById(R.id.tvRadius)
        tvGeofenceStatus = findViewById(R.id.tvGeofenceStatus)
        fabAddLock       = findViewById(R.id.fabAddLock)
        btnManageAccess  = findViewById(R.id.btnManageAccess)
        tvUserEmail      = findViewById(R.id.tvUserEmail)
        btnLogout        = findViewById(R.id.btnLogout)

        val currentUser = com.example.smartlock.api.FirebaseClient.auth.currentUser
        tvUserEmail.text = currentUser?.email ?: ""

        enableButtons(false)
    }

    private fun initBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter: BluetoothAdapter? = bluetoothManager.adapter
        viewModel.init(applicationContext, btAdapter)
    }

    private fun setupSpinner() {
        spinnerLocks.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                viewModel.selectLock(position)
                // Manage gomb láthatósága: csak ownernek
                updateManageButton()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun updateSpinnerData() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            viewModel.lockDisplayNames
        )
        spinnerLocks.adapter = adapter
    }

    private fun updateManageButton() {
        // Kis késleltetés, hogy a selectLock lekérje a szerepkört
        btnManageAccess.postDelayed({
            btnManageAccess.visibility =
                if (viewModel.isOwnerOfCurrentLock()) View.VISIBLE else View.GONE
        }, 500)
    }

    private fun setupGeofenceUI() {
        seekBarRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                viewModel.geofenceRadiusMeters = (progress + 3).toFloat()
                tvRadius.text = "Radius: ${viewModel.geofenceRadiusMeters.toInt()} m"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun observeViewModel() {
        viewModel.statusText.observe(this) { tvStatus.text = it }
        viewModel.geofenceStatusText.observe(this) { tvGeofenceStatus.text = it }

        viewModel.toastMessage.observe(this) { message ->
            if (message != null) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                viewModel.onToastShown()
            }
        }

        viewModel.isLoggedIn.observe(this) { loggedIn ->
            enableButtons(loggedIn)
        }

        // Zárak betöltődtek → frissítjük a spinnert
        viewModel.locksLoaded.observe(this) { loaded ->
            if (loaded) {
                updateSpinnerData()
                updateManageButton()
            }
        }

        viewModel.bleDetectedLockIndex.observe(this) { index ->
            if (index != null) {
                spinnerLocks.setSelection(index)
                viewModel.onBleIndexHandled()
            }
        }
    }

    private fun setupListeners() {
        btnOpen.setOnClickListener  { viewModel.openLock() }
        btnClose.setOnClickListener { viewModel.closeLock() }

        // Zár hozzáadása gomb
        fabAddLock.setOnClickListener {
            addLockLauncher.launch(Intent(this, AddLockActivity::class.java))
        }

        // Hozzáférés kezelése gomb (csak ownereknek látható)
        btnManageAccess.setOnClickListener {
            val lockName = viewModel.myLocks.find { it.id == viewModel.currentLockId }?.name ?: ""
            val intent = Intent(this, ManageAccessActivity::class.java).apply {
                putExtra("lockId", viewModel.currentLockId)
                putExtra("lockName", lockName)
            }
            startActivity(intent)
        }

        switchAutoUnlock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasBluetoothPermissions()) viewModel.startBLEScan()
                else {
                    requestBluetoothPermissions()
                    switchAutoUnlock.isChecked = false
                }
            } else {
                viewModel.stopBLEScan()
            }
        }

        switchGeofence.setOnCheckedChangeListener { _, isChecked ->
            viewModel.isGeofenceEnabled = isChecked
            if (isChecked) {
                if (hasLocationPermission()) {
                    viewModel.fetchLockLocation()
                    viewModel.startLocationUpdates(this)
                    tvGeofenceStatus.text = "GPS: searching..."
                } else {
                    requestLocationPermissions()
                    switchGeofence.isChecked = false
                }
            } else {
                viewModel.stopLocationUpdates()
                tvGeofenceStatus.text = "GPS: –"
            }
        }

        btnLogout.setOnClickListener {
            viewModel.authRepository.logout()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)    == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestLocationPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        requestPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION))
        } else {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN))
        }
    }

    private fun enableButtons(enable: Boolean) {
        btnOpen.isEnabled  = enable
        btnClose.isEnabled = enable
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopLocationUpdates()
    }
}