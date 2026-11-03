package com.deep.indiapulse

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.Activity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var web: WebView

    /** Native HTTP for the page: no CORS, no proxies. Result is returned base64-encoded. */
    inner class Bridge {
        @JavascriptInterface
        fun fetch(id: Int, url: String) {
            if (!url.startsWith("https://news.google.com/")) {
                reply(id, false, "blocked host".toByteArray()); return
            }
            thread {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 15000
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IndiaPulse")
                    if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
                    reply(id, true, c.inputStream.use { it.readBytes() })
                } catch (e: Exception) {
                    reply(id, false, (e.message ?: "network error").toByteArray())
                }
            }
        }
    }

    private fun reply(id: Int, ok: Boolean, bytes: ByteArray) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        runOnUiThread { web.evaluateJavascript("window.__nativeDone($id,$ok,'$b64')", null) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                // Headline links open in the user's browser, not inside the app
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    if (u.scheme == "http" || u.scheme == "https") {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.toString())))
                        return true
                    }
                    return false
                }
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // Page auto-refreshes hourly; also catch up if the app was backgrounded for a while
        web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null)
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
