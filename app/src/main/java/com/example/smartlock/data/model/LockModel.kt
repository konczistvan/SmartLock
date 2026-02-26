package com.example.smartlock.data.model

data class LockModel(
    val id: String = "",
    val name: String = "",
    val status: String = "UNKNOWN",
    val macAddress: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val addedBy: String = ""
)