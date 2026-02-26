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
                Log.d(TAG, "Command sent: $command → $lockId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Command sending error: ${e.message}")
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
                Log.d(TAG, "Status updated: $lockId → $status")
                onStatusChanged(LockModel(id = lockId, status = status))
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Status listening error: ${error.message}")
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
        Log.d(TAG, "Status listening stopped: $lockId")
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
                Log.d(TAG, "Coordinates fetched: lat=$lat, lng=$lng ($lockId)")

                if (lat != null && lng != null) {
                    val location = Location("firebase").apply {
                        latitude = lat
                        longitude = lng
                    }
                    onSuccess(location)
                } else {
                    val msg = "Missing coordinates: locks/$lockId/location"
                    Log.w(TAG, msg)
                    onFailure(msg)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Coordinate fetching error: ${e.message}")
                onFailure(e.message ?: "Unknown error")
            }
    }

    // Több zár adatainak egyszerre lekérése
    fun fetchMyLocks(lockIds: List<String>, onResult: (List<LockModel>) -> Unit) {
        if (lockIds.isEmpty()) {
            onResult(emptyList())
            return
        }

        val locks   = mutableListOf<LockModel>()
        var fetched = 0

        for (lockId in lockIds) {
            FirebaseClient.getReference("locks/$lockId")
                .get()
                .addOnSuccessListener { snapshot ->
                    val name       = snapshot.child("name").getValue(String::class.java) ?: lockId
                    val status     = snapshot.child("status").getValue(String::class.java) ?: "UNKNOWN"
                    val macAddress = snapshot.child("macAddress").getValue(String::class.java) ?: ""
                    val lat        = snapshot.child("location/lat").getValue(Double::class.java)
                    val lng        = snapshot.child("location/lng").getValue(Double::class.java)
                    val addedBy    = snapshot.child("addedBy").getValue(String::class.java) ?: ""

                    locks.add(LockModel(id = lockId, name = name, status = status,
                        macAddress = macAddress, latitude = lat, longitude = lng, addedBy = addedBy))
                    fetched++
                    if (fetched == lockIds.size) onResult(locks)
                }
                .addOnFailureListener {
                    fetched++
                    if (fetched == lockIds.size) onResult(locks)
                }
        }
    }

    fun addLock(
        lockId: String,
        lockName: String,
        macAddress: String,
        addedByUid: String,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val lockData = hashMapOf(
            "name"       to lockName,
            "macAddress" to macAddress,
            "command"    to "NONE",
            "status"     to "LOCKED",
            "addedBy"    to addedByUid,
            "location"   to hashMapOf("lat" to latitude, "lng" to longitude)
        )
        FirebaseClient.getReference("locks/$lockId")
            .setValue(lockData)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it.message ?: "Error") }
    }
}

