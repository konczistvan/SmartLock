package com.example.smartlock.ui.activation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.smartlock.R
import com.example.smartlock.api.FirebaseClient
import java.util.UUID

class ActivationActivity : AppCompatActivity() {

    companion object {
        const val TAG = "Activation"
        const val ACTIVATION_SERVICE_UUID = "12345678-1234-1234-1234-123456789abc"
        const val ACTIVATION_CHAR_UUID    = "12345678-1234-1234-1234-123456789abd"
        const val ACTIVATION_STATUS_UUID  = "12345678-1234-1234-1234-123456789abe"
        const val SCAN_TIMEOUT = 30000L
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnActivate: Button
    private lateinit var tvInstructions: TextView

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.all { it.value }
        Log.d(TAG, "Permissions result: $results allGranted=$allGranted")
        if (allGranted) {
            startBleScan()
        } else {
            tvStatus.text = "Bluetooth permissions required!"
            btnActivate.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)

        supportActionBar?.title = "Activate Lock"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvStatus = findViewById(R.id.tvActivationStatus)
        progressBar = findViewById(R.id.progressBarActivation)
        btnActivate = findViewById(R.id.btnStartActivation)
        tvInstructions = findViewById(R.id.tvActivationInstructions)

        tvInstructions.text = "1. Power on your new SmartLock\n" +
                "2. Wait for the LED to blink rapidly\n" +
                "3. Press 'Start Activation' below\n" +
                "4. The app will find and pair with the lock"

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner

        btnActivate.setOnClickListener {
            startActivation()
        }
    }

    private fun startActivation() {
        val uid = FirebaseClient.auth.currentUser?.uid
        if (uid == null) {
            tvStatus.text = "Error: Not logged in!"
            return
        }
        if (bleScanner == null) {
            tvStatus.text = "Error: Bluetooth not available"
            return
        }

        btnActivate.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Checking permissions..."

        // Check and request permissions
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_SCAN)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        Log.d(TAG, "Needed permissions: $needed")

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            startBleScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        tvStatus.text = "Searching for SmartLock..."
        Log.d(TAG, "Starting BLE scan...")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(null, settings, scanCallback)
            isScanning = true
            Log.d(TAG, "BLE scan started OK")

            handler.postDelayed({
                if (isScanning) {
                    stopScan()
                    Log.d(TAG, "Scan timeout — lock not found")
                    tvStatus.text = "Lock not found. Make sure it's in activation mode."
                    btnActivate.isEnabled = true
                    progressBar.visibility = View.GONE
                }
            }, SCAN_TIMEOUT)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            tvStatus.text = "Permission error: ${e.message}"
            btnActivate.isEnabled = true
            progressBar.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Scan error: ${e.message}")
            tvStatus.text = "Scan error: ${e.message}"
            btnActivate.isEnabled = true
            progressBar.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device
            val deviceName = device.name ?: result.scanRecord?.deviceName ?: ""
            val addr = device.address ?: ""

            // Log ALL devices for debug
            if (deviceName.isNotEmpty() || addr.uppercase().startsWith("0C:DC:7E")) {
                Log.d(TAG, "Scan: addr=$addr name='$deviceName' rssi=${result.rssi}")
            }

            val isSmartLock = deviceName.contains("SmartLock", ignoreCase = true)
                    || addr.uppercase().startsWith("0C:DC:7E")

            if (isSmartLock) {
                Log.d(TAG, ">>> SMARTLOCK FOUND! name='$deviceName' addr=$addr")
                stopScan()
                runOnUiThread {
                    tvStatus.text = "Found lock!\nConnecting..."
                }
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "onScanFailed: errorCode=$errorCode")
            runOnUiThread {
                tvStatus.text = "Scan failed (error $errorCode)"
                btnActivate.isEnabled = true
                progressBar.visibility = View.GONE
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (isScanning) {
            try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
            isScanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address}...")
        bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "Connection state: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT Connected!")
                    runOnUiThread { tvStatus.text = "Connected! Discovering services..." }
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT Disconnected")
                    runOnUiThread {
                        if (tvStatus.text.contains("✅")) return@runOnUiThread
                        tvStatus.text = "Disconnected. Try again."
                        btnActivate.isEnabled = true
                        progressBar.visibility = View.GONE
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d(TAG, "Services discovered, status=$status")

            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                runOnUiThread { tvStatus.text = "Service discovery failed" }
                return
            }

            for (service in gatt.services) {
                Log.d(TAG, "  Service: ${service.uuid}")
                for (ch in service.characteristics) {
                    Log.d(TAG, "    Char: ${ch.uuid} props=${ch.properties}")
                }
            }

            val service = gatt.getService(UUID.fromString(ACTIVATION_SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "Activation service NOT found!")
                runOnUiThread { tvStatus.text = "Activation service not found on lock" }
                return
            }

            Log.d(TAG, "Activation service found!")

            val statusChar = service.getCharacteristic(UUID.fromString(ACTIVATION_STATUS_UUID))
            if (statusChar != null) {
                gatt.setCharacteristicNotification(statusChar, true)
                val descriptor = statusChar.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                    Log.d(TAG, "Subscribed to status notifications")
                } else {
                    sendActivationData(gatt)
                }
            } else {
                sendActivationData(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            Log.d(TAG, "Descriptor written, status=$status")
            if (gatt != null) sendActivationData(gatt)
        }

        @Deprecated("Deprecated in API 33+")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val value = characteristic?.value ?: return
            handleActivationResponse(gatt, String(value))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleActivationResponse(gatt, String(value))
        }

        @SuppressLint("MissingPermission")
        private fun handleActivationResponse(gatt: BluetoothGatt?, response: String) {
            Log.d(TAG, "Activation response: $response")
            runOnUiThread {
                progressBar.visibility = View.GONE
                if (response == "OK") {
                    tvStatus.text = "✅ Lock activated successfully!\n\nYou are now the owner."
                    btnActivate.text = "Done"
                    btnActivate.isEnabled = true
                    btnActivate.setOnClickListener { setResult(RESULT_OK); finish() }
                } else {
                    tvStatus.text = "❌ Activation failed. Try again."
                    btnActivate.isEnabled = true
                }
            }
            gatt?.disconnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendActivationData(gatt: BluetoothGatt) {
        val uid = FirebaseClient.auth.currentUser?.uid ?: return
        val beaconUUID = UUID.randomUUID().toString().replace("-", "")

        getSharedPreferences("smartlock_prefs", MODE_PRIVATE)
            .edit().putString("my_beacon_uuid", beaconUUID).apply()

        val payload = "$uid|$beaconUUID"
        Log.d(TAG, "Sending: $payload")
        runOnUiThread { tvStatus.text = "Sending activation data..." }

        val service = gatt.getService(UUID.fromString(ACTIVATION_SERVICE_UUID))
        val writeChar = service?.getCharacteristic(UUID.fromString(ACTIVATION_CHAR_UUID))

        if (writeChar != null) {
            writeChar.value = payload.toByteArray(Charsets.UTF_8)
            writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val sent = gatt.writeCharacteristic(writeChar)
            Log.d(TAG, "Write result: $sent")
            runOnUiThread { tvStatus.text = "Data sent. Waiting for response..." }
        } else {
            Log.e(TAG, "Write characteristic not found!")
            runOnUiThread { tvStatus.text = "Error: Write characteristic not found" }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        stopScan()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}