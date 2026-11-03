package com.deep.indiapulse

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var web: WebView          // the India Pulse feed (local page)
    private lateinit var reader: WebView       // in-app article reader
    private lateinit var readerPane: LinearLayout
    private lateinit var readerTitle: TextView
    private lateinit var progressBar: ProgressBar
    private var readerUrl: String = ""

    /** Native HTTP for the feed page: no CORS, no proxies. Result is returned base64-encoded.
     *  Only attached to the feed WebView — never to the article reader. */
    inner class Bridge {
        /** Page saves topics / watch / mute / alert settings here; the hourly worker reads them. */
        @JavascriptInterface
        fun saveConfig(json: String) {
            getSharedPreferences("ip", MODE_PRIVATE).edit().putString("config", json).apply()
            val on = try { JSONObject(json).optJSONObject("alerts")?.optBoolean("on") ?: false } catch (e: Exception) { false }
            runOnUiThread { if (on) { askNotificationPermission(); scheduleAlerts() } else cancelAlerts() }
        }

        @JavascriptInterface
        fun testAlert() {
            runOnUiThread { askNotificationPermission() }
            Notifier.post(this@MainActivity, 1, "India Pulse test alert", "Notifications are working. Tap to open a sample story.", "https://news.google.com/")
        }

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

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun scheduleAlerts() {
        val req = PeriodicWorkRequestBuilder<AlertWorker>(60, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("alerts", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun cancelAlerts() {
        WorkManager.getInstance(this).cancelUniqueWork("alerts")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("url")?.let { openReader(it) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun barButton(text: String, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(14), dp(12), dp(14))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildReader(): LinearLayout {
        val close = barButton("✕", Color.parseColor("#F2F2F2")).apply { setOnClickListener { closeReader() } }
        readerTitle = TextView(this).apply {
            setTextColor(Color.parseColor("#999999"))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val external = barButton("Open in browser", Color.parseColor("#D6B36A")).apply {
            setOnClickListener {
                if (readerUrl.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(readerUrl)))
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(close, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(readerTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(external, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable.setTint(Color.parseColor("#D6B36A"))
            visibility = View.GONE
        }
        reader = WebView(this).apply {
            setBackgroundColor(Color.WHITE)   // articles are designed for light backgrounds
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, p: Int) {
                    progressBar.progress = p
                    progressBar.visibility = if (p in 1..99) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val s = r.url.scheme
                    return !(s == "http" || s == "https")   // stay inside the reader; ignore intent:// etc.
                }
                override fun onPageFinished(v: WebView, url: String) {
                    if (url != "about:blank") {
                        readerUrl = url
                        readerTitle.text = Uri.parse(url).host ?: ""
                    }
                }
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true   // swallow touches so the feed underneath doesn't react
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(progressBar, LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
            addView(reader, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
    }

    private fun openReader(url: String) {
        readerUrl = url
        readerTitle.text = Uri.parse(url).host ?: ""
        readerPane.visibility = View.VISIBLE
        reader.loadUrl(url)
    }

    private fun closeReader() {
        readerPane.visibility = View.GONE
        reader.loadUrl("about:blank")
        reader.clearHistory()
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
                // Headline taps open in the in-app reader
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    if (u.scheme == "http" || u.scheme == "https") {
                        openReader(u.toString())
                        return true
                    }
                    return false
                }
            }
        }
        readerPane = buildReader()
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(readerPane, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
        web.loadUrl("file:///android_asset/index.html")
        intent?.getStringExtra("url")?.let { openReader(it) }   // opened from a notification
    }

    override fun onResume() {
        super.onResume()
        web.onResume(); reader.onResume()
        // Page auto-refreshes hourly; also catch up if the app was backgrounded for a while
        web.evaluateJavascript("document.dispatchEvent(new Event('visibilitychange'))", null)
    }

    override fun onPause() {
        web.onPause(); reader.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        reader.destroy(); web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            readerPane.visibility == View.VISIBLE ->
                if (reader.canGoBack()) reader.goBack() else closeReader()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }
}
