package com.example.smartlock.data.repository

import android.util.Log
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.model.PermissionModel

class PermissionRepository {

    private val TAG = "PermissionRepository"

    // Visszaadja az aktuális user zárjait: List<Pair<lockId, role>>
    fun getMyLockIds(uid: String, onResult: (List<Pair<String, String>>) -> Unit) {
        FirebaseClient.getReference("permissions")
            .get()
            .addOnSuccessListener { snapshot ->
                val result = mutableListOf<Pair<String, String>>()
                val now = System.currentTimeMillis()

                for (lockSnapshot in snapshot.children) {
                    val lockId       = lockSnapshot.key ?: continue
                    val permSnapshot = lockSnapshot.child(uid)

                    if (permSnapshot.exists()) {
                        val expiresAt = permSnapshot.child("expiresAt").getValue(Long::class.java)

                        // null = örök, vagy még nem járt le
                        if (expiresAt == null || expiresAt > now) {
                            val role = permSnapshot.child("role").getValue(String::class.java) ?: "guest"
                            result.add(Pair(lockId, role))
                        } else {
                            // Lejárt – töröljük automatikusan
                            FirebaseClient.getReference("permissions/$lockId/$uid").removeValue()
                            Log.d(TAG, "Expired permission removed: $lockId / $uid")
                        }
                    }
                }
                onResult(result)
            }
            .addOnFailureListener {
                Log.e(TAG, "getMyLockIds error: ${it.message}")
                onResult(emptyList())
            }
    }

    // Hozzáférés adása egy usernek
    fun grantAccess(
        lockId: String,
        targetUid: String,
        role: String = "guest",
        expiresAt: Long? = null,
        grantedByUid: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val permData = hashMapOf<String, Any?>(
            "role"      to role,
            "grantedBy" to grantedByUid,
            "grantedAt" to System.currentTimeMillis(),
            "expiresAt" to expiresAt
        )
        FirebaseClient.getReference("permissions/$lockId/$targetUid")
            .setValue(permData)
            .addOnSuccessListener {
                Log.d(TAG, "Access granted: $targetUid → $lockId ($role)")
                onSuccess()
            }
            .addOnFailureListener {
                Log.e(TAG, "grantAccess error: ${it.message}")
                onFailure(it.message ?: "Error")
            }
    }

    // Hozzáférés elvétele
    fun revokeAccess(
        lockId: String,
        targetUid: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        FirebaseClient.getReference("permissions/$lockId/$targetUid")
            .removeValue()
            .addOnSuccessListener {
                Log.d(TAG, "Access revoked: $targetUid from $lockId")
                onSuccess()
            }
            .addOnFailureListener {
                Log.e(TAG, "revokeAccess error: ${it.message}")
                onFailure(it.message ?: "Error")
            }
    }

    // Egy zár összes jogosultságának listája
    fun getAccessList(lockId: String, onResult: (List<PermissionModel>) -> Unit) {
        FirebaseClient.getReference("permissions/$lockId")
            .get()
            .addOnSuccessListener { snapshot ->
                val result = mutableListOf<PermissionModel>()
                for (child in snapshot.children) {
                    val uid       = child.key ?: continue
                    val role      = child.child("role").getValue(String::class.java) ?: "guest"
                    val grantedBy = child.child("grantedBy").getValue(String::class.java) ?: ""
                    val grantedAt = child.child("grantedAt").getValue(Long::class.java) ?: 0L
                    val expiresAt = child.child("expiresAt").getValue(Long::class.java)
                    result.add(PermissionModel(uid = uid, role = role, grantedBy = grantedBy,
                        grantedAt = grantedAt, expiresAt = expiresAt))
                }
                onResult(result)
            }
            .addOnFailureListener { onResult(emptyList()) }
    }
}