package com.example.smartlock.data.model

data class PermissionModel(
    val uid: String = "",
    val role: String = "guest",
    val grantedBy: String = "",
    val grantedAt: Long = 0L,
    val expiresAt: Long? = null,

    //nem a firebaseből jön
    val email: String = "",
    val displayName: String = ""
)