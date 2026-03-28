package io.nekohasekai.sagernet.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity

class ServerBottomSheetAdapter(
    private val profiles: List<ProxyEntity>,
    private val selectedProfileId: Long,
    private val onProfileSelected: (ProxyEntity) -> Unit
) : RecyclerView.Adapter<ServerBottomSheetAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvServerFlag: TextView = view.findViewById(R.id.tvServerFlag)
        val tvServerName: TextView = view.findViewById(R.id.tvServerName)
        val tvServerStatus: TextView = view.findViewById(R.id.tvServerStatus)
        val ivServerCheck: ImageView = view.findViewById(R.id.ivServerCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server_bottom_sheet, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = profiles[position]

        // LvovFlow: Parse flag if available, else default to Globe
        val defaultName = profile.displayName()
        var flag = "🌐"
        var cleanName = defaultName

        // Rudimentary flag extraction (if name starts with emoji)
        if (defaultName.isNotEmpty() && Character.isSurrogate(defaultName[0])) {
            val spaceIdx = defaultName.indexOf(' ')
            if (spaceIdx > 0 && spaceIdx <= 4) {
                flag = defaultName.substring(0, spaceIdx)
                cleanName = defaultName.substring(spaceIdx).trim()
            }
        } else if (defaultName.contains("RU") || defaultName.contains("Russia") || defaultName.contains("Россия")) {
            flag = "🇷🇺"
        } else if (defaultName.contains("DE") || defaultName.contains("Germany") || defaultName.contains("Германия") || defaultName.contains("Frankfurt")) {
            flag = "🇩🇪"
        } else if (defaultName.contains("NL") || defaultName.contains("Netherlands") || defaultName.contains("Амстердам")) {
            flag = "🇳🇱"
        } else if (defaultName.contains("US") || defaultName.contains("USA") || defaultName.contains("США")) {
            flag = "🇺🇸"
        } else if (defaultName.contains("FI") || defaultName.contains("Finland") || defaultName.contains("Финляндия")) {
            flag = "🇫🇮"
        }

        holder.tvServerFlag.text = flag
        holder.tvServerName.text = cleanName

        if (profile.id == selectedProfileId) {
            holder.ivServerCheck.visibility = View.VISIBLE
            holder.tvServerStatus.text = "Выбранный сервер"
            holder.tvServerStatus.setTextColor(0xFF25C9EF.toInt())
        } else {
            holder.ivServerCheck.visibility = View.INVISIBLE
            holder.tvServerStatus.text = "Доступен для подключения"
            holder.tvServerStatus.setTextColor(0xFF8B9BB4.toInt())
        }

        holder.itemView.setOnClickListener {
            onProfileSelected(profile)
        }
    }

    override fun getItemCount() = profiles.size
}
