package com.example.smartlock.data.model

data class LogModel(
    val lockId: String,
    val userEmail: String,
    val method: String,
    val deviceModel: String,
    val rssi: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val formattedDate: String = ""
)