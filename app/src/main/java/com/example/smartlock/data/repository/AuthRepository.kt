package com.example.smartlock.data.repository

import com.example.smartlock.api.FirebaseClient

class AuthRepository {


    fun login(
        email: String,
        password: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        FirebaseClient.auth
            .signInWithEmailAndPassword(email, password)
            .addOnSuccessListener { result ->

                val user = result.user
                if (user != null) {
                    val ref = FirebaseClient.getReference("users/${user.uid}")
                    ref.get().addOnSuccessListener { snap ->
                        if (!snap.exists()) {
                            UserRepository().saveUserProfile(
                                uid = user.uid,
                                email = user.email ?: email,
                                displayName = user.displayName ?: email.substringBefore("@")
                            )
                        }
                    }
                }

                onSuccess()
            }
            .addOnFailureListener { e ->
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
                UserRepository().saveUserProfile(uid, email, displayName)
                onSuccess()
            }
            .addOnFailureListener { e ->
                onFailure(e.message ?: "Unknown error")
            }
    }

    fun logout() {
        FirebaseClient.auth.signOut()
    }

    fun isLoggedIn(): Boolean {
        return FirebaseClient.auth.currentUser != null
    }
}