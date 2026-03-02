package com.example.smartlock.data.model

data class LogModel(
    val lock_id: String = "",
    val user_email: String = "",
    val method: String = "",
    val device_model: String = "",
    val rssi: Int = 0,
    val timestamp: Long = 0L,
    val formatted_date: String = ""
)