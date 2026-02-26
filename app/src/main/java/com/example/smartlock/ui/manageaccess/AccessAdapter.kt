package com.example.smartlock.ui.manageaccess

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartlock.R
import com.example.smartlock.data.model.PermissionModel
import java.text.SimpleDateFormat
import java.util.*

class AccessAdapter(
    private val onRevokeClick: (uid: String) -> Unit
) : RecyclerView.Adapter<AccessAdapter.ViewHolder>() {

    private val items = mutableListOf<PermissionModel>()

    fun submitList(list: List<PermissionModel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_access, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName:   TextView = itemView.findViewById(R.id.tvName)
        private val tvEmail:  TextView = itemView.findViewById(R.id.tvEmail)
        private val tvRole:   TextView = itemView.findViewById(R.id.tvRole)
        private val tvExpiry: TextView = itemView.findViewById(R.id.tvExpiry)
        private val btnRevoke: Button  = itemView.findViewById(R.id.btnRevoke)

        fun bind(p: PermissionModel) {
            tvName.text  = p.displayName.ifEmpty { "Unknown" }
            tvEmail.text = p.email
            tvRole.text  = p.role.uppercase()

            tvExpiry.text = if (p.expiresAt == null) {
                "Permanent"
            } else {
                "Expires: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(p.expiresAt))}"
            }

            if (p.role == "owner") {
                btnRevoke.visibility = View.GONE
            } else {
                btnRevoke.visibility = View.VISIBLE
                btnRevoke.setOnClickListener { onRevokeClick(p.uid) }
            }
        }
    }
}