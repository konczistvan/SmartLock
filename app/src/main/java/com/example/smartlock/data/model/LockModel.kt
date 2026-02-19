package com.example.smartlock.data.model

data class LockModel(
    val id: String,
    val status: String = "UNKNOWN",
    val latitude: Double? = null,
    val longitude: Double? = null
)