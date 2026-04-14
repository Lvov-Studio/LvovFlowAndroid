package io.nekohasekai.sagernet.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * LvovFlow — Notifications Overlay (Concept v2 Design)
 * Fullscreen DialogFragment matching vpn_concept.html notifications overlay:
 * - Semi-transparent backdrop
 * - Expandable notification cards with unread dots
 * - Delete with animation
 * - Empty state
 */
class NotificationsFragment : DialogFragment() {

    private lateinit var adapter: NotifAdapter
    private val notifications = mutableListOf<NotifItem>()
    private var lastReadId = 0

    data class NotifItem(
        val id: Int,
        val title: String,
        val message: String,
        val date: String,
        var isRead: Boolean = false,
        var isExpanded: Boolean = false
    )

    override fun getTheme(): Int = android.R.style.Theme_Black_NoTitleBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notifications, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
        lastReadId = prefs.getInt("last_notif_id", 0)

        // Parse notifications from SharedPreferences
        val jsonStr = prefs.getString("notifications_json", "[]") ?: "[]"
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optInt("id", 0)
                notifications.add(
                    NotifItem(
                        id = id,
                        title = obj.optString("title", ""),
                        message = obj.optString("message", ""),
                        date = obj.optString("date", ""),
                        isRead = id <= lastReadId
                    )
                )
            }
        } catch (_: Exception) { }

        // Close button
        view.findViewById<View>(R.id.btn_close_notifications).setOnClickListener {
            dismiss()
        }

        // RecyclerView
        val rv = view.findViewById<RecyclerView>(R.id.rv_notifications)
        val emptyState = view.findViewById<View>(R.id.empty_state)
        adapter = NotifAdapter(notifications, emptyState, rv)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // Check empty
        updateEmptyState(view)

        // Mark all as read
        var maxId = lastReadId
        for (n in notifications) {
            if (n.id > maxId) maxId = n.id
        }
        if (maxId > lastReadId) {
            prefs.edit().putInt("last_notif_id", maxId).apply()
        }
    }

    private fun updateEmptyState(view: View) {
        val emptyState = view.findViewById<View>(R.id.empty_state)
        val rv = view.findViewById<RecyclerView>(R.id.rv_notifications)
        if (notifications.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }
    }

    // ── Adapter ──────────────────────────────────────────────
    inner class NotifAdapter(
        private val items: MutableList<NotifItem>,
        private val emptyState: View,
        private val recyclerView: RecyclerView
    ) : RecyclerView.Adapter<NotifAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val dot: View = itemView.findViewById(R.id.notif_dot)
            val title: TextView = itemView.findViewById(R.id.tv_notif_title)
            val time: TextView = itemView.findViewById(R.id.tv_notif_time)
            val body: TextView = itemView.findViewById(R.id.tv_notif_body)
            val actionsRow: LinearLayout = itemView.findViewById(R.id.actions_row)
            val deleteBtn: LinearLayout = itemView.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]

            holder.title.text = item.title
            holder.time.text = item.date
            holder.body.text = item.message

            // Unread dot
            holder.dot.visibility = if (!item.isRead) View.VISIBLE else View.GONE

            // Expand state
            holder.body.maxLines = if (item.isExpanded) Integer.MAX_VALUE else 2
            holder.actionsRow.visibility = if (item.isExpanded) View.VISIBLE else View.GONE

            // Read state alpha
            holder.itemView.alpha = if (item.isRead && !item.isExpanded) 0.6f else 1f

            // Click to expand/collapse
            holder.itemView.setOnClickListener {
                val wasExpanded = item.isExpanded
                // Collapse all others
                for (i in items.indices) {
                    items[i].isExpanded = false
                }
                if (!wasExpanded) {
                    item.isExpanded = true
                    item.isRead = true
                    holder.dot.visibility = View.GONE
                }
                notifyDataSetChanged()
            }

            // Delete with animation
            holder.deleteBtn.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener

                // Animate out
                val scaleX = ObjectAnimator.ofFloat(holder.itemView, "scaleX", 1f, 0.9f)
                val scaleY = ObjectAnimator.ofFloat(holder.itemView, "scaleY", 1f, 0.9f)
                val alpha = ObjectAnimator.ofFloat(holder.itemView, "alpha", 1f, 0f)
                AnimatorSet().apply {
                    playTogether(scaleX, scaleY, alpha)
                    duration = 300
                    start()
                }

                holder.itemView.postDelayed({
                    if (pos < items.size) {
                        items.removeAt(pos)
                        notifyItemRemoved(pos)
                        notifyItemRangeChanged(pos, items.size)

                        // Check if empty
                        if (items.isEmpty()) {
                            emptyState.visibility = View.VISIBLE
                            recyclerView.visibility = View.GONE
                        }
                    }
                }, 300)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
