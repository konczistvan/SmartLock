package com.example.smartlock.data.repository

import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.model.PermissionModel

class PermissionRepository {

    fun grantAccess(
        lockId: String,
        targetUid: String,
        role: String,
        expiresAt: Long?,
        grantedByUid: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val permissionData = mapOf(
            "role" to role,
            "grantedBy" to grantedByUid,
            "grantedAt" to System.currentTimeMillis(),
            "expiresAt" to expiresAt
        )

        FirebaseClient.getReference("permissions/$lockId/$targetUid")
            .setValue(permissionData)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it.message ?: "Unknown error") }
    }

    fun revokeAccess(
        lockId: String,
        targetUid: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        FirebaseClient.getReference("permissions/$lockId/$targetUid")
            .removeValue()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it.message ?: "Unknown error") }
    }

    fun getMyLockIds(uid: String, callback: (List<Pair<String, String>>) -> Unit) {
        FirebaseClient.getReference("permissions")
            .get()
            .addOnSuccessListener { snapshot ->
                val result = mutableListOf<Pair<String, String>>()
                for (lockSnap in snapshot.children) {
                    val lockId = lockSnap.key ?: continue
                    val userSnap = lockSnap.child(uid)
                    if (userSnap.exists()) {
                        val role = userSnap.child("role").getValue(String::class.java) ?: "guest"

                        val expiresAt = userSnap.child("expiresAt").getValue(Long::class.java)
                        if (expiresAt != null && expiresAt < System.currentTimeMillis()) {
                            FirebaseClient.getReference("permissions/$lockId/$uid").removeValue()
                            continue
                        }

                        result.add(Pair(lockId, role))
                    }
                }
                callback(result)
            }
            .addOnFailureListener {
                callback(emptyList())
            }
    }

    fun getAccessList(lockId: String, callback: (List<PermissionModel>) -> Unit) {
        FirebaseClient.getReference("permissions/$lockId")
            .get()
            .addOnSuccessListener { snapshot ->
                val entries = mutableListOf<PermissionModel>()
                for (userSnap in snapshot.children) {
                    val uid = userSnap.key ?: continue
                    val role = userSnap.child("role").getValue(String::class.java) ?: "guest"
                    val grantedBy = userSnap.child("grantedBy").getValue(String::class.java) ?: ""
                    val grantedAt = userSnap.child("grantedAt").getValue(Long::class.java) ?: 0L
                    val expiresAt = userSnap.child("expiresAt").getValue(Long::class.java)

                    entries.add(PermissionModel(uid, role, grantedBy, grantedAt, expiresAt))
                }
                callback(entries)
            }
            .addOnFailureListener {
                callback(emptyList())
            }
    }
}