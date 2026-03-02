package com.example.smartlock.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.smartlock.R
import com.example.smartlock.api.FirebaseClient
import com.example.smartlock.ui.login.LoginActivity
import com.example.smartlock.ui.main.MainViewModel

class SettingsFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences(
            "smartlock_prefs",
            android.content.Context.MODE_PRIVATE
        )

        view.findViewById<TextView>(R.id.tvUserEmail).text =
            FirebaseClient.auth.currentUser?.email ?: ""

        val tvBle = view.findViewById<TextView>(R.id.tvBleValue)
        val seekBle = view.findViewById<SeekBar>(R.id.seekBarBle)

        val savedBle = prefs.getInt("ble_rssi", -80)
        seekBle.progress = savedBle + 100                      // map to 0..60
        tvBle.text = "$savedBle dBm"

        seekBle.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val rssi = p - 100
                tvBle.text = "$rssi dBm"
                if (fromUser) {
                    prefs.edit().putInt("ble_rssi", rssi).apply()
                    viewModel.setBleRssiThreshold(rssi)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val tvGeo = view.findViewById<TextView>(R.id.tvGeoValue)
        val seekGeo = view.findViewById<SeekBar>(R.id.seekBarGeo)

        val savedGeo = prefs.getInt("geo_radius", 50)
        seekGeo.progress = (savedGeo - 3).coerceIn(0, 497)
        tvGeo.text = "$savedGeo m"

        seekGeo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val radius = p + 3
                tvGeo.text = "$radius m"
                if (fromUser) {
                    prefs.edit().putInt("geo_radius", radius).apply()
                    viewModel.setGeofenceRadius(radius.toFloat())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        view.findViewById<android.widget.Button>(R.id.btnLogout).setOnClickListener {
            viewModel.authRepository.logout()
            startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }
}
