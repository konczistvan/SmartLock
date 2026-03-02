package com.example.smartlock.data.repository

import android.util.Log
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.model.UserModel

class UserRepository {

    private val TAG = "UserRepository"

    fun saveUserProfile(uid: String, email: String, displayName: String) {
        val userData = hashMapOf(
            "uid" to uid,
            "email" to email,
            "displayName" to displayName,
            "createdAt" to System.currentTimeMillis()
        )
        FirebaseClient.getReference("users/$uid")
            .setValue(userData)
            .addOnSuccessListener { Log.d(TAG, "User profile saved: $email") }
            .addOnFailureListener { Log.e(TAG, "Error saving user: ${it.message}") }
    }

    fun findUserByEmail(email: String, onResult: (UserModel?) -> Unit) {
        Log.d(TAG, "Searching for user with email: $email")

        FirebaseClient.getReference("users")
            .orderByChild("email")
            .equalTo(email)
            .get()
            .addOnSuccessListener { snapshot ->
                Log.d(
                    TAG,
                    "Search result - exists: ${snapshot.exists()}, children: ${snapshot.childrenCount}"
                )
                Log.d(TAG, "Raw snapshot: ${snapshot.value}")

                val child = snapshot.children.firstOrNull()
                if (child != null) {
                    val user = UserModel(
                        uid = child.child("uid").getValue(String::class.java) ?: child.key ?: "",
                        email = child.child("email").getValue(String::class.java) ?: "",
                        displayName = child.child("displayName").getValue(String::class.java) ?: ""
                    )
                    Log.d(TAG, "User found: ${user.email} (${user.uid})")
                    onResult(user)
                } else {
                    Log.w(TAG, "User not found: $email")
                    onResult(null)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "findUserByEmail error: ${e.message}")
                onResult(null)
            }
    }

    fun getUserProfile(uid: String, onResult: (UserModel?) -> Unit) {
        FirebaseClient.getReference("users/$uid")
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    onResult(
                        UserModel(
                            uid = snapshot.child("uid").getValue(String::class.java) ?: uid,
                            email = snapshot.child("email").getValue(String::class.java) ?: "",
                            displayName = snapshot.child("displayName").getValue(String::class.java)
                                ?: ""
                        )
                    )
                } else {
                    onResult(null)
                }
            }
            .addOnFailureListener { onResult(null) }
    }
}