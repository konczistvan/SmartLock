package com.example.smartlock.ui.devices

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.smartlock.R
import com.example.smartlock.ui.activation.ActivationActivity
import com.example.smartlock.ui.addlock.AddLockActivity
import com.example.smartlock.ui.main.MainViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton

class DevicesFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private val addLockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.reloadLocks()
        }
    }

    private val activationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.reloadLocks()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_devices, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tvTitle = view.findViewById<TextView>(R.id.tvDevicesTitle)
        val listView = view.findViewById<ListView>(R.id.lvDevices)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyDevices)
        val fabAdd = view.findViewById<FloatingActionButton>(R.id.fabAddDevice)

        viewModel.myLocksLive.observe(viewLifecycleOwner) { locks ->
            if (locks.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                listView.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                listView.visibility = View.VISIBLE

                val items = locks.map { lock ->
                    val name = lock.name.ifEmpty { lock.id }
                    val mac = lock.macAddress.ifEmpty { "No MAC" }
                    val status = if (lock.activated) "✅ Activated" else "⏳ Not activated"
                    "$name\n$mac — $status"
                }
                listView.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_2,
                    android.R.id.text1,
                    items
                )
            }
        }

        fabAdd.setOnClickListener {
            // Show choice dialog: Activate new lock vs Add manually
            val options = arrayOf("🔗 Activate New Lock (BLE)", "✏️ Add Lock Manually")
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Add Lock")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> activationLauncher.launch(
                            Intent(requireContext(), ActivationActivity::class.java)
                        )
                        1 -> addLockLauncher.launch(
                            Intent(requireContext(), AddLockActivity::class.java)
                        )
                    }
                }
                .show()
        }
    }
}