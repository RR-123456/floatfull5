package com.pragon.mobile

import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * UI customiser: the PC orb page with its "UI customiser" widget open (UI editor, Orb style, Orb tuning,
 * Colour & glass, Cluster maker, Motion combos, Click sound, Core logo ...). Opened from Settings.
 * Everything saved here is read by the floating bubble, which reloads when this screen closes.
 */
class CustomiserActivity : AppCompatActivity() {
    private var web: WebView? = null
    private var pending: ValueCallback<Array<Uri>>? = null

    /** Android's file picker for the page's <input type=file> (Core logo, face files ...). A WebView shows nothing unless the app does this. */
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pending?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        pending = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = OrbWeb.create(this, bubble = false, tuner = true, fileChooser = { cb, intent ->
            pending?.onReceiveValue(null)          // a picker the page never finished: release it
            pending = cb
            try { picker.launch(intent); true } catch (e: Exception) { pending = null; cb.onReceiveValue(null); false }
        })
        setContentView(web)
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }

    override fun onDestroy() {
        pending?.onReceiveValue(null); pending = null
        FloatService.reloadOrb()      // the bubble picks up what was just saved
        web?.destroy(); web = null
        super.onDestroy()
    }
}
