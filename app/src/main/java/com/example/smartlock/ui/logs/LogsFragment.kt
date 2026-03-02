package com.example.smartlock.ui.logs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.smartlock.R
import com.example.smartlock.data.repository.LogRepository
import com.example.smartlock.ui.main.MainViewModel

class LogsFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private val logRepository = LogRepository()

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_logs, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val listView = view.findViewById<ListView>(R.id.lvLogs)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyLogs)

        loadLogs(listView, tvEmpty)

        viewModel.myLocksLive.observe(viewLifecycleOwner) {
            loadLogs(listView, tvEmpty)
        }
    }

    private fun loadLogs(listView: ListView, tvEmpty: TextView) {
        val lockId = viewModel.currentLockId
        if (lockId.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            listView.visibility = View.GONE
            tvEmpty.text = "No lock selected"
            return
        }

        logRepository.getLogs(lockId) { logs ->
            if (!isAdded) return@getLogs

            if (logs.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                listView.visibility = View.GONE
                tvEmpty.text = "No logs yet"
            } else {
                tvEmpty.visibility = View.GONE
                listView.visibility = View.VISIBLE

                val items = logs.map { log ->
                    "${log.formatted_date}\n${log.method} — ${log.user_email}"
                }
                listView.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_list_item_1,
                    items
                )
            }
        }
    }
}