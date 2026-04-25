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

    // Engedélykérő ablak eredményeinek kezelése
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!permissions.entries.all { it.value }) {
            Toast.makeText(requireContext(), "A megfelelő működéshez minden engedély szükséges!", Toast.LENGTH_LONG).show()
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

        // Ezt az egy kapcsolót használjuk a teljes Hibrid (BLE + GPS) mód vezérlésére
        val switchHybrid: SwitchMaterial = view.findViewById(R.id.switchAutoUnlock)
        val layoutGeo: View = view.findViewById(R.id.layoutGeoStatus)
        val tvGeoStatus: TextView = view.findViewById(R.id.tvGeofenceStatus)
        val btnSetLocation: Button = view.findViewById(R.id.btnSetLockLocation)
        val btnLogout: View = view.findViewById(R.id.btnLogout)

        // Ha az XML-ben benne maradt a régi Geofence kapcsoló, szoftveresen eltüntetjük, hogy ne zavarjon be
        val switchGeo: SwitchMaterial? = view.findViewById(R.id.switchGeofence)
        switchGeo?.visibility = View.GONE
        (switchGeo?.parent as? View)?.visibility = View.GONE

        tvEmail.text = FirebaseClient.currentUserEmail

        // --- ALAP UI FIGYELŐK ---
        viewModel.statusText.observe(viewLifecycleOwner) { tvStatus.text = it }

        viewModel.lockStatus.observe(viewLifecycleOwner) { status ->
            val colorRes = if (status == "UNLOCKED") R.color.green else R.color.primary
            ivLockIcon.setColorFilter(resources.getColor(colorRes, null))
        }

        viewModel.myLocksLive.observe(viewLifecycleOwner) { locks ->
            if (locks.isEmpty()) {
                tvStatus.text = "Nincs zár. Adj hozzá a + gombbal."
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

        // --- GOMBOK ---
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

        // --- UI FRISSÍTÉS A SZENZORFÚZIÓ (VALÓDI ÁLLAPOT) ALAPJÁN ---
        viewModel.unifiedStateText.observe(viewLifecycleOwner) { unifiedText ->
            // Fentre, az email cím alá kiírjuk a gyönyörű, pontos állapotot
            val email = FirebaseClient.auth.currentUser?.email ?: ""
            tvEmail.text = "$email\n$unifiedText"

            // Lentre a GPS kártyára is rátesszük
            val currentDist = viewModel.geofenceStatusText.value ?: "GPS: -"
            tvGeoStatus.text = "$unifiedText\n$currentDist"
        }

        viewModel.geofenceStatusText.observe(viewLifecycleOwner) { distanceText ->
            // Ha csak a méterek változnak, frissítjük a lenti kártyát
            val unifiedText = viewModel.unifiedStateText.value ?: ""
            tvGeoStatus.text = "$unifiedText\n$distanceText"
        }

        // Toast üzenetek megjelenítése a ViewModelből
        viewModel.toastMessage.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrEmpty()) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                viewModel.onToastShown()
            }
        }

        // --- HIBRID MÓD KAPCSOLÓ (GPS + BLE) ---
        switchHybrid.setOnCheckedChangeListener { _, checked ->
            layoutGeo.visibility = if (checked) View.VISIBLE else View.GONE

            if (checked) {
                if (hasBluetoothPermissions() && hasLocationPermission()) {
                    viewModel.setHybridMode(true)
                    viewModel.fetchLockLocation()
                    viewModel.startLocationUpdates(requireContext())
                    Toast.makeText(requireContext(), "Hibrid Auto-Unlock Élesítve!", Toast.LENGTH_SHORT).show()
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

    // --- JOGOSULTSÁG KEZELÉS ---
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