package com.example.smartlock.ui.main

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.smartlock.R
import com.example.smartlock.ui.devices.DevicesFragment
import com.example.smartlock.ui.home.HomeFragment
import com.example.smartlock.ui.logs.LogsFragment
import com.example.smartlock.ui.settings.SettingsFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter: BluetoothAdapter? = bluetoothManager.adapter
        viewModel.init(applicationContext, btAdapter)
        viewModel.loginAndLoadLocks()

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment()); true
                }

                R.id.nav_devices -> {
                    loadFragment(DevicesFragment()); true
                }

                R.id.nav_logs -> {
                    loadFragment(LogsFragment()); true
                }

                R.id.nav_settings -> {
                    loadFragment(SettingsFragment()); true
                }

                else -> false
            }
        }

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopLocationUpdates()
    }
}