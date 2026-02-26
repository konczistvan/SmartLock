package com.example.smartlock.ui.register

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.smartlock.R
import com.example.smartlock.data.repository.AuthRepository
import com.example.smartlock.ui.main.MainActivity

class RegisterActivity : AppCompatActivity() {

    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        val etName      = findViewById<EditText>(R.id.etName)
        val etEmail     = findViewById<EditText>(R.id.etEmail)
        val etPassword  = findViewById<EditText>(R.id.etPassword)
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val btnBack     = findViewById<TextView>(R.id.btnBackToLogin)

        btnRegister.setOnClickListener {
            val name     = etName.text.toString().trim()
            val email    = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Fill in all fields!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.length < 6) {
                Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressBar.visibility = View.VISIBLE
            btnRegister.isEnabled  = false

            authRepository.register(
                email       = email,
                password    = password,
                displayName = name,
                onSuccess = {
                    progressBar.visibility = View.GONE
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                },
                onFailure = { error ->
                    progressBar.visibility = View.GONE
                    btnRegister.isEnabled  = true
                    Toast.makeText(this, "Registration failed: $error", Toast.LENGTH_LONG).show()
                }
            )
        }

        btnBack.setOnClickListener { finish() }
    }
}