package com.example.smartlock.data.repository

import android.util.Log
import com.example.smartlock.api.FirebaseClient

class AuthRepository {

    private val TAG = "AuthRepository"

    fun login(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        FirebaseClient.auth
            .signInWithEmailAndPassword(email, password)
            .addOnSuccessListener {
                Log.d(TAG, "Bejelentkezés sikeres: $email")
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Bejelentkezési hiba: ${e.message}")
                onFailure(e.message ?: "Ismeretlen hiba")
            }
    }

    fun logout() {
        FirebaseClient.auth.signOut()
        Log.d(TAG, "Kijelentkezve")
    }

    fun isLoggedIn(): Boolean {
        return FirebaseClient.auth.currentUser != null
    }
}