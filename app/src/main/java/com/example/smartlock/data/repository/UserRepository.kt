package com.example.smartlock.data.repository

import android.util.Log
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.model.UserModel

class UserRepository {

    private val TAG = "UserRepository"

    fun saveUserProfile(uid: String, email: String, displayName: String) {
        val userData = hashMapOf(
            "uid"         to uid,
            "email"       to email,
            "displayName" to displayName,
            "createdAt"   to System.currentTimeMillis()
        )
        FirebaseClient.getReference("users/$uid")
            .setValue(userData)
            .addOnSuccessListener { Log.d(TAG, "User profile saved: $email") }
            .addOnFailureListener { Log.e(TAG, "Error saving user: ${it.message}") }
    }

    // E-mail cím alapján megkeresi a usert (meghívóhoz kell)
    fun findUserByEmail(email: String, onResult: (UserModel?) -> Unit) {
        FirebaseClient.getReference("users")
            .orderByChild("email")
            .equalTo(email)
            .get()
            .addOnSuccessListener { snapshot ->
                val child = snapshot.children.firstOrNull()
                if (child != null) {
                    onResult(
                        UserModel(
                            uid         = child.child("uid").getValue(String::class.java) ?: "",
                            email       = child.child("email").getValue(String::class.java) ?: "",
                            displayName = child.child("displayName").getValue(String::class.java) ?: ""
                        )
                    )
                } else {
                    Log.w(TAG, "User not found: $email")
                    onResult(null)
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "findUserByEmail error: ${it.message}")
                onResult(null)
            }
    }

    // UID alapján lekéri a user adatait
    fun getUserProfile(uid: String, onResult: (UserModel?) -> Unit) {
        FirebaseClient.getReference("users/$uid")
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    onResult(
                        UserModel(
                            uid         = snapshot.child("uid").getValue(String::class.java) ?: uid,
                            email       = snapshot.child("email").getValue(String::class.java) ?: "",
                            displayName = snapshot.child("displayName").getValue(String::class.java) ?: ""
                        )
                    )
                } else {
                    onResult(null)
                }
            }
            .addOnFailureListener { onResult(null) }
    }
}