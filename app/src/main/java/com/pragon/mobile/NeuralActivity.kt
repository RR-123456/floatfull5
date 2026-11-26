package com.pragon.mobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * v34: Neural chat screen (assets/neural_chat.html), opened from Tools > Neural.
 * Plain full-screen page: back goes back inside the page when it can, otherwise closes the screen.
 */
class NeuralActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/neural_chat.html")
        }
        setContentView(web)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
    }

    override fun onDestroy() {
        try { web.stopLoading(); web.destroy() } catch (_: Exception) { }
        super.onDestroy()
    }
}
