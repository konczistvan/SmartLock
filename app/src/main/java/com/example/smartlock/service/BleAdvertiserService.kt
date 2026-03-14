package com.example.smartlock.service

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.smartlock.R
import com.example.smartlock.ui.main.MainActivity

class BleAdvertiserService : Service() {

    companion object {
        const val TAG = "BleAdvertiser"
        const val CHANNEL_ID = "ble_advertiser_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_BEACON_UUID = "beacon_uuid"

        val SMARTLOCK_SERVICE_UUID: ParcelUuid =
            ParcelUuid.fromString("0000ABCD-0000-1000-8000-00805F9B34FB")

        fun start(context: Context, beaconUUID: String) {
            val intent = Intent(context, BleAdvertiserService::class.java).apply {
                putExtra(EXTRA_BEACON_UUID, beaconUUID)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleAdvertiserService::class.java))
        }
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    // Simple legacy callback
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertising = true
            Log.d(TAG, "Advertising started OK!")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Log.e(TAG, "Advertising failed, errorCode=$errorCode")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val beaconUUID = intent?.getStringExtra(EXTRA_BEACON_UUID)
        if (beaconUUID.isNullOrEmpty()) {
            Log.e(TAG, "No beacon UUID provided")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        startAdvertising(beaconUUID)

        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun startAdvertising(beaconUUID: String) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BLE Advertiser not supported")
            return
        }

        // Stop any previous advertising
        if (isAdvertising) {
            try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
            isAdvertising = false
        }

        val serviceDataBytes = uuidToBytes(beaconUUID)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)  // Advertise forever
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(SMARTLOCK_SERVICE_UUID)
            .addServiceData(SMARTLOCK_SERVICE_UUID, serviceDataBytes)
            .build()

        try {
            advertiser?.startAdvertising(settings, advertiseData, advertiseCallback)
            Log.d(TAG, "Started advertising UUID: $beaconUUID")
            Log.d(TAG, "Service data bytes: ${serviceDataBytes.joinToString(" ") { "%02x".format(it) }}")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    private fun uuidToBytes(uuidStr: String): ByteArray {
        val hex = uuidStr.replace("-", "").lowercase()
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    @Suppress("MissingPermission")
    private fun stopAdvertising() {
        if (isAdvertising) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                Log.d(TAG, "Advertising stopped")
            } catch (e: SecurityException) {
                Log.e(TAG, "Stop error: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SmartLock BLE",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLE beacon signal for auto-unlock"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartLock Active")
            .setContentText("BLE beacon running for auto-unlock")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopAdvertising()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}