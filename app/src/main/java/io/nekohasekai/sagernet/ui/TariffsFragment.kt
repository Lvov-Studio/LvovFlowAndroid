package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.nekohasekai.sagernet.R

class TariffsFragment : androidx.fragment.app.Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tariffs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnBuy1Month = view.findViewById<Button>(R.id.btn_buy_1_month)
        btnBuy1Month.setOnClickListener {
            // LvovFlow: open YuKassa payment via server-side redirect
            val prefs = requireContext().getSharedPreferences("lvovflow", android.content.Context.MODE_PRIVATE)
            val token = prefs.getString("session_token", "") ?: ""

            val url = if (token.isNotBlank()) {
                "https://lvovflow.com/api/payment/create.php?token=$token"
            } else {
                "https://lvovflow.com/#pricing"
            }

            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
