package com.example.smartlock.data.repository

import android.location.Location
import android.util.Log
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.model.LockModel
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener

class LockRepository {

    private val TAG = "LockRepository"

    private var statusListener: ValueEventListener? = null
    private var currentListenedLockId: String? = null

    fun sendCommand(lockId: String, command: String) {
        FirebaseClient
            .getReference("locks/$lockId/command")
            .setValue(command)
            .addOnSuccessListener {
                Log.d(TAG, "Parancs elküldve: $command → $lockId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Parancs küldési hiba: ${e.message}")
            }
    }

    fun listenToLockStatus(
        lockId: String,
        onStatusChanged: (LockModel) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        stopListening()

        currentListenedLockId = lockId
        val ref = FirebaseClient.getReference("locks/$lockId/status")

        statusListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.getValue(String::class.java) ?: "UNKNOWN"
                Log.d(TAG, "Státusz frissült: $lockId → $status")
                onStatusChanged(LockModel(id = lockId, status = status))
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Státusz figyelés hiba: ${error.message}")
                onError(error.message)
            }
        }

        ref.addValueEventListener(statusListener!!)
    }

    fun stopListening() {
        val lockId = currentListenedLockId ?: return
        val listener = statusListener ?: return

        FirebaseClient
            .getReference("locks/$lockId/status")
            .removeEventListener(listener)

        statusListener = null
        currentListenedLockId = null
        Log.d(TAG, "Státusz figyelés leállítva: $lockId")
    }

    fun fetchLockLocation(
        lockId: String,
        onSuccess: (Location) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val ref = FirebaseClient.getReference("locks/$lockId/location")

        ref.keepSynced(false)
        FirebaseClient.database.goOnline()

        ref.get()
            .addOnSuccessListener { snapshot ->
                val lat = snapshot.child("lat").getValue(Double::class.java)
                val lng = snapshot.child("lng").getValue(Double::class.java)
                Log.d(TAG, "Koordináták lekérve: lat=$lat, lng=$lng ($lockId)")

                if (lat != null && lng != null) {
                    val location = Location("firebase").apply {
                        latitude = lat
                        longitude = lng
                    }
                    onSuccess(location)
                } else {
                    val msg = "Hiányzó koordináták: locks/$lockId/location"
                    Log.w(TAG, msg)
                    onFailure(msg)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Koordináta lekérési hiba: ${e.message}")
                onFailure(e.message ?: "Ismeretlen hiba")
            }
    }
}