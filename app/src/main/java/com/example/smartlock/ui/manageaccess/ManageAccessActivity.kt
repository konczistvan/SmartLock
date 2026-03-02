package com.example.smartlock.ui.manageaccess

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.smartlock.R
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.model.PermissionModel
import com.example.smartlock.data.repository.PermissionRepository
import com.example.smartlock.data.repository.UserRepository

class ManageAccessActivity : AppCompatActivity() {

    private val permissionRepository = PermissionRepository()
    private val userRepository = UserRepository()
    private lateinit var adapter: AccessAdapter

    private var lockId = ""
    private var lockName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_access)

        lockId = intent.getStringExtra("lockId") ?: ""
        lockName = intent.getStringExtra("lockName") ?: lockId

        supportActionBar?.title = "Manage: $lockName"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val btnGrant = findViewById<Button>(R.id.btnGrantAccess)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        adapter = AccessAdapter { uid -> showRevokeDialog(uid) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnGrant.setOnClickListener { showGrantDialog() }

        loadList(progressBar)
    }

    private fun loadList(progressBar: ProgressBar) {
        progressBar.visibility = View.VISIBLE

        permissionRepository.getAccessList(lockId) { permissions ->
            if (permissions.isEmpty()) {
                progressBar.visibility = View.GONE
                return@getAccessList
            }

            val result = mutableListOf<PermissionModel>()
            var fetched = 0

            for (perm in permissions) {
                userRepository.getUserProfile(perm.uid) { user ->
                    result.add(
                        perm.copy(
                            email = user?.email ?: "Unknown",
                            displayName = user?.displayName ?: "Unknown"
                        )
                    )
                    fetched++
                    if (fetched == permissions.size) {
                        progressBar.visibility = View.GONE
                        adapter.submitList(result.sortedByDescending { it.role == "owner" })
                    }
                }
            }
        }
    }

    private fun showGrantDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_grant_access, null)
        val etEmail = dialogView.findViewById<EditText>(R.id.etEmail)
        val spinnerDur = dialogView.findViewById<Spinner>(R.id.spinnerDuration)
        val durations = arrayOf("Permanent", "1 hour", "24 hours", "7 days", "30 days")

        spinnerDur.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, durations)

        AlertDialog.Builder(this)
            .setTitle("Grant Access")
            .setView(dialogView)
            .setPositiveButton("Grant") { _, _ ->
                val email = etEmail.text.toString().trim()
                if (email.isEmpty()) {
                    Toast.makeText(this, "Enter an email!", Toast.LENGTH_SHORT)
                        .show(); return@setPositiveButton
                }

                val expiresAt: Long? = when (spinnerDur.selectedItemPosition) {
                    0 -> null
                    1 -> System.currentTimeMillis() + 3_600_000L
                    2 -> System.currentTimeMillis() + 86_400_000L
                    3 -> System.currentTimeMillis() + 7 * 86_400_000L
                    4 -> System.currentTimeMillis() + 30 * 86_400_000L
                    else -> null
                }
                grantByEmail(email, expiresAt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun grantByEmail(email: String, expiresAt: Long?) {
        val myUid = FirebaseClient.auth.currentUser?.uid ?: return

        userRepository.findUserByEmail(email) { user ->
            if (user == null) {
                Toast.makeText(this, "User not found: $email", Toast.LENGTH_LONG).show()
                return@findUserByEmail
            }

            permissionRepository.grantAccess(
                lockId = lockId,
                targetUid = user.uid,
                role = "guest",
                expiresAt = expiresAt,
                grantedByUid = myUid,
                onSuccess = {
                    Toast.makeText(
                        this,
                        "Access granted to ${user.displayName}",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadList(findViewById(R.id.progressBar))
                },
                onFailure = { Toast.makeText(this, "Error: $it", Toast.LENGTH_LONG).show() }
            )
        }
    }

    private fun showRevokeDialog(uid: String) {
        AlertDialog.Builder(this)
            .setTitle("Revoke Access")
            .setMessage("Remove this user's access?")
            .setPositiveButton("Remove") { _, _ ->
                permissionRepository.revokeAccess(
                    lockId = lockId,
                    targetUid = uid,
                    onSuccess = {
                        Toast.makeText(this, "Access removed", Toast.LENGTH_SHORT).show()
                        loadList(findViewById(R.id.progressBar))
                    },
                    onFailure = { Toast.makeText(this, "Error: $it", Toast.LENGTH_LONG).show() }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }
}