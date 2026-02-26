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
                Log.d(TAG, "Login successful: $email")
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Login error: ${e.message}")
                onFailure(e.message ?: "Unknown error")
            }
    }

    fun register(
        email: String,
        password: String,
        displayName: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        FirebaseClient.auth
            .createUserWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener
                // Mentjük a user profilját a Firebase "users" táblába
                UserRepository().saveUserProfile(uid, email, displayName)
                Log.d(TAG, "Register successful: $email")
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Register error: ${e.message}")
                onFailure(e.message ?: "Unknown error")
            }
    }

    fun logout() {
        FirebaseClient.auth.signOut()
        Log.d(TAG, "Logged out")
    }

    fun isLoggedIn(): Boolean {
        return FirebaseClient.auth.currentUser != null
    }
}