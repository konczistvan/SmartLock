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
            Toast.makeText(requireContext(), "All permissions are required for the app to work properly!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvEmail: TextView = view.findViewById(R.id.tvUserEmail)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val ivLockIcon: ImageView = view.findViewById(R.id.ivLockIcon)
        val btnOpen: Button = view.findViewById(R.id.btnOpen)
        val btnClose: Button = view.findViewById(R.id.btnClose)
        val btnManage: Button = view.findViewById(R.id.btnManageAccess)
        val switchHybrid: SwitchMaterial = view.findViewById(R.id.switchAutoUnlock)
        val layoutGeo: View = view.findViewById(R.id.layoutGeoStatus)
        val tvGeoStatus: TextView = view.findViewById(R.id.tvGeofenceStatus)
        val btnSetLocation: Button = view.findViewById(R.id.btnSetLockLocation)
        val btnLogout: View = view.findViewById(R.id.btnLogout)
        val layoutLockSelector: View = view.findViewById(R.id.layoutLockSelector)
        // The separate geofence switch is not used — the Auto-Unlock switch is the master gate.
        val switchGeo: SwitchMaterial? = view.findViewById(R.id.switchGeofence)
        switchGeo?.visibility = View.GONE
        (switchGeo?.parent as? View)?.visibility = View.GONE

        tvEmail.text = FirebaseClient.currentUserEmail

        viewModel.statusText.observe(viewLifecycleOwner) { tvStatus.text = it }

        viewModel.lockStatus.observe(viewLifecycleOwner) { status ->
            val colorRes = if (status == "UNLOCKED") R.color.green else R.color.primary
            ivLockIcon.setColorFilter(resources.getColor(colorRes, null))
        }

        viewModel.myLocksLive.observe(viewLifecycleOwner) { locks ->
            if (locks.isEmpty()) {
                tvStatus.text = "No locks. Add one with the + button."
                layoutLockSelector.setOnClickListener(null)
                return@observe
            }

            layoutLockSelector.setOnClickListener {
                showLockSelectorBottomSheet(locks)
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

        btnSetLocation.setOnClickListener {
            if (hasLocationPermission()) {
                viewModel.saveCurrentLocationAsLock(requireContext())
            } else {
                requestAllPermissions()
            }
        }

        // The geofence card (visible only when hybrid is on) shows the unified system-state line.
        viewModel.unifiedStateText.observe(viewLifecycleOwner) { stateText ->
            tvGeoStatus.text = stateText
        }

        viewModel.toastMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                viewModel.onToastShown()
            }
        }

        switchHybrid.setOnCheckedChangeListener(null) // Remove the listener first, just to be safe
        switchHybrid.isChecked = viewModel.isHybridModeEnabled
        layoutGeo.visibility = if (viewModel.isHybridModeEnabled) View.VISIBLE else View.GONE

        switchHybrid.setOnCheckedChangeListener { _, checked ->
            layoutGeo.visibility = if (checked) View.VISIBLE else View.GONE

            if (checked) {
                if (hasBluetoothPermissions() && hasLocationPermission()) {
                    viewModel.setHybridMode(true)
                    viewModel.fetchLockLocation()
                    viewModel.startLocationUpdates(requireContext())
                    Toast.makeText(requireContext(), "Auto-Unlock enabled — it activates when you're near home", Toast.LENGTH_SHORT).show()
                } else {
                    requestAllPermissions()
                    switchHybrid.isChecked = false
                }
            } else {
                viewModel.setHybridMode(false)
                viewModel.stopLocationUpdates()
            }
        }

        btnLogout.setOnClickListener {
            viewModel.authRepository.logout()
            startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

    private fun showLockSelectorBottomSheet(locks: List<com.example.smartlock.data.model.LockModel>) {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_bottom_sheet_locks, null)

        val container = view.findViewById<LinearLayout>(R.id.locksContainer)

        locks.forEach { lock ->
            val itemView = layoutInflater.inflate(R.layout.item_bottom_sheet_lock, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvLockName)
            val ivCheck = itemView.findViewById<ImageView>(R.id.ivCheck)

            tvName.text = lock.name.ifEmpty { lock.id }

            if (lock.id == viewModel.currentLockId) {
                tvName.setTypeface(null, android.graphics.Typeface.BOLD)
                tvName.setTextColor(resources.getColor(R.color.primary, null))
                ivCheck.visibility = View.VISIBLE
            } else {
                tvName.setTypeface(null, android.graphics.Typeface.NORMAL)
                tvName.setTextColor(resources.getColor(R.color.text_primary, null))
                ivCheck.visibility = View.GONE
            }

            itemView.setOnClickListener {
                viewModel.selectLock(lock.id)
                bottomSheetDialog.dismiss()
            }

            container.addView(itemView)
        }

        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
    }

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        requestPermissionLauncher.launch(perms.toTypedArray())
    }
}