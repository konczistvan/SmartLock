package com.example.smartlock.data.repository

import android.util.Log
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.model.LogModel

class LogRepository {

    fun logAccess(lockId: String, method: String, rssi: Int = 0) {
        val uid = FirebaseClient.auth.currentUser?.uid ?: return
        val email = FirebaseClient.currentUserEmail

        val log = mapOf(
            "lock_id" to lockId,
            "user_email" to email,
            "method" to method,
            "device_model" to android.os.Build.MODEL,
            "rssi" to rssi,
            "timestamp" to System.currentTimeMillis(),
            "formatted_date" to java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
            ).format(java.util.Date())
        )

        FirebaseClient.getReference("logs")
            .push()
            .setValue(log)
    }

    fun getLogs(lockId: String, callback: (List<LogModel>) -> Unit) {
        Log.d("LogRepository", "Reading logs for lockId: $lockId")

        FirebaseClient.getReference("logs")
            .orderByChild("lock_id")
            .equalTo(lockId)
            .limitToLast(50)
            .get()
            .addOnSuccessListener { snapshot ->
                Log.d(
                    "LogRepository",
                    "Snapshot exists: ${snapshot.exists()}, children: ${snapshot.childrenCount}"
                )

                val logs = snapshot.children.mapNotNull { it.getValue(LogModel::class.java) }
                    .sortedByDescending { it.timestamp }

                Log.d("LogRepository", "Parsed logs count: ${logs.size}")
                callback(logs)
            }
            .addOnFailureListener { e ->
                Log.e("LogRepository", "Failed to read logs: ${e.message}")
                callback(emptyList())
            }
    }
}