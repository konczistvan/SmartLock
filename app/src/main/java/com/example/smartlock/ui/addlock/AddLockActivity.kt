package com.example.smartlock.ui.addlock

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.smartlock.R
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.data.repository.LockRepository
import com.example.smartlock.data.repository.PermissionRepository

class AddLockActivity : AppCompatActivity() {

    private val lockRepository       = LockRepository()
    private val permissionRepository = PermissionRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_lock)

        supportActionBar?.title = "Add New Lock"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val etLockName  = findViewById<EditText>(R.id.etLockName)
        val etLockId    = findViewById<EditText>(R.id.etLockId)
        val etMac       = findViewById<EditText>(R.id.etMacAddress)
        val etLat       = findViewById<EditText>(R.id.etLatitude)
        val etLng       = findViewById<EditText>(R.id.etLongitude)
        val btnAdd      = findViewById<Button>(R.id.btnAddLock)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvHint      = findViewById<TextView>(R.id.tvHint)

        tvHint.text = "Lock ID = the LOCK_ID defined in your ESP32 code\n" +
                "BLE MAC = 12-character MAC without colons (e.g. B8F862E0BCBD)\n" +
                "Lat/Lng = GPS coordinates of the lock (for geofence)"

        btnAdd.setOnClickListener {
            val lockName   = etLockName.text.toString().trim()
            val lockId     = etLockId.text.toString().trim().uppercase()
            val macAddress = etMac.text.toString().trim().uppercase().replace(":", "")
            val latText    = etLat.text.toString().trim()
            val lngText    = etLng.text.toString().trim()

            if (lockName.isEmpty() || lockId.isEmpty()) {
                Toast.makeText(this, "Lock name and Lock ID are required!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val lat = if (latText.isNotEmpty()) latText.toDoubleOrNull() else 0.0
            val lng = if (lngText.isNotEmpty()) lngText.toDoubleOrNull() else 0.0

            if ((latText.isNotEmpty() && lat == null) || (lngText.isNotEmpty() && lng == null)) {
                Toast.makeText(this, "Invalid coordinates!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val uid = FirebaseClient.auth.currentUser?.uid
            if (uid == null) {
                Toast.makeText(this, "Not logged in!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            progressBar.visibility = View.VISIBLE
            btnAdd.isEnabled       = false

            lockRepository.addLock(
                lockId      = lockId,
                lockName    = lockName,
                macAddress  = macAddress,
                addedByUid  = uid,
                latitude    = lat ?: 0.0,
                longitude   = lng ?: 0.0,
                onSuccess   = {
                    permissionRepository.grantAccess(
                        lockId       = lockId,
                        targetUid    = uid,
                        role         = "owner",
                        expiresAt    = null,
                        grantedByUid = uid,
                        onSuccess    = {
                            progressBar.visibility = View.GONE
                            Toast.makeText(this, "Lock added!", Toast.LENGTH_SHORT).show()
                            setResult(RESULT_OK)
                            finish()
                        },
                        onFailure    = { err ->
                            progressBar.visibility = View.GONE
                            btnAdd.isEnabled       = true
                            Toast.makeText(this, "Permission error: $err", Toast.LENGTH_LONG).show()
                        }
                    )
                },
                onFailure   = { err ->
                    progressBar.visibility = View.GONE
                    btnAdd.isEnabled       = true
                    Toast.makeText(this, "Error: $err", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}