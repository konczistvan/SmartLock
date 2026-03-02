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
                    "$name\n$mac"
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
            addLockLauncher.launch(Intent(requireContext(), AddLockActivity::class.java))
        }
    }
}