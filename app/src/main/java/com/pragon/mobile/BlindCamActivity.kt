package com.pragon.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * v7.6 BlindCam: a camera that talks. Open it from Tools. Nothing is saved: frames live in memory only and are dropped after analysis.
 *
 *  - TAP TO CAPTURE (big button, or tap anywhere on the picture, or Volume Up): looks at the next frame and speaks what it found.
 *  - LIVE (button, or Volume Down): looks every couple of seconds and speaks only when what it sees has changed.
 *  - Modes (switch any time): AUTO (scene sentence + any text), READ TEXT, OBJECTS (with where each is), DESCRIBE (scene sentence).
 *    A tap-capture in AUTO / DESCRIBE uses the on-phone chat model for a nicer sentence (a few seconds); live mode always uses the quick
 *    version built from the detected objects, so it keeps up.
 * Everything runs on the phone (ML Kit + the Gemma 3 1B chat model). The status line is a live region, so TalkBack reads it too.
 */
class BlindCamActivity : AppCompatActivity() {

    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var liveBtn: Button
    private val modeBtns = HashMap<Vision.CamMode, Button>()

    private val cam = Executors.newSingleThreadExecutor()      // camera frames
    private val worker = Executors.newSingleThreadExecutor()   // ML Kit and the chat model
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var wantFrame = false
    @Volatile private var busy = false
    @Volatile private var live = false
    @Volatile private var mode = Vision.CamMode.AUTO
    @Volatile private var lastLiveAt = 0L
    @Volatile private var lastKey = ""
    private var provider: ProcessCameraProvider? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else { say("I need the camera permission. Allow it for Pragon in the phone's app settings.", true); }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = "Camera. Double tap to capture."
            setOnClickListener { capture() }
        }
        root.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            text = "BlindCam. Tap to capture, or turn on Live."
            setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(40), dp(16), dp(16))
            setBackgroundColor(0xB3000000.toInt())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6000000.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(20))
        }
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in Vision.CamMode.values()) {
            val b = button(m.label, 15f)
            b.setOnClickListener { setMode(m) }
            modeBtns[m] = b
            modes.addView(b, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        panel.addView(modes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cap = button("TAP TO CAPTURE", 20f).apply { setOnClickListener { capture() } }
        accent(cap)
        liveBtn = button("LIVE: OFF", 16f).apply { setOnClickListener { toggleLive() } }
        actions.addView(cap, LinearLayout.LayoutParams(0, dp(88), 2f).apply { setMargins(dp(3), dp(10), dp(3), 0) })
        actions.addView(liveBtn, LinearLayout.LayoutParams(0, dp(88), 1f).apply { setMargins(dp(3), dp(10), dp(3), 0) })
        panel.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        refreshModes()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    // ── controls ──────────────────────────────────────────────────────────

    private fun button(label: String, size: Float) = Button(this).apply {
        text = label; textSize = size; isAllCaps = false; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF1A1F26.toInt()); setStroke(dp(1), 0x6600D4FF) }
        contentDescription = label
    }

    private fun accent(b: Button) {
        b.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0xFF0B4660.toInt()); setStroke(dp(2), 0xFF00D4FF.toInt()) }
    }

    private fun refreshModes() {
        for ((m, b) in modeBtns) {
            val on = m == mode
            b.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (on) 0xFF0B4660.toInt() else 0xFF1A1F26.toInt())
                setStroke(dp(if (on) 2 else 1), if (on) 0xFF00D4FF.toInt() else 0x6600D4FF)
            }
            b.contentDescription = m.label + if (on) ", selected" else ""
        }
        liveBtn.text = if (live) "LIVE: ON" else "LIVE: OFF"
        liveBtn.contentDescription = if (live) "Live is on. Double tap to turn off." else "Live is off. Double tap to turn on."
    }

    private fun setMode(m: Vision.CamMode) {
        mode = m; lastKey = ""
        refreshModes()
        say("Mode: ${m.label}.", true)
    }

    private fun toggleLive() {
        live = !live; lastKey = ""; lastLiveAt = 0L
        refreshModes()
        say(if (live) "Live on." else "Live off.", true)
    }

    private fun capture() {
        if (busy) { say("One moment, still looking.", true); return }
        status.text = "Looking..."
        wantFrame = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> { capture(); true }
        KeyEvent.KEYCODE_VOLUME_DOWN -> { toggleLive(); true }
        else -> super.onKeyDown(keyCode, event)
    }

    // ── camera ────────────────────────────────────────────────────────────

    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try {
                val p = f.get(); provider = p
                val pv = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(960, 1280))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cam) { proxy ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        val wanted = wantFrame && !busy
                        val tick = live && !busy && now - lastLiveAt > 2500
                        if (wanted || tick) {
                            val bmp = proxy.toBitmap().let { b ->
                                val deg = proxy.imageInfo.rotationDegrees
                                if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
                            }
                            if (wanted) wantFrame = false
                            lastLiveAt = now
                            analyse(bmp, interactive = wanted)
                        }
                    } catch (e: Throwable) {
                        busy = false
                    } finally { proxy.close() }
                }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, analysis)
                say("Camera ready. Tap to capture, or turn on Live.", true)
            } catch (e: Exception) {
                say("I couldn't start the camera: ${e.message}", true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyse(bmp: Bitmap, interactive: Boolean) {
        busy = true
        val m = mode
        worker.execute {
            val r = try { Vision.analyzeFrame(applicationContext, bmp, m, quick = !interactive) }
                    catch (e: Throwable) { Vision.FrameResult("I couldn't analyse that: ${e.message}", "") }
            main.post {
                busy = false
                if (interactive) { lastKey = r.key; say(r.spoken, true) }
                else if (live && r.key.isNotEmpty() && r.key != lastKey) {
                    lastKey = r.key
                    say(r.spoken, !Speaker.get(this).speaking)   // live: show always, speak only when not already talking
                } else if (live && r.key.isEmpty() && lastKey.isNotEmpty()) {
                    lastKey = ""   // the scene went empty: say nothing, but speak again when something new appears
                }
            }
        }
    }

    private fun say(text: String, speak: Boolean) {
        status.text = text
        if (speak) try { Speaker.get(this).speak(text) } catch (e: Exception) { }
    }

    override fun onPause() {
        super.onPause()
        live = false; wantFrame = false
        try { Speaker.get(this).stop() } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        live = false
        try { provider?.unbindAll() } catch (e: Exception) { }
        cam.shutdown(); worker.shutdown()
    }
}
