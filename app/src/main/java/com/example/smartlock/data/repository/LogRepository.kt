package com.example.smartlock.data.repository

import android.os.Build
import android.util.Log
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.model.LogModel
import java.text.SimpleDateFormat
import java.util.*

class LogRepository {

    private val TAG = "LogRepository"

    fun logAccess(lockId: String, method: String, rssi: Int = 0) {
        val log = LogModel(
            lockId = lockId,
            userEmail = FirebaseClient.currentUserEmail,
            method = method,
            deviceModel = Build.MODEL,
            rssi = rssi,
            timestamp = System.currentTimeMillis(),
            formattedDate = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            ).format(Date())
        )

        saveLog(log)
    }

    private fun saveLog(log: LogModel) {
        val logData = hashMapOf(
            "timestamp"      to log.timestamp,
            "formatted_date" to log.formattedDate,
            "lock_id"        to log.lockId,
            "user_email"     to log.userEmail,
            "method"         to log.method,
            "device_model"   to log.deviceModel,
            "rssi"           to log.rssi
        )

        FirebaseClient.getReference("logs")
            .push()
            .setValue(logData)
            .addOnSuccessListener {
                Log.d(TAG, "Log elmentve: ${log.method} → ${log.lockId}")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Log mentési hiba: ${e.message}")
            }
    }
}