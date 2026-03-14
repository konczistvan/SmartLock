package com.example.smartlock.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.smartlock.R
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.ui.login.LoginActivity
import com.example.smartlock.ui.main.MainViewModel
import com.example.smartlock.ui.manageaccess.ManageAccessActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class HomeFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.entries.all { it.value }) {
            Toast.makeText(requireContext(), "Permissions required!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvEmail: TextView = view.findViewById(R.id.tvUserEmail)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val ivLockIcon: ImageView = view.findViewById(R.id.ivLockIcon)
        val spinnerLocks: Spinner = view.findViewById(R.id.spinnerLocks)
        val btnOpen: Button = view.findViewById(R.id.btnOpen)
        val btnClose: Button = view.findViewById(R.id.btnClose)
        val btnManage: Button = view.findViewById(R.id.btnManageAccess)
        val switchBle: SwitchMaterial = view.findViewById(R.id.switchAutoUnlock)
        val switchGeo: SwitchMaterial = view.findViewById(R.id.switchGeofence)
        val layoutGeo: View = view.findViewById(R.id.layoutGeoStatus)
        val tvGeoStatus: TextView = view.findViewById(R.id.tvGeofenceStatus)
        val btnSetLocation: Button = view.findViewById(R.id.btnSetLockLocation)
        val btnLogout: View = view.findViewById(R.id.btnLogout)

        tvEmail.text = FirebaseClient.currentUserEmail

        viewModel.statusText.observe(viewLifecycleOwner) { tvStatus.text = it }

        viewModel.lockStatus.observe(viewLifecycleOwner) { status ->
            val colorRes = if (status == "UNLOCKED") R.color.green else R.color.primary
            ivLockIcon.setColorFilter(resources.getColor(colorRes, null))
        }

        viewModel.myLocksLive.observe(viewLifecycleOwner) { locks ->
            if (locks.isEmpty()) {
                tvStatus.text = "No locks found. Add one with the + button."
                return@observe
            }
            val names = locks.map { it.name.ifEmpty { it.id } }
            spinnerLocks.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item, names
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinnerLocks.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.selectLock(locks[pos].id)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        viewModel.currentLockRole.observe(viewLifecycleOwner) { role ->
            btnManage.visibility = if (role == "owner") View.VISIBLE else View.GONE
        }

        btnOpen.setOnClickListener { viewModel.sendCommand("OPEN") }
        btnClose.setOnClickListener { viewModel.sendCommand("CLOSE") }

        btnManage.setOnClickListener {
            startActivity(Intent(requireContext(), ManageAccessActivity::class.java).apply {
                putExtra("lockId", viewModel.currentLockId)
            })
        }

        // Save current GPS as lock location
        btnSetLocation.setOnClickListener {
            if (hasLocationPermission()) {
                viewModel.saveCurrentLocationAsLock(requireContext())
            } else {
                requestLocationPermissions()
            }
        }

        switchGeo.setOnCheckedChangeListener { _, checked ->
            layoutGeo.visibility = if (checked) View.VISIBLE else View.GONE
            viewModel.isGeofenceEnabled = checked
            if (checked) {
                if (hasLocationPermission()) {
                    viewModel.fetchLockLocation()
                    viewModel.startLocationUpdates(requireContext())
                } else {
                    requestLocationPermissions()
                    switchGeo.isChecked = false
                }
            } else {
                viewModel.stopLocationUpdates()
            }
        }

        viewModel.geofenceStatus.observe(viewLifecycleOwner) { tvGeoStatus.text = it }

        switchBle.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (hasBluetoothPermissions()) {
                    viewModel.setAutoUnlockEnabled(true)
                    val lockId = viewModel.currentLockId
                    if (lockId.isNotEmpty()) {
                        FirebaseClient.getReference("locks/$lockId/bleProximityEnabled")
                            .setValue(true)
                    }
                } else {
                    requestBluetoothPermissions()
                    switchBle.isChecked = false
                }
            } else {
                viewModel.setAutoUnlockEnabled(false)
                val lockId = viewModel.currentLockId
                if (lockId.isNotEmpty()) {
                    FirebaseClient.getReference("locks/$lockId/bleProximityEnabled")
                        .setValue(false)
                }
            }
        }

        btnLogout.setOnClickListener {
            viewModel.authRepository.logout()
            startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestLocationPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        requestPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        } else {
            requestPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }
    }
}