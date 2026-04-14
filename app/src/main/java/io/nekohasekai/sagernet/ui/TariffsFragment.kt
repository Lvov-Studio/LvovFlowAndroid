package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import io.nekohasekai.sagernet.R
import java.net.HttpURLConnection
import java.net.URL

/**
 * LvovFlow — Tariffs Tab (Native UI, Server-Driven Pricing)
 *
 * Displays tariff cards natively with glassmorphism design.
 * Prices and payment URLs are fetched from the server so they
 * can be updated without releasing a new APK.
 */
class TariffsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tariffs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
        val token = prefs.getString("session_token", "") ?: ""
        val email = prefs.getString("user_email", "") ?: ""

        val btnFlowBuy = view.findViewById<TextView>(R.id.btn_flow_buy)
        val tvFlowPrice = view.findViewById<TextView>(R.id.tv_flow_price)

        // Default: open payment page in browser
        btnFlowBuy.setOnClickListener {
            val url = "https://lvovflow.com/app/app_tariffs.php?token=$token&action=buy_flow"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // Fetch dynamic pricing from server
        if (token.isNotBlank()) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("https://lvovflow.com/api/app/tariffs_config.php")
                        .openConnection() as HttpURLConnection
                    conn.apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("X-App-Client", "LvovFlow-Android")
                        setRequestProperty("X-Session-Token", token)
                        connectTimeout = 6_000
                        readTimeout = 6_000
                        doOutput = true
                    }
                    java.io.OutputStreamWriter(conn.outputStream).use { it.write("{}") }
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val json = JSONObject(body)

                    if (json.optBoolean("ok")) {
                        val plans = json.optJSONArray("plans")
                        if (plans != null && plans.length() > 0) {
                            val flow = plans.getJSONObject(0)
                            val price = flow.optString("price", "99")
                            val payUrl = flow.optString("pay_url", "")

                            withContext(Dispatchers.Main) {
                                tvFlowPrice.text = "$price ₽"
                                if (payUrl.isNotBlank()) {
                                    btnFlowBuy.setOnClickListener {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payUrl)))
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Use default static data — no crash
                }
            }
        }
    }
}
