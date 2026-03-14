package com.example.smartlock.data.model

data class LockModel(
    val id: String = "",
    val name: String = "",
    val status: String = "UNKNOWN",
    val macAddress: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val addedBy: String = "",
    val beaconUUID: String = "",       // NEW: phone beacon UUID for this lock
    val activated: Boolean = false      // NEW: whether lock has been activated
)