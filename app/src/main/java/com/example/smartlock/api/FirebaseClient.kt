package com.example.smartlock.api

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

object FirebaseClient {

    private const val DATABASE_URL =
        "https://smartlock-system-f76d3-default-rtdb.europe-west1.firebasedatabase.app"

    val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance(DATABASE_URL)
    }


    fun getReference(path: String) = database.getReference(path)


    val currentUserEmail: String
        get() = auth.currentUser?.email ?: "Unknown"
}