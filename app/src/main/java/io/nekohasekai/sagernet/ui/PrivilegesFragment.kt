package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * LvovFlow — Privileges Screen Fragment
 * Combines referral program, promo codes, and invite history.
 * Backed by /api/app/privileges.php
 */
class PrivilegesFragment : ToolbarFragment() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_privileges, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.navigationIcon = null
        toolbar.title = null

        val prefs = requireContext().getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
        val token = prefs.getString("session_token", "") ?: ""
        val email = prefs.getString("user_email", "") ?: ""

        // ── Load data from server ──
        loadPrivilegesInfo(view, token)

        // ── Fallback referral link from email hash ──
        val fallbackRef = if (email.isNotBlank()) {
            val hash = email.hashCode().and(0x7FFFFFFF).toString(36).take(6)
            "https://lvovflow.com/r/ref_$hash"
        } else "https://lvovflow.com"
        view.findViewById<TextView>(R.id.tv_referral_link).text = fallbackRef

        // ── Copy link ──
        view.findViewById<Button>(R.id.btn_copy_link).setOnClickListener {
            val link = view.findViewById<TextView>(R.id.tv_referral_link).text.toString()
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LvovFlow Referral", link))
            Toast.makeText(requireContext(), "Ссылка скопирована ✓", Toast.LENGTH_SHORT).show()
        }

        // ── Share button ──
        view.findViewById<Button>(R.id.btn_share_referral).setOnClickListener {
            val link = view.findViewById<TextView>(R.id.tv_referral_link).text.toString()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "LvovFlow — YouTube без тормозов")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Попробуй LvovFlow — интернет без блокировок и лагов! " +
                    "Мы оба получим +7 дней бесплатно 🚀\n$link"
                )
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться"))
        }

        // ── QR code button ──
        view.findViewById<Button>(R.id.btn_show_qr).setOnClickListener {
            val link = view.findViewById<TextView>(R.id.tv_referral_link).text.toString()
            showQrDialog(link)
        }

        // ── Promo code ──
        val etPromo = view.findViewById<EditText>(R.id.et_promo_code)
        view.findViewById<Button>(R.id.btn_apply_promo).setOnClickListener {
            val code = etPromo.text.toString().trim().uppercase()
            if (code.isBlank()) {
                Toast.makeText(requireContext(), "Введите промокод", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            applyPromoCode(view, token, code)
        }
    }

    private fun loadPrivilegesInfo(view: View, token: String) {
        if (token.isBlank()) return

        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val url = URL("https://lvovflow.com/api/app/privileges.php")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-App-Client", "LvovFlow-Android")
                    conn.setRequestProperty("X-Session-Token", token)
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.doOutput = true
                    conn.outputStream.bufferedWriter().use {
                        it.write(JSONObject().put("action", "get_info").put("token", token).toString())
                    }
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    JSONObject(body)
                }

                if (response.optBoolean("ok", false)) {
                    // Update referral link
                    val refUrl = response.optString("referral_url", "")
                    if (refUrl.isNotBlank()) {
                        view.findViewById<TextView>(R.id.tv_referral_link).text = refUrl
                    }

                    // Update earned days
                    val earnedDays = response.optInt("earned_days", 0)
                    view.findViewById<TextView>(R.id.tv_earned_days).text = "$earnedDays дней"

                    // Save locally
                    requireContext().getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
                        .edit()
                        .putString("referral_url", refUrl)
                        .putInt("referral_earned_days", earnedDays)
                        .apply()

                    // Populate invite history
                    val invites = response.optJSONArray("invites")
                    val emptyLabel = view.findViewById<TextView>(R.id.tv_invite_empty)
                    val listContainer = view.findViewById<LinearLayout>(R.id.invite_list_container)

                    if (invites != null && invites.length() > 0) {
                        emptyLabel.visibility = View.GONE
                        listContainer.visibility = View.VISIBLE
                        listContainer.removeAllViews()

                        for (i in 0 until invites.length()) {
                            val invite = invites.getJSONObject(i)
                            val row = TextView(requireContext()).apply {
                                val status = if (invite.optString("status") == "credited") "✓" else "⏳"
                                text = "${invite.optString("email")} • ${invite.optString("date")} • +${invite.optInt("days")} дней $status"
                                setTextColor(0xFFCBD5E1.toInt())
                                textSize = 13f
                                setPadding(0, 12, 0, 12)
                            }
                            listContainer.addView(row)

                            // Divider
                            if (i < invites.length() - 1) {
                                val divider = View(requireContext()).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                                    )
                                    setBackgroundColor(0xFF1A2C46.toInt())
                                }
                                listContainer.addView(divider)
                            }
                        }
                    } else {
                        emptyLabel.visibility = View.VISIBLE
                        listContainer.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                // Silently fail — UI shows cached/default data
            }
        }
    }

    private fun applyPromoCode(view: View, token: String, code: String) {
        val btnApply = view.findViewById<Button>(R.id.btn_apply_promo)
        btnApply.isEnabled = false
        btnApply.text = "Проверяем…"

        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    val url = URL("https://lvovflow.com/api/app/privileges.php")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("X-App-Client", "LvovFlow-Android")
                    conn.setRequestProperty("X-Session-Token", token)
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.doOutput = true
                    conn.outputStream.bufferedWriter().use {
                        it.write(JSONObject()
                            .put("action", "apply_promo")
                            .put("token", token)
                            .put("code", code)
                            .toString()
                        )
                    }
                    val body = if (conn.responseCode in 200..299) {
                        conn.inputStream.bufferedReader().readText()
                    } else {
                        conn.errorStream?.bufferedReader()?.readText() ?: "{}"
                    }
                    conn.disconnect()
                    JSONObject(body)
                }

                if (response.optBoolean("ok", false)) {
                    val msg = response.optString("message", "Промокод активирован!")
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    view.findViewById<EditText>(R.id.et_promo_code).text.clear()

                    // Refresh data
                    loadPrivilegesInfo(view, token)
                } else {
                    val error = response.optString("error", "Ошибка")
                    Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Ошибка сети. Проверьте подключение.", Toast.LENGTH_SHORT).show()
            } finally {
                btnApply.isEnabled = true
                btnApply.text = "Применить"
            }
        }
    }

    private fun showQrDialog(referralUrl: String) {
        val dialog = android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_qr_referral)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val ivQr = dialog.findViewById<ImageView>(R.id.iv_qr_code)
        val pbLoading = dialog.findViewById<ProgressBar>(R.id.pb_qr_loading)
        val tvLink = dialog.findViewById<TextView>(R.id.tv_qr_link)
        val btnClose = dialog.findViewById<Button>(R.id.btn_qr_close)

        tvLink.text = referralUrl
        btnClose.setOnClickListener { dialog.dismiss() }

        // Load QR from api.qrserver.com
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val encoded = java.net.URLEncoder.encode(referralUrl, "UTF-8")
                    val qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=400x400&data=$encoded&bgcolor=ffffff&color=000000&margin=8"
                    val conn = URL(qrUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    val stream = conn.inputStream
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream.close()
                    conn.disconnect()
                    bmp
                }
                if (bitmap != null) {
                    pbLoading.visibility = View.GONE
                    ivQr.setImageBitmap(bitmap)
                } else {
                    Toast.makeText(requireContext(), "Не удалось загрузить QR", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            } catch (e: Exception) {
                pbLoading.visibility = View.GONE
                Toast.makeText(requireContext(), "Ошибка загрузки QR", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
