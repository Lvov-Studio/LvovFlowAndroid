package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

/**
 * LvovFlow — Tariffs Tab (Server-Driven UI)
 *
 * Loads the tariffs page from the server via WebView.
 * The page is styled to look identical to native UI.
 * Benefits: prices, plans, and promotions can be updated
 * on the server without releasing a new APK.
 */
class TariffsFragment : Fragment() {

    private var webView: WebView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tariffs, container, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = view.findViewById(R.id.webview_tariffs)
        val loading = view.findViewById<ProgressBar>(R.id.tariffs_loading)
        val offline = view.findViewById<LinearLayout>(R.id.tariffs_offline)
        val btnRetry = view.findViewById<Button>(R.id.btn_retry_tariffs)

        // Get user token for personalized pricing / payment links
        val prefs = requireContext().getSharedPreferences("lvovflow", Context.MODE_PRIVATE)
        val token = prefs.getString("session_token", "") ?: ""

        val url = "https://lvovflow.com/app/app_tariffs.php?token=$token"

        // Configure WebView to behave like native UI
        webView?.apply {
            setBackgroundColor(0xFF0A1628.toInt()) // Match app background — no white flash
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Disable zoom controls
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loading.visibility = View.VISIBLE
                    offline.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    // Show offline fallback only for the main page load
                    if (request?.isForMainFrame == true) {
                        loading.visibility = View.GONE
                        offline.visibility = View.VISIBLE
                        view?.visibility = View.GONE
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val reqUrl = request?.url?.toString() ?: return false

                    // Payment links & external URLs — open in system browser
                    if (reqUrl.contains("payment") ||
                        reqUrl.contains("yookassa") ||
                        reqUrl.contains("checkout") ||
                        !reqUrl.startsWith("https://lvovflow.com/app/")
                    ) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(reqUrl)))
                        return true
                    }

                    return false // Let WebView handle internal navigation
                }
            }

            loadUrl(url)
        }

        // Retry button for offline state
        btnRetry.setOnClickListener {
            offline.visibility = View.GONE
            webView?.visibility = View.VISIBLE
            webView?.loadUrl(url)
        }
    }

    override fun onDestroyView() {
        webView?.apply {
            stopLoading()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroyView()
    }
}
