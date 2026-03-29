package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.nekohasekai.sagernet.R
import org.json.JSONArray

class NotificationsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Remove default ActionBar since we have a custom header
        supportActionBar?.hide()
        setContentView(R.layout.activity_notifications)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.notifications_container)
        val prefs = getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
        
        val jsonStr = prefs.getString("notifications_json", "[]") ?: "[]"
        val notifications = JSONArray(jsonStr)

        if (notifications.length() == 0) {
            val emptyText = TextView(this).apply {
                text = "Новостей пока нет 📭"
                setTextColor(Color.parseColor("#8B9BB4"))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(32), 0, 0)
            }
            container.addView(emptyText)
            return
        }

        var maxId = prefs.getInt("last_notif_id", 0)
        
        for (i in 0 until notifications.length()) {
            val n = notifications.getJSONObject(i)
            val id = n.optInt("id", 0)
            if (id > maxId) maxId = id

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#0F172A"))
                    cornerRadius = dpToPx(12).toFloat()
                    setStroke(dpToPx(1), Color.parseColor("#1E293B"))
                }
                setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
                
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, dpToPx(12))
                layoutParams = params
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val titleView = TextView(this).apply {
                text = n.optString("title", "")
                setTextColor(Color.parseColor("#22D3EE")) // Neon cyan
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val dateView = TextView(this).apply {
                text = n.optString("date", "")
                setTextColor(Color.parseColor("#64748B"))
                textSize = 11f
            }

            topRow.addView(titleView)
            topRow.addView(dateView)

            val messageView = TextView(this).apply {
                text = n.optString("message", "")
                setTextColor(Color.parseColor("#F1F5F9"))
                textSize = 13f
                setPadding(0, dpToPx(8), 0, 0)
            }

            card.addView(topRow)
            card.addView(messageView)
            
            container.addView(card)
        }

        // Mark all as read
        if (maxId > 0) {
            prefs.edit().putInt("last_notif_id", maxId).apply()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
