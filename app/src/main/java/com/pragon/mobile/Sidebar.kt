package com.pragon.mobile

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.BatteryManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Smart sidebar: a thin handle on the screen edge. Tap it (or swipe inwards) and a panel slides out with
 *   - smart chips that change with what you're doing (media controls while music plays, Typer when a text box is
 *     focused, Read / Ask screen when you allowed screen reading, Stop macro while a macro is running)
 *   - quick controls: flashlight, volume, brightness, screenshot, home/back
 *   - your favourite apps (most used first), only ones Pragon is allowed to open
 *   - your macros, with one-tap play and record
 * Every button goes through the same command code as voice, so the same switches and limits apply.
 */
class SidebarService : Service() {

    companion object {
        const val ACTION_STOP = "com.pragon.mobile.SIDEBAR_STOP"
        @Volatile var running = false
        @Volatile var instance: SidebarService? = null

        fun start(ctx: Context): String? {
            if (!Settings.canDrawOverlays(ctx) && PragonAccessibilityService.instance == null)
                return "Allow 'Display over other apps' for Pragon first."
            try { ctx.startForegroundService(Intent(ctx, SidebarService::class.java)) } catch (e: Exception) { return "Android wouldn't start the sidebar: ${e.message}" }
            return null
        }
        fun stop(ctx: Context) { try { ctx.startService(Intent(ctx, SidebarService::class.java).setAction(ACTION_STOP)) } catch (e: Exception) { } }
        fun open() { instance?.let { s -> s.main.post { s.expand() } } }
        fun close() { instance?.let { s -> s.main.post { s.collapse() } } }
        /** v14: open the panel on the File Dock tab (after something was shared into it). */
        fun showDock() { instance?.let { s -> s.main.post { s.setFilesTab("dock"); s.openTab(3); s.expand() } } }
        /** v14: redraw the FILES section if the panel is open (the dock or quick files changed). */
        fun refreshFiles() { instance?.let { s -> s.main.post { if (s.expanded) s.fillFiles() } } }

        private fun prefs(c: Context) = c.getSharedPreferences("pragon_sidebar", Context.MODE_PRIVATE)

        /** v14: the Quick Settings tiles shown in the TILES section (ids from QuickCatalog). Comma-separated pref. */
        const val DEFAULT_TILES = "wifi,bluetooth,mobiledata,airplane,rotate,torch,dnd,highperf,workapps,songsearch,screenrec,devicecontrols,voicescribe"
        fun sidebarTiles(c: Context): List<String> =
            (prefs(c).getString("tiles", DEFAULT_TILES) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        /** v19: the bottom tabs, in the order and visibility you chose with the pencil button (same as the preview's "Edit sections"). */
        val TABS = listOf(Triple(0, "ctl", "Control"), Triple(1, "grid", "Tiles"), Triple(2, "apps", "Apps"), Triple(3, "files", "Files"), Triple(4, "macro", "Macros"), Triple(5, "gear", "Settings"))
        fun tabOrder(c: Context): List<Int> {
            val saved = (prefs(c).getString("tab_order", "") ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..5 }.distinct()
            return saved + (0..5).filter { it !in saved }
        }
        fun tabHidden(c: Context): Set<Int> =
            (prefs(c).getString("tab_hidden", "") ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..5 }.toSet()
        fun saveTabs(c: Context, order: List<Int>, hidden: Set<Int>) =
            prefs(c).edit().putString("tab_order", order.joinToString(",")).putString("tab_hidden", hidden.joinToString(",")).apply()
        fun leftSide(c: Context) = prefs(c).getBoolean("left", false)
        fun setLeft(c: Context, left: Boolean) = prefs(c).edit().putBoolean("left", left).apply()
        fun autoStart(c: Context) = prefs(c).getBoolean("auto", false)
        fun setAutoStart(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()

        /** Opened-through-Pragon counters: the sidebar lists the apps you use most first. */
        fun countOpen(c: Context, pkg: String) {
            val p = prefs(c)
            val o = try { JSONObject(p.getString("uses", "{}")) } catch (e: Exception) { JSONObject() }
            o.put(pkg, o.optInt(pkg, 0) + 1)
            p.edit().putString("uses", o.toString()).apply()
        }
        fun uses(c: Context): Map<String, Int> {
            val o = try { JSONObject(prefs(c).getString("uses", "{}")) } catch (e: Exception) { return emptyMap() }
            return o.keys().asSequence().associateWith { o.optInt(it, 0) }
        }
    }

    val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var handle: View? = null
    private var panel: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    @Volatile private var expanded = false
    private var filesBox: LinearLayout? = null
    private var filesGen = 0
    private var openEntry: String? = null          // the File Dock item whose Share / Copy / Remove buttons are showing
    private var docLimit = 12
    private var appsBox: LinearLayout? = null
    private var appGrid: LinearLayout? = null
    private var appQuery = ""
    private var appLimit = 24
    private var appsGen = 0
    @Volatile private var appCache: List<AppEntry>? = null
    @Volatile private var appCacheAt = 0L
    private var dragging = false
    private val thumbCache = android.util.LruCache<String, Bitmap>(48)
    private var toast: TextView? = null
    private var ptab = 0                           // v17: 0 Control, 1 Tiles, 2 Apps, 3 Files, 4 Macros (the bottom tab bar)
    private var editing = false                    // v19: the "Edit sections" screen (pencil button in the header)
    /** v19.1: the panel is full height, so keep the header clear of the status bar / camera notch and the tabs clear of the nav bar. */
    private fun sysBar(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
    private fun applyInsets() {
        val p = panel ?: return
        var top = sysBar("status_bar_height"); var bottom = sysBar("navigation_bar_height")
        try {
            val wi = p.rootWindowInsets
            if (wi != null) {
                if (android.os.Build.VERSION.SDK_INT >= 28) wi.displayCutout?.let { top = maxOf(top, it.safeInsetTop); bottom = maxOf(bottom, it.safeInsetBottom) }
                if (android.os.Build.VERSION.SDK_INT >= 30) { val b = wi.getInsets(android.view.WindowInsets.Type.systemBars()); top = maxOf(top, b.top); bottom = maxOf(bottom, b.bottom) }
            }
        } catch (_: Throwable) { }
        p.setPadding(dp(10), dp(10) + top, dp(10), dp(10) + bottom)
    }
    private fun panelW() = minOf(dp(300), (resources.displayMetrics.widthPixels * 0.88f).toInt())
    /** Switches to a tab; a hidden tab is switched back on first so the panel never shows a tab that has no button. */
    fun openTab(i: Int) {
        val h = tabHidden(this)
        if (i in h) saveTabs(this, tabOrder(this), h - i)
        ptab = i; editing = false
    }
    private var qaEdit = false                     // v17: Quick apps "Edit" mode (shows ✕ on each app)

    // v16: LOOK & GLOW (screen-edge glow, colour, sound reactive, match video colour)
    private var viaA11yNow = false
    private var glowView: GlowView? = null
    private var glowLevel = 0f
    @Volatile private var vizLevel = 0f
    private var viz: Visualizer? = null
    private val glowCur = floatArrayOf(0f, 170f, 255f)
    @Volatile private var sampled: IntArray? = null
    @Volatile private var sampledAt = 0L
    @Volatile private var sampling = false
    private var sampleExec: java.util.concurrent.ExecutorService? = null
    private var bodyScroll: ScrollView? = null

    private val cyan = 0xFF00D4FF.toInt()
    private val bg = 0xFF000000.toInt()
    private val chip = 0xFF0C0E12.toInt()
    private val txt = 0xFFE9EDF4.toInt()
    private val mute = 0xFF9AA4B5.toInt()
    private val onTxt = 0xFFBFF3FF.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // v17: the Settings-page look (same Newsreader font, ash buttons, teal "on" state, gradient cards as the preview)
    private fun font(weight: Int): Typeface = try {
        Typeface.Builder(assets, "fonts/newsreader.ttf").setFontVariationSettings("'wght' $weight").build() ?: Typeface.SERIF
    } catch (e: Throwable) { Typeface.SERIF }
    private val fReg: Typeface by lazy { font(400) }
    private val fSemi: Typeface by lazy { font(600) }
    /** ash: the dark metal surface of the normal buttons */
    private fun ash(r: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0C0E12.toInt(), 0xFF040506.toInt(), 0xFF080A0D.toInt())).apply {
        cornerRadius = dp(r).toFloat(); setStroke(dp(1), 0x1AFFFFFF)
    }
    /** teal: the "on" / selected state */
    private fun teal(r: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B4660.toInt(), 0xFF06222F.toInt())).apply {
        cornerRadius = dp(r).toFloat(); setStroke(dp(1), 0x6600D4FF)
    }
    /** card: list rows (files) */
    private fun card(r: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0D1014.toInt(), 0xFF06080A.toInt())).apply {
        cornerRadius = dp(r).toFloat(); setStroke(dp(1), 0x2EFFFFFF)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        if (running) return START_STICKY
        try { startInForeground() } catch (e: Throwable) { stopSelf(); return START_NOT_STICKY }
        val a11y = PragonAccessibilityService.instance
        wm = (a11y ?: this).getSystemService(Context.WINDOW_SERVICE) as WindowManager
        running = true
        instance = this
        viaA11yNow = a11y != null
        try { build(viaA11y = a11y != null) } catch (e: Throwable) { shutdown(); return START_NOT_STICKY }
        applyGlow()
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pragon_sidebar", "Sidebar", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 9, Intent(this, SidebarService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "pragon_sidebar").setSmallIcon(android.R.drawable.ic_menu_more)
            .setContentTitle("Pragon sidebar is on").setContentText("Tap to turn it off").setContentIntent(stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(10, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(10, n)
    }

    private fun shutdown() {
        running = false
        instance = null
        main.removeCallbacksAndMessages(null)
        stopGlow()
        root?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        root = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        if (running) { running = false; instance = null; stopGlow(); root?.let { try { wm.removeView(it) } catch (e: Exception) { } }; root = null }
        super.onDestroy()
    }

    // ── window ───────────────────────────────────────────────────────────

    private fun type(viaA11y: Boolean) =
        if (viaA11y) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun build(viaA11y: Boolean) {
        val left = leftSide(this)
        val r = FrameLayout(this)
        root = r
        // the thin handle
        val h = SbHandle(this, left)
        handle = h
        r.addView(h, FrameLayout.LayoutParams(dp(18), dp(120), (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL))
        h.setOnTouchListener(handleTouch(left))
        // v14: dragging something from another app to the edge opens the File Dock so it can be dropped in
        h.setOnDragListener { _, ev ->
            when (ev.action) {
                DragEvent.ACTION_DRAG_STARTED -> ev.localState != "pragon_dock"
                DragEvent.ACTION_DRAG_ENTERED -> { setFilesTab("dock"); openTab(3); expand(); true }
                else -> true
            }
        }

        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bg); setStroke(dp(1), 0x4700D4FF)
                val rad = dp(20).toFloat()
                cornerRadii = if (left) floatArrayOf(0f, 0f, rad, rad, rad, rad, 0f, 0f) else floatArrayOf(rad, rad, 0f, 0f, 0f, 0f, rad, rad)
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
            elevation = dp(10).toFloat()
        }
        panel = p
        r.addView(p, FrameLayout.LayoutParams(panelW(), ViewGroup.LayoutParams.MATCH_PARENT, if (left) Gravity.START else Gravity.END))

        val sh = resources.displayMetrics.heightPixels
        val params = WindowManager.LayoutParams(
            dp(18), dp(120), type(viaA11y),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (left) Gravity.START else Gravity.END) or Gravity.TOP
            x = 0
            y = getSharedPreferences("pragon_sidebar", MODE_PRIVATE).getInt("y", (sh * 0.4).toInt())
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        lp = params
        try { wm.addView(r, params) } catch (e: Throwable) {
            if (viaA11y && Settings.canDrawOverlays(this)) {
                wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                wm.addView(r, params)
            } else throw e
        }
    }

    private fun handleTouch(left: Boolean) = object : View.OnTouchListener {
        var downX = 0f; var downY = 0f; var startY = 0; var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val params = lp ?: return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startY = params.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    // sideways pull toward the screen centre opens it; a mostly vertical drag moves the handle
                    if (kotlin.math.abs(dy) > dp(14) && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        moved = true
                        params.y = (startY + dy).toInt().coerceIn(0, resources.displayMetrics.heightPixels - dp(120))
                        try { wm.updateViewLayout(root, params) } catch (x: Exception) { }
                    } else if ((if (left) dx else -dx) > dp(18)) { expand(); return true }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) getSharedPreferences("pragon_sidebar", MODE_PRIVATE).edit().putInt("y", params.y).apply()
                    else expand()
                }
            }
            return true
        }
    }

    // ── open / close ─────────────────────────────────────────────────────

    private var idleClose = Runnable { if (!dragging) collapse() }

    fun expand() {
        val params = lp ?: return
        val p = panel ?: return
        if (expanded) { refreshPanel(); return }
        expanded = true
        applyInsets()
        fillPanel()
        params.width = panelW() + dp(18)
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.y = 0
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv() or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        try { wm.updateViewLayout(root, params) } catch (e: Exception) { }
        handle?.visibility = View.GONE
        p.visibility = View.VISIBLE
        p.translationX = if (leftSide(this)) -panelW().toFloat() else panelW().toFloat()
        p.animate().translationX(0f).setDuration(180).start()
        root?.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { collapse(); true } else false }
        root?.setOnDragListener { _, ev ->
            if (ev.action == DragEvent.ACTION_DRAG_ENDED && dragging) dragging = false
            dragging   // only our own drag-out needs to be heard; foreign drags go to the drop zone
        }
        resetIdle()
    }

    fun collapse() {
        val params = lp ?: return
        val p = panel ?: return
        if (!expanded) return
        expanded = false
        main.removeCallbacks(idleClose)
        typingMode(false); appQuery = ""; appLimit = 24; editing = false
        params.width = dp(18)
        params.height = dp(120)
        params.y = getSharedPreferences("pragon_sidebar", MODE_PRIVATE).getInt("y", (resources.displayMetrics.heightPixels * 0.4).toInt())
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        p.visibility = View.GONE
        handle?.visibility = View.VISIBLE
        try { wm.updateViewLayout(root, params) } catch (e: Exception) { }
    }

    private fun resetIdle() { main.removeCallbacks(idleClose); main.postDelayed(idleClose, 12000) }

    private fun refreshPanel() { fillPanel(); resetIdle() }

    // ── content ──────────────────────────────────────────────────────────

    /** Section title: cyan small caps with a fading line, like the Settings page. */
    private fun label(t: String, size: Float = 12f, color: Int = cyan, line: Boolean = true): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(14), dp(2), dp(6))
        addView(TextView(this@SidebarService).apply { text = t.uppercase(); textSize = size; setTextColor(color); typeface = fSemi; letterSpacing = 0.18f })
        if (line) addView(View(this@SidebarService).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x5900D4FF, 0x0000D4FF))
        }, LinearLayout.LayoutParams(0, dp(1), 1f).apply { setMargins(dp(10), 0, 0, 0) })
    }

    private fun button(text: String, hot: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 14f; gravity = Gravity.CENTER
        setTextColor(if (hot) onTxt else txt); typeface = fSemi
        background = if (hot) teal(12) else ash(12)
        setPadding(dp(10), dp(11), dp(10), dp(11))
        setOnClickListener { resetIdle(); onClick() }
        shine(this)
    }

    private fun row(vararg v: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (x in v) addView(x, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    /** Runs one Pragon command off the main thread and shows the result in the panel. */
    private fun cmd(action: String, value: String = "", collapseAfter: Boolean = false) {
        Thread {
            val r = try { CommandExecutor.run(applicationContext, JSONObject().put("action", action).put("value", value), user = true) }
            catch (e: Exception) { CommandExecutor.Res(false, e.message ?: "failed") }
            main.post { say(r.msg); if (collapseAfter && r.ok) collapse() }
        }.start()
    }

    private fun tileChip(it: QuickCatalog.Item): View {
        val st = try { Quick.stateOf(this, it) } catch (e: Exception) { "" }
        val on = st == "on"
        val ic = mapOf("wifi" to "wifi", "bluetooth" to "bt", "mobiledata" to "data", "airplane" to "plane", "rotate" to "rot", "torch" to "torch", "dnd" to "dnd",
            "highperf" to "bolt", "workapps" to "work", "songsearch" to "note", "screenrec" to "scr", "devicecontrols" to "dev", "voicescribe" to "mic")[it.id] ?: "tool"
        return tileK(ic, it.label, on) { tapTile(it) }
    }

    /** Runs the phone switch or tile off the main thread, then redraws the panel so the on/off marks update. */
    private fun tapTile(it: QuickCatalog.Item) {
        Thread {
            val r = try { Quick.run(applicationContext, it.id, user = true) }
                    catch (e: Exception) { CommandExecutor.Res(false, e.message ?: "failed") }
            main.post { say(r.msg) }
            main.postDelayed({ if (panel != null) { fillPanel(); say(r.msg) } }, 700)
            if (it.kind == "btn" && r.ok) main.post { collapse() }
        }.start()
    }

    private fun say(t: String) { toast?.text = t.take(140); toast?.visibility = if (t.isBlank()) View.GONE else View.VISIBLE }

    private fun fillPanel() {
        val p = panel ?: return
        p.removeAllViews()
        if (editing) {
            toast = TextView(this).apply { textSize = 12.5f; setTextColor(0xFF6EE7B7.toInt()); typeface = fReg; visibility = View.GONE; setPadding(dp(4), dp(2), dp(4), dp(2)) }
            val sv0 = ScrollView(this).apply { isVerticalScrollBarEnabled = false; setOnScrollChangeListener { _, _, _, _, _ -> resetIdle() } }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sv0.addView(col); bodyScroll = sv0
            filesBox = null; appsBox = null; appGrid = null
            editorScreen(col)
            p.addView(toast)
            p.addView(sv0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            return
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(2), dp(2), dp(10)) }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.pg_logo); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(0, 0, dp(10), 0) })
        val ttl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ttl.addView(TextView(this).apply { text = "PRAGON"; textSize = 17f; setTextColor(cyan); typeface = fSemi; letterSpacing = 0.18f })
        ttl.addView(TextView(this).apply { text = "Smart sidebar · online"; textSize = 11.5f; setTextColor(mute); typeface = fReg; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        head.addView(ttl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(if (editing) cyan else 0x1400D4FF); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0x4D00D4FF) }
            contentDescription = "Edit sections"
            setOnClickListener { resetIdle(); editing = !editing; openSel = null; fillPanel() }
            addView(SbIcon(this@SidebarService, "edit", if (editing) SbIcon.LIGHT else SbIcon.CYAN), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(0, 0, dp(6), 0) })
        head.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0x1400D4FF); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0x4D00D4FF) }
            contentDescription = "Close"
            setOnClickListener { collapse() }
            addView(SbIcon(this@SidebarService, "x"), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        p.addView(head)
        p.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x0000D4FF, 0x8000D4FF.toInt(), 0x0000D4FF)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        toast = TextView(this).apply { textSize = 12.5f; setTextColor(0xFF6EE7B7.toInt()); typeface = fReg; visibility = View.GONE; setPadding(dp(4), dp(2), dp(4), dp(2)) }
        p.addView(toast)

        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; setOnScrollChangeListener { _, _, _, _, _ -> resetIdle() } }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(body)
        bodyScroll = sv
        p.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (!editing) { val vis = tabOrder(this).filter { it !in tabHidden(this) }; if (vis.isNotEmpty() && ptab !in vis) ptab = vis[0] }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        filesBox = null; appsBox = null; appGrid = null
        renderSection(body, ptab, am)
        p.addView(tabBar())
    }

    // ── v17: tabs (Control, Tiles, Apps, Files, Macros) ──────────────────

    /** v19: small round / pill buttons of the smart strip (icon, optional text), like the preview's .pill */
    private fun pill(icon: String, text: String, hot: Boolean = false, red: Boolean = false, onClick: () -> Unit): View {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = if (hot) teal(99) else ash(99)
            if (text.isEmpty()) { minimumWidth = dp(34); minimumHeight = dp(34) } else setPadding(dp(11), dp(7), dp(13), dp(7))
            setOnClickListener { resetIdle(); onClick() }
        }
        l.addView(SbIcon(this, icon, if (hot) SbIcon.LIGHT else if (red) SbIcon.RED else SbIcon.CYAN), LinearLayout.LayoutParams(dp(16), dp(16)))
        if (text.isNotEmpty()) l.addView(TextView(this).apply { this.text = text; textSize = 11.5f; setTextColor(if (hot) onTxt else txt); typeface = fSemi; maxLines = 1; setPadding(dp(6), 0, 0, 0) })
        shine(l)
        return l
    }

    /** v19: icon-chip tile (the preview's .k): glass icon chip above a label. */
    private fun tileK(icon: String, name: String, hot: Boolean = false, onClick: () -> Unit): View {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = if (hot) teal(12) else ash(12)
            setPadding(dp(2), dp(9), dp(2), dp(8))
            setOnClickListener { resetIdle(); onClick() }
        }
        val chip = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientCenter(0.3f, 0.18f); gradientRadius = dp(30).toFloat()
                colors = intArrayOf(0x3D00D4FF, 0x0A00D4FF)
                cornerRadius = dp(12).toFloat(); setStroke(dp(1), if (hot) 0x9900D4FF.toInt() else 0x3D00D4FF)
            }
        }
        chip.addView(SbIcon(this, icon, if (hot) SbIcon.LIGHT else SbIcon.CYAN), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        l.addView(chip, LinearLayout.LayoutParams(dp(36), dp(36)))
        l.addView(TextView(this).apply { text = name; textSize = 12f; gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(if (hot) onTxt else txt); typeface = fSemi; setPadding(dp(2), dp(5), dp(2), 0) })
        shine(l)
        return l
    }

    /** v19: the silver shine sweep from the preview: a soft light band crosses a button when you press it. */
    private fun shine(v: View) {
        v.clipToOutline = true
        v.setOnTouchListener { view, e ->
            if (e.action == MotionEvent.ACTION_DOWN && view.width > 0 && view.height > 0) {
                val w = view.width; val h = view.height; val bw = (w * 0.28f).toInt()
                val d = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF))
                view.overlay.add(d)
                android.animation.ValueAnimator.ofFloat(-0.35f, 1.1f).apply {
                    duration = 650
                    addUpdateListener { a -> val x = ((a.animatedValue as Float) * w).toInt(); d.setBounds(x, 0, x + bw, h) }
                    addListener(object : android.animation.AnimatorListenerAdapter() { override fun onAnimationEnd(a: android.animation.Animator) { view.overlay.remove(d) } })
                    start()
                }
            }
            false
        }
    }

    /** v19: one macro, drawn like the preview's .mac row (play icon + name). */
    private fun macRow(n: String): View {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = card(12)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setOnClickListener { resetIdle(); cmd("macro_run", n, true) }
        }
        l.addView(SbIcon(this, "play"), LinearLayout.LayoutParams(dp(18), dp(18)))
        l.addView(TextView(this).apply { text = n; textSize = 14f; setTextColor(txt); typeface = fSemi; setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        shine(l)
        return l
    }

    /** v19: fade/slide the body in when a tab opens (the preview's "rise" animation). */
    private fun riseIn() {
        bodyScroll?.let { it.alpha = 0f; it.translationY = dp(10).toFloat(); it.animate().alpha(1f).translationY(0f).setDuration(320).start() }
    }

    private fun smartStrip(body: LinearLayout, am: AudioManager) {
        // ---- smart strip: round icon pills that depend on what is happening right now ----
        val smart = ArrayList<View>()
        if (Macros.isRecording) smart.add(pill("rec", "Stop recording", true) { cmd("macro_stop") })
        if (Macros.playing != null) smart.add(pill("rec", "Stop macro", true) { cmd("macro_cancel") })
        if (am.isMusicActive && Glow.get(this, Glow.MUSIC)) {
            smart.add(pill("prev", "") { cmd("key", "previous") }); smart.add(pill("pause", "") { cmd("key", "pause") }); smart.add(pill("next", "") { cmd("key", "next") })
        }
        val svc = PragonAccessibilityService.instance
        if (svc != null && svc.hasFocusedTextBox()) smart.add(pill("talk", "", Typer.active) { cmd("typer", if (Typer.active) "off" else "on", true) })
        if (Guard.screenReadingOn(this) && svc != null) smart.add(pill("read", "") { cmd("screen_read", "", true) })
        val bat = BatteryReminder.level(this)
        if (bat in 0..15 && !BatteryReminder.charging(this)) smart.add(pill("bat", "$bat% low", false, true) { cmd("status") })
        if (smart.isNotEmpty()) {
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(6), dp(3), dp(2)) }
            smart.forEach { v -> strip.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(8), 0) }) }
            hs.addView(strip)
            body.addView(hs)
        }

    }

    private fun controlsBlk(body: LinearLayout) {
        body.addView(label("CONTROLS"))
        body.addView(row(tileK("torch", "Torch") { cmd("flashlight", "toggle") }, tileK("torch", "Torch +") { cmd("flashlight_level", "+") }, tileK("torch", "Torch −") { cmd("flashlight_level", "-") }))
        body.addView(row(tileK("vol", "Vol +") { cmd("volume", "+") }, tileK("vdn", "Vol −") { cmd("volume", "-") }, tileK("mute", "Mute") { cmd("key", "mute") }))
        body.addView(row(tileK("sun", "Bright +") { cmd("brightness", "+") }, tileK("sund", "Bright −") { cmd("brightness", "-") }, tileK("shot", "Shot") { cmd("key", "screenshot", true) }))
    }

    private fun levelsBlk(body: LinearLayout, am: AudioManager) {
        body.addView(label("LEVELS"))
        val vst = AudioManager.STREAM_MUSIC
        val vmax = am.getStreamMaxVolume(vst).coerceAtLeast(1)
        val bri = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255 } catch (e: Exception) { 50 }
        val lp3 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        body.addView(slider("vol", am.getStreamVolume(vst) * 100 / vmax) { cmd("volume", it.toString()) }, lp3)
        body.addView(slider("sun", bri) { cmd("brightness", it.toString()) }, LinearLayout.LayoutParams(lp3))
    }

    private fun navBlk(body: LinearLayout) {
        body.addView(label("NAVIGATE"))
        // one joined pill (Back | Home | Recents) like the preview's .nav
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = ash(24) }
        listOf(Triple("back", "Back", false), Triple("home", "Home", true), Triple("rec", "Recents", true)).forEachIndexed { i, (ic, t, c) ->
            if (i > 0) nav.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(0, dp(8), 0, dp(8)) })
            val cell = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(2), dp(11), dp(2), dp(11)); setOnClickListener { resetIdle(); cmd("key", t.lowercase(), c) } }
            cell.addView(SbIcon(this, ic), LinearLayout.LayoutParams(dp(16), dp(16)))
            cell.addView(TextView(this).apply { text = t; textSize = 12.5f; maxLines = 1; setTextColor(txt); typeface = fSemi; setPadding(dp(6), 0, 0, 0) })
            nav.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        body.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    private fun optBlk(body: LinearLayout) {
        body.addView(label("OPTIMISER"))
        optimiserCard(body, listOf(
            tileK("clean", "Clean") { openOptimiser("junk") },
            tileK("boost", "Boost") { boostNow() },
            tileK("tool", "Optimiser") { openOptimiser("") }))
    }

    private fun macrosBlk(body: LinearLayout) {
        // ---- macros ----
        if (Guard.macrosOn(this)) {
            body.addView(label("MACROS"))
            val names = Macros.names(this)
            names.take(8).forEach { n -> body.addView(macRow(n), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            if (!Macros.isRecording) body.addView(button("● Record new macro") { cmd("macro_record", "macro " + (names.size + 1), true) }.also { v ->
                v.setTextColor(0xFFFCA5A5.toInt())
                v.background = GradientDrawable().apply { setColor(0x1AEF4444); setStroke(dp(1), 0x59EF4444); cornerRadius = dp(12).toFloat() }
            },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
    }

    private fun lookBlk(body: LinearLayout, am: AudioManager) {
        // ---- v16: LOOK & GLOW (everything that used to sit on the preview's top bar) ----
        body.addView(label("LOOK & GLOW"))
        body.addView(row(button("✕ Close panel") { collapse() }, button("⇄ Switch side") { switchSide() }))
        body.addView(row(toggleBtn("♪ Music on/off", Glow.MUSIC, glow = false) { fillPanelKeepScroll() }, toggleBtn("Border glow", Glow.BORDER)))
        body.addView(row(toggleBtn("Haze glow", Glow.HAZE), toggleBtn("Ambient glow", Glow.AMBIENT)))
        body.addView(swatchRow())
        lateinit var vid: TextView
        vid = button(if (am.isMusicActive) "⏸ Pause video" else "▶ Play video") {
            cmd("key", if (am.isMusicActive) "pause" else "play")
            main.postDelayed({ vid.text = if (am.isMusicActive) "⏸ Pause video" else "▶ Play video" }, 800)
        }
        body.addView(row(vid, toggleBtn("Match video colour", Glow.MATCH)))
        body.addView(row(toggleBtn("Sound reactive", Glow.SOUND), View(this)))
    }


    /** v18: every app-like feature in one place: Music, Video editor, Drafter, Calculator, Maps, Calendar and more. */
    private fun toolIntent(cls: Class<*>, panel: String = "", action: String = ""): Intent =
        Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("panel", panel).putExtra("action", action)

    private fun openTool(i: Intent) {
        try { startActivity(i); collapse() } catch (e: Exception) { say("Could not open that.") }
    }

    private fun toolsBlk(body: LinearLayout) {
        fun tile(label: String, cls: Class<*>) = button(label) { openTool(toolIntent(cls)) }
        body.addView(label("MUSIC & MEDIA"))
        body.addView(row(tile("🎵 Music", MusicActivity::class.java), tile("🎬 Video editor", VideoEditorActivity::class.java)))
        body.addView(row(tile("🎨 Image generator", ImageScreen::class.java), View(this)))
        body.addView(row(tile("📷 Camera", CameraScreen::class.java), tile("🔍 Google Lens", LensScreen::class.java)))
        body.addView(label("WRITE & CALCULATE"))
        body.addView(row(tile("✍️ Drafter", DrafterScreen::class.java), tile("🧮 Calculator", CalculatorScreen::class.java)))
        body.addView(row(tile("💬 Messages", MessagesScreen::class.java), tile("🔔 Notifications", NotificationsScreen::class.java)))
        body.addView(label("PLACES & TIME"))
        body.addView(row(tile("🗺 Map", MapScreen::class.java), tile("🧭 Directions", DirectionsScreen::class.java)))
        body.addView(row(tile("🚕 Book a ride", RideScreen::class.java), tile("📅 Calendar", CalendarScreen::class.java)))
        body.addView(row(tile("⏰ Clock", ClockScreen::class.java), button("🧹 Optimiser") { openOptimiser("") }))
        body.addView(label("PHONE"))
        body.addView(row(button("🧰 All tools") { openTool(toolIntent(ToolsActivity::class.java)) }, View(this)))
    }

    private fun tabBar(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x0000D4FF, 0x5900D4FF, 0x0000D4FF)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(6), dp(2), dp(0)) }
        val hidden = tabHidden(this)
        val shown = tabOrder(this).filter { it !in hidden }
        if (shown.isEmpty()) return wrap
        // a tab that was hidden while it was open: fall back to the first visible one
        if (!editing && ptab !in shown) ptab = shown[0]
        for (i in shown) {
            val (_, ic, name) = TABS[i]
            val on = i == ptab && !editing
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                if (on) background = ash(9)
                setPadding(0, dp(7), 0, dp(6))
                setOnClickListener { resetIdle(); if (ptab != i || editing) { typingMode(false); ptab = i; editing = false; fillPanel(); riseIn() } }
            }
            t.addView(SbIcon(this, ic, if (on) SbIcon.CYAN else SbIcon.GRAY), LinearLayout.LayoutParams(dp(19), dp(19)))
            t.addView(TextView(this).apply { text = name; textSize = if (shown.size > 6) 9.5f else 10.5f; maxLines = 1; gravity = Gravity.CENTER; typeface = fSemi; setTextColor(if (on) txt else mute); setPadding(0, dp(3), 0, 0) })
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(1), 0, dp(1), 0) })
        }
        wrap.addView(bar)
        return wrap
    }

    // ── v19.1: PARTS. Every heading block of a tab can be moved to another tab, reordered and resized (the preview's "Edit sections") ──

    class Blk(val id: String, val label: String, val home: Int, val build: (LinearLayout) -> Unit)

    private fun blocks(am: AudioManager): List<Blk> = listOf(
        Blk("c-top", "Top bar", 0) { smartStrip(it, am) },
        Blk("c-qa", "QUICK APPS", 0) { quickApps(it) },
        Blk("c-ctl", "CONTROLS", 0) { controlsBlk(it) },
        Blk("c-lvl", "LEVELS", 0) { levelsBlk(it, am) },
        Blk("c-nav", "NAVIGATE", 0) { navBlk(it) },
        Blk("c-opt", "OPTIMISER", 0) { optBlk(it) },
        Blk("c-tools", "TOOLS", 0) { toolsBlk(it) },
        Blk("t-qs", "QUICK SETTINGS", 1) { tilesBlk(it) },
        Blk("t-sys", "SYSTEM STATUS", 1) { statusCard(it) },
        Blk("a-list", "Apps list", 2) { appsBlk(it) },
        Blk("f-list", "Files list", 3) { filesBlk(it) },
        Blk("m-mac", "MACROS", 4) { macrosBlk(it) },
        Blk("m-look", "LOOK & GLOW", 4) { lookBlk(it, am) },
        Blk("s-bat", "BATTERY", 5) { batteryBlk(it) },
        Blk("s-sec", "SECURITY AND PRIVACY", 5) { securityBlk(it) },
        Blk("s-orb", "ORB AND UI CUSTOMISER", 5) { orbBlk(it) },
        Blk("s-pa", "PRAGON PA", 5) { paBlk(it) },
        Blk("s-acc", "ACCESSIBILITY", 5) { accBlk(it) },
        Blk("s-not", "NOTIFICATIONS", 5) { notifBlk(it) },
        Blk("s-tmo", "SCREEN TIMEOUT", 5) { timeoutBlk(it) },
        Blk("s-conv", "ACCESSIBILITY & CONVENIENCE", 5) { convBlk(it) },
        Blk("s-prg", "P.R.A.G.O.N", 5) { floatBlk(it) },
        Blk("s-feat", "PRAGON FEATURES", 5) { featBlk(it) },
        Blk("s-rem", "REMINDERS", 5) { remBlk(it) }
    )

    private fun mapPref(key: String): MutableMap<String, String> {
        val m = LinkedHashMap<String, String>()
        (prefs(this).getString(key, "") ?: "").split(',').forEach { e -> val i = e.indexOf('='); if (i > 0) m[e.substring(0, i)] = e.substring(i + 1) }
        return m
    }
    private fun saveMap(key: String, m: Map<String, String>) = prefs(this).edit().putString(key, m.entries.joinToString(",") { it.key + "=" + it.value }).apply()
    private fun partsOrder(all: List<Blk>): List<String> {
        val have = all.map { it.id }
        val kept = (prefs(this).getString("parts", "") ?: "").split(',').filter { it in have }.distinct()
        return kept + have.filter { it !in kept }
    }
    private fun partSec(b: Blk, psec: Map<String, String>): Int = psec[b.id]?.toIntOrNull()?.takeIf { it in 0..5 } ?: b.home
    private fun scaleOf(id: String, sc: Map<String, String>): Int = (sc[id]?.toIntOrNull() ?: 100).coerceIn(70, 130)
    private var openSel: String? = null

    private fun renderSection(body: LinearLayout, sec: Int, am: AudioManager) {
        val all = blocks(am)
        val byId = all.associateBy { it.id }
        val psec = mapPref("psec"); val scl = mapPref("scale")
        filesBox = null; appsBox = null; appGrid = null
        for (id in partsOrder(all)) {
            val bk = byId[id] ?: continue
            if (partSec(bk, psec) != sec) continue
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            try { bk.build(inner) } catch (e: Exception) { continue }
            if (inner.childCount == 0) continue
            val z = scaleOf(id, scl)
            if (z == 100) body.addView(inner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            else body.addView(ZoomBox(this, z / 100f).also { it.addView(inner) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (body.childCount == 0) body.addView(note("Nothing here. Use the pencil (Edit sections) to move parts into this tab."))
    }

    private fun tilesBlk(body: LinearLayout) {
        val tiles = sidebarTiles(this).mapNotNull { QuickCatalog.byId(it) }
        if (tiles.isEmpty()) return
        body.addView(label("QUICK SETTINGS"))
        tiles.chunked(3).forEach { grp ->
            val views = grp.map { tileChip(it) }
            body.addView(row(*views.toTypedArray(), *Array<View>(3 - grp.size) { View(this) }))
        }
        body.addView(TextView(this).apply { text = "Tap to switch. Glowing = on."; textSize = 13f; setTextColor(mute); typeface = fReg; setPadding(dp(6), dp(6), dp(6), dp(2)) })
    }
    private fun appsBlk(body: LinearLayout) {
        val ab = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appsBox = ab
        body.addView(ab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fillApps()
    }
    private fun filesBlk(body: LinearLayout) {
        val fb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        filesBox = fb
        body.addView(fb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fillFiles()
    }

    // ── the Edit sections screen (same look as the preview: TABS with switches, PARTS with a tab picker and a size slider) ──

    private fun editorScreen(col: LinearLayout) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val all = blocks(am)
        val order = tabOrder(this).toMutableList()
        val hidden = tabHidden(this).toMutableSet()
        val m = ViewGroup.LayoutParams.MATCH_PARENT; val w = ViewGroup.LayoutParams.WRAP_CONTENT
        fun rowBg() = GradientDrawable().apply { setColor(0x0A00D4FF); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x3800D4FF) }
        fun mv(icon: String, enabled: Boolean, onClick: () -> Unit): View = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setStroke(dp(1), 0x4D00D4FF) }
            alpha = if (enabled) 1f else 0.3f
            if (enabled) setOnClickListener { resetIdle(); onClick() }
            addView(SbIcon(this@SidebarService, icon), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        }
        fun lpw(wd: Int, mr: Int = 0) = LinearLayout.LayoutParams(wd, w).apply { setMargins(0, 0, mr, 0) }

        val hd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hd.addView(label("EDIT SECTIONS"), LinearLayout.LayoutParams(0, w, 1f))
        hd.addView(mini("Done", true) { editing = false; openSel = null; fillPanel(); riseIn() }, LinearLayout.LayoutParams(dp(58), w).apply { setMargins(0, dp(4), dp(3), 0) })
        col.addView(hd)
        col.addView(TextView(this).apply {
            text = "Show or hide each section, and move it up or down. Changes apply to the bottom tabs."
            textSize = 13f; setTextColor(mute); typeface = fReg; setPadding(dp(4), dp(2), dp(4), dp(6))
        })
        col.addView(label("TABS"))
        order.forEachIndexed { i, id ->
            val on = id !in hidden
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = rowBg(); setPadding(dp(8), dp(9), dp(8), dp(9)); alpha = if (on) 1f else 0.45f }
            r.addView(GripView(this), LinearLayout.LayoutParams(dp(14), dp(24)).apply { setMargins(0, 0, dp(8), 0) })
            r.addView(TextView(this).apply { text = TABS[id].third; textSize = 14f; setTextColor(txt); typeface = fSemi }, LinearLayout.LayoutParams(0, w, 1f))
            r.addView(SbSwitch(this, on).also { sw ->
                sw.setOnClickListener {
                    resetIdle()
                    if (on && order.count { it !in hidden } <= 1) { say("Keep at least one section visible."); return@setOnClickListener }
                    if (on) hidden.add(id) else hidden.remove(id)
                    saveTabs(this@SidebarService, order, hidden); fillPanelKeepScroll()
                }
            }, LinearLayout.LayoutParams(dp(40), dp(22)).apply { setMargins(0, 0, dp(8), 0) })
            r.addView(mv("up", i > 0) { java.util.Collections.swap(order, i, i - 1); saveTabs(this, order, hidden); fillPanelKeepScroll() }, lpw(dp(28), dp(8)).also { it.height = dp(28) })
            r.addView(mv("down", i < order.size - 1) { java.util.Collections.swap(order, i, i + 1); saveTabs(this, order, hidden); fillPanelKeepScroll() }, LinearLayout.LayoutParams(dp(28), dp(28)))
            col.addView(r, LinearLayout.LayoutParams(m, w).apply { setMargins(0, dp(5), 0, dp(5)) })
        }

        col.addView(label("PARTS"))
        val byId = all.associateBy { it.id }
        val psec = mapPref("psec"); val scl = mapPref("scale")
        val ord = partsOrder(all)
        ord.forEachIndexed { j, id ->
            val bk = byId[id] ?: return@forEachIndexed
            val cur = partSec(bk, psec)
            val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0x2400D4FF) } }
            val hr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(9), dp(8), dp(9)) }
            hr.addView(GripView(this), LinearLayout.LayoutParams(dp(14), dp(24)).apply { setMargins(0, 0, dp(8), 0) })
            hr.addView(TextView(this).apply { text = bk.label; textSize = 13f; setTextColor(txt); typeface = fSemi }, LinearLayout.LayoutParams(0, w, 1f).apply { setMargins(0, 0, dp(6), 0) })
            val sel = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(0x1400D4FF); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0x4D00D4FF) }
                setPadding(dp(9), dp(5), dp(7), dp(5))
                setOnClickListener { resetIdle(); openSel = if (openSel == id) null else id; fillPanelKeepScroll() }
            }
            sel.addView(TextView(this).apply { text = TABS[cur].third; textSize = 12.5f; setTextColor(txt); typeface = fSemi })
            sel.addView(SbIcon(this, "chev"), LinearLayout.LayoutParams(dp(14), dp(14)).apply { setMargins(dp(4), 0, 0, 0) })
            hr.addView(sel, lpw(w, dp(8)))
            hr.addView(mv("up", j > 0) { val o = ord.toMutableList(); java.util.Collections.swap(o, j, j - 1); prefs(this).edit().putString("parts", o.joinToString(",")).apply(); fillPanelKeepScroll() }, lpw(dp(28), dp(8)).also { it.height = dp(28) })
            hr.addView(mv("down", j < ord.size - 1) { val o = ord.toMutableList(); java.util.Collections.swap(o, j, j + 1); prefs(this).edit().putString("parts", o.joinToString(",")).apply(); fillPanelKeepScroll() }, LinearLayout.LayoutParams(dp(28), dp(28)))
            g.addView(hr)
            if (openSel == id) {
                val rows = (0..5).toList().chunked(3)
                rows.forEach { grp ->
                    val rw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), dp(4)) }
                    grp.forEach { t ->
                        rw.addView(mini(TABS[t].third, t == cur) {
                            psec[id] = t.toString(); saveMap("psec", psec); openSel = null; fillPanelKeepScroll()
                        }, LinearLayout.LayoutParams(0, w, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
                    }
                    g.addView(rw)
                }
            }
            val v0 = scaleOf(id, scl)
            val sr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), dp(8)) }
            sr.addView(TextView(this).apply { text = "Size"; textSize = 12.5f; setTextColor(mute); typeface = fReg })
            val pct = TextView(this).apply { text = "$v0%"; textSize = 12.5f; setTextColor(cyan); typeface = fSemi; gravity = Gravity.END }
            val sb = android.widget.SeekBar(this).apply {
                max = 12; progress = (v0 - 70) / 5
                progressTintList = android.content.res.ColorStateList.valueOf(cyan); thumbTintList = android.content.res.ColorStateList.valueOf(cyan)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { pct.text = "${70 + p * 5}%"; if (fromUser) resetIdle() }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) { resetIdle() }
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) { scl[id] = (70 + (s?.progress ?: 6) * 5).toString(); saveMap("scale", scl) }
                })
            }
            sr.addView(sb, LinearLayout.LayoutParams(0, w, 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
            sr.addView(pct, LinearLayout.LayoutParams(dp(44), w))
            g.addView(sr)
            col.addView(g, LinearLayout.LayoutParams(m, w).apply { setMargins(0, dp(6), 0, dp(6)) })
        }
        col.addView(button("Reset to default") {
            prefs(this).edit().remove("tab_order").remove("tab_hidden").remove("parts").remove("psec").remove("scale").apply()
            openSel = null; say("Sections reset."); fillPanelKeepScroll()
        }, LinearLayout.LayoutParams(m, w).apply { setMargins(0, dp(12), 0, dp(12)) })
    }

    // ── the Settings tab, block by block like the preview ──

    private fun quickRun(id: String, arg: String, after: () -> Unit = {}) {
        Thread {
            val r = try { CommandExecutor.run(this, JSONObject().put("action", "quick").put("value", "$id:$arg"), fromStandalone = true, user = true) } catch (e: Exception) { null }
            main.post { if (r != null) say(r.msg); after() }
        }.start()
    }
    private fun quickState(id: String): String = try { QuickCatalog.byId(id)?.let { Quick.stateOf(this, it) } ?: "?" } catch (e: Exception) { "?" }

    private fun swBtn(text: String, init: Boolean, onSet: (Boolean) -> Unit): TextView {
        var st = init
        val t = button(text, st) { }
        t.setOnClickListener { resetIdle(); st = !st; styleBtn(t, st); onSet(st) }
        return t
    }
    private fun fullW() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    private fun cap(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(mute); typeface = fReg; setPadding(dp(6), dp(8), dp(6), dp(2)) }

    private fun field(value: String, numeric: Boolean, onChange: (String) -> Unit): View = android.widget.EditText(this).apply {
        textSize = 15f; setSingleLine(true); setText(value); setTextColor(txt); typeface = fReg
        inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER else android.text.InputType.TYPE_CLASS_TEXT
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        background = GradientDrawable().apply { setColor(0xFF06080A.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x6600D4FF) }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                typingMode(true)
                v.post { v.requestFocus(); try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(v, 0) } catch (_: Exception) { } }
            }
            false
        }
        setOnEditorActionListener { _, _, _ -> typingMode(false); true }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { resetIdle(); onChange(s?.toString() ?: "") }
        })
    }

    private fun openAct(i: Intent) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); collapse() } catch (e: Exception) { say("Could not open that.") } }

    private fun batteryBlk(b: LinearLayout) {
        var low = BatteryReminder.lowOn(this); var lv = BatteryReminder.lowLevelsText(this); var tg = BatteryReminder.chargeTarget(this).toString()
        b.addView(label("BATTERY"))
        b.addView(row(swBtn("Low battery warnings", low) { low = it }, View(this)))
        b.addView(cap("Warn at these levels (%, comma separated)"))
        b.addView(field(lv, false) { lv = it }, fullW())
        b.addView(cap("Remind me to unplug at (%, 0 = off)"))
        b.addView(field(if (tg == "0") "" else tg, true) { tg = it }, fullW())
        val sum = note(BatteryReminder.describe(this))
        b.addView(button("Save") {
            typingMode(false)
            BatteryReminder.setLow(this, low, lv.trim()); BatteryReminder.setChargeTarget(this, tg.toIntOrNull() ?: 0)
            sum.text = BatteryReminder.describe(this); say("Battery reminders saved.")
        }, fullW())
        b.addView(sum)
    }
    private fun securityBlk(b: LinearLayout) {
        b.addView(label("SECURITY AND PRIVACY"))
        b.addView(row(swBtn("Privacy Shield", Guard.privacyOn(this)) { Guard.setPrivacy(this, it) }, swBtn("Read my screen", Guard.screenReadingOn(this)) { Guard.setScreenReading(this, it) }))
    }
    private fun orbBlk(b: LinearLayout) {
        b.addView(label("ORB AND UI CUSTOMISER"))
        b.addView(row(button("Open UI customiser") { openAct(Intent(this, CustomiserActivity::class.java)) }))
        // v34: orb size, emblem size and 'emblem follows orb size' (same settings as Settings > Floating orb size)
        b.addView(rangeRow("Orb size", 50, 200, Prefs.orbScale(this)) { Prefs.saveOrbScale(this, it); FloatService.applyOrbSize() })
        b.addView(rangeRow("Emblem size", 30, 100, Prefs.emblemScale(this)) { Prefs.saveEmblemScale(this, it); FloatService.applyEmblem() })
        b.addView(row(swBtn("Emblem follows orb size", Prefs.emblemFollow(this)) { Prefs.saveEmblemFollow(this, it); FloatService.applyEmblem() }))
        b.addView(note("Emblem size stays fixed when you resize the orb, unless 'Emblem follows orb size' is on."))
    }
    /** v34: labelled percent slider (min..max) for the sidebar; onSet runs when the finger lifts. */
    private fun rangeRow(name: String, min: Int, max: Int, start: Int, onSet: (Int) -> Unit): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = card(14); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        l.addView(TextView(this).apply { text = name; textSize = 12f; setTextColor(txt); typeface = fSemi }, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
        val v = TextView(this).apply { text = "$start%"; textSize = 11.5f; setTextColor(mute); typeface = fReg; gravity = Gravity.END }
        val sb = android.widget.SeekBar(this).apply {
            this.max = (max - min) / 5; progress = ((start.coerceIn(min, max) - min) / 5)
            progressTintList = android.content.res.ColorStateList.valueOf(cyan)
            thumbTintList = android.content.res.ColorStateList.valueOf(cyan)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) { v.text = "${min + p * 5}%"; if (fromUser) resetIdle() }
                override fun onStartTrackingTouch(s: android.widget.SeekBar?) { resetIdle() }
                override fun onStopTrackingTouch(s: android.widget.SeekBar?) { onSet(min + (s?.progress ?: 0) * 5) }
            })
        }
        l.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        l.addView(v, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
        return l
    }
    private fun paBlk(b: LinearLayout) {
        b.addView(label("PRAGON PA"))
        b.addView(row(swBtn("PA answers my calls", PragonPA.on(this)) { on ->
            Thread { val r = if (on) PragonPA.enable(this) else PragonPA.disable(this); main.post { say(r.msg); fillPanelKeepScroll() } }.start()
        }, View(this)))
    }
    private fun accBlk(b: LinearLayout) {
        b.addView(label("ACCESSIBILITY"))
        b.addView(row(swBtn("Let Pragon switch accessibility helpers", Guard.a11yFeaturesOn(this)) { Guard.setA11yFeatures(this, it) }))
        b.addView(note("Off by default. Lets you say “turn on talkback”, “turn off live caption” or “turn on text to speech”."))
    }
    private fun segment(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit): View {
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(0x0AFFFFFF); cornerRadius = dp(99).toFloat(); setStroke(dp(1), 0x2400D4FF) }
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        items.forEachIndexed { i, (name, on) ->
            seg.addView(TextView(this).apply {
                text = name; textSize = 12.5f; gravity = Gravity.CENTER; typeface = fSemi; maxLines = 2
                setTextColor(if (on) txt else mute); background = if (on) ash(99) else null
                setPadding(dp(4), dp(10), dp(4), dp(10)); setOnClickListener { resetIdle(); onPick(i) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return seg
    }
    private fun notifBlk(b: LinearLayout) {
        b.addView(label("NOTIFICATIONS"))
        val lock = quickState("lockNotif") == "on"
        b.addView(segment(listOf("Clear all" to false, "Lock screen" to lock, "Settings" to false)) { i ->
            when (i) { 0 -> quickRun("clearall", "run"); 1 -> quickRun("lockNotif", "toggle") { fillPanelKeepScroll() }; else -> quickRun("notifsettings", "run") }
        }, fullW())
    }
    private fun timeoutBlk(b: LinearLayout) {
        b.addView(label("SCREEN TIMEOUT"))
        val cur = quickState("timeout").lowercase().replace(" ", "")
        listOf("15 s" to "15s", "30 s" to "30s", "1 min" to "1m", "5 min" to "5m", "10 min" to "10m").chunked(3).forEach { grp ->
            val v = grp.map { (name, arg) -> button(name, cur == arg) { quickRun("timeout", arg) { fillPanelKeepScroll() } } }
            b.addView(row(*v.toTypedArray(), *Array<View>(3 - v.size) { View(this) }))
        }
    }
    private fun convBlk(b: LinearLayout) {
        b.addView(label("ACCESSIBILITY & CONVENIENCE"))
        fun sw(name: String, id: String) = button(name, quickState(id) == "on") { quickRun(id, "toggle") { fillPanelKeepScroll() } }
        val sizes = QuickCatalog.SIZES
        val size = button("Text size") {
            val i = sizes.indexOf(quickState("fontsize").lowercase())
            quickRun("fontsize", sizes[(i + 1) % sizes.size]) { fillPanelKeepScroll() }
        }
        b.addView(row(sw("Screen reader", "talkback"), sw("Captions", "caption")))
        b.addView(row(size, sw("Auto-rotate", "rotate")))
    }
    private fun floatBlk(b: LinearLayout) {
        b.addView(label("P.R.A.G.O.N"))
        val on = FloatService.running
        val fb = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xFF06080A.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), if (on) 0xFF9AA7A0.toInt() else 0x2EFFFFFF) }
            setPadding(dp(8), dp(12), dp(8), dp(12))
            contentDescription = "Float button"
            setOnClickListener {
                resetIdle()
                if (FloatService.running) { FloatMode.stop(this@SidebarService); say("Float mode off.") }
                else if (!Perm.has(this@SidebarService, android.Manifest.permission.RECORD_AUDIO)) say("Open Pragon once and allow the microphone first.")
                else try { FloatMode.start(this@SidebarService, active = false); say("Float mode on.") } catch (e: Exception) { say("Float mode couldn't start.") }
                main.postDelayed({ fillPanelKeepScroll() }, 600)
            }
        }
        fb.addView(ImageView(this).apply { setImageResource(R.drawable.pg_logo); scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        b.addView(fb, fullW())
        b.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x0000D4FF, 0x4000D4FF, 0x0000D4FF)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(3), dp(10), dp(3), dp(10)) })
        val grade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF00AAFF.toInt(), 0xFF00D4FF.toInt(), 0xFF955AFF.toInt(), 0xFFF853AC.toInt())).apply { cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x66FFFFFF) }
            contentDescription = "Colour grade"
            setOnClickListener { resetIdle(); openAct(Intent(this@SidebarService, CustomiserActivity::class.java)) }
        }
        b.addView(grade, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }
    private fun featBlk(b: LinearLayout) {
        b.addView(label("PRAGON FEATURES"))
        b.addView(row(swBtn("Pragon Live voice", Prefs.liveOn(this)) { Prefs.setLiveOn(this, it) }, swBtn("Let me interrupt Pragon while it talks", Prefs.liveBargeIn(this)) { Prefs.setLiveBargeIn(this, it) }))
        b.addView(row(swBtn("Media-friendly listening", Guard.mediaFriendly(this)) { Guard.setMediaFriendly(this, it) }, View(this)))
        b.addView(row(swBtn("Typer mode finds the text box by itself", Guard.typerAuto(this)) { Guard.setTyperAuto(this, it) }))
    }
    private fun remBlk(b: LinearLayout) {
        b.addView(label("REMINDERS"))
        val rem = try { Reminders.exactAllowed(this) } catch (e: Exception) { false }
        val cal = Perm.has(this, android.Manifest.permission.READ_CALENDAR)
        val r1 = button("Reminders", rem) {
            if (!rem) Thread { val r = Reminders.act(this, JSONObject().put("action", "allow_exact")); main.post { say(r.msg); fillPanelKeepScroll() } }.start() else say("Reminders are allowed.")
        }
        val r2 = button("Calendar", cal) {
            if (!cal) { Perm.request(this, android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.READ_CONTACTS); collapse() } else say("Calendar access is allowed.")
        }
        b.addView(row(r1, r2))
    }

    /** QUICK APPS: your favourites (most used first), 10 slots, with Edit to remove. */
    private fun quickApps(body: LinearLayout) {
        val pm = packageManager
        val uses = uses(this)
        val pkgs = Guard.sidebarApps(this).filter { pm.getLaunchIntentForPackage(it) != null && Guard.canOpen(this, it) }
            .sortedByDescending { uses[it] ?: 0 }.take(10)
        val hd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hd.addView(label("QUICK APPS · ${pkgs.size}/10"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hd.addView(mini(if (qaEdit) "Done" else "Edit", qaEdit) { qaEdit = !qaEdit; fillPanelKeepScroll() }, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), dp(3), 0) })
        body.addView(hd)
        val cells = ArrayList<View>()
        for (pk in pkgs) cells.add(quickCell(pk))
        while (cells.size < 10) cells.add(addSlot())
        cells.chunked(5).forEach { r ->
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEach { l.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            body.addView(l)
        }
    }

    private fun quickCell(pk: String): View {
        val pm = packageManager
        val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pk, 0)).toString() } catch (e: Exception) { pk }
        val f = FrameLayout(this)
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(1), dp(4), dp(1), dp(4)) }
        cell.addView(ImageView(this).apply { try { setImageDrawable(pm.getApplicationIcon(pk)) } catch (e: Exception) { } }, LinearLayout.LayoutParams(dp(40), dp(40)))
        cell.addView(TextView(this).apply { text = name; textSize = 10.5f; maxLines = 1; gravity = Gravity.CENTER; setTextColor(mute); typeface = fReg; ellipsize = android.text.TextUtils.TruncateAt.END })
        f.addView(cell, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (qaEdit) f.addView(TextView(this).apply {
            text = "×"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFEF4444.toInt()) }
        }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END))
        f.setOnClickListener {
            resetIdle()
            if (qaEdit) {
                val cur = Guard.sidebarApps(this).toMutableSet(); cur.remove(pk); Guard.setSidebarApps(this, cur)
                say("$name removed from favourites."); fillPanelKeepScroll()
            } else launchApp(pk, name)
        }
        return f
    }

    private fun addSlot(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(1), dp(4), dp(1), dp(4))
        addView(TextView(this@SidebarService).apply {
            text = "+"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(cyan)
            background = GradientDrawable().apply { setColor(0x00000000); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x6600D4FF, dp(4).toFloat(), dp(3).toFloat()) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(TextView(this@SidebarService).apply { text = "Add"; textSize = 10.5f; gravity = Gravity.CENTER; setTextColor(mute); typeface = fReg })
        setOnClickListener { resetIdle(); openTab(2); fillPanel() }
    }

    private fun slider(icon: String, start: Int, onSet: (Int) -> Unit): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = card(14); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        l.addView(SbIcon(this, icon), LinearLayout.LayoutParams(dp(22), dp(22)))
        val v = TextView(this).apply { text = "$start"; textSize = 11.5f; setTextColor(mute); typeface = fReg; gravity = Gravity.END }
        val sb = android.widget.SeekBar(this).apply {
            max = 100; progress = start.coerceIn(0, 100)
            progressTintList = android.content.res.ColorStateList.valueOf(cyan)
            thumbTintList = android.content.res.ColorStateList.valueOf(cyan)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) { v.text = "$p"; if (fromUser) resetIdle() }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { resetIdle() }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { onSet(sb?.progress ?: 0) }
            })
        }
        l.addView(sb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        l.addView(v, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
        return l
    }

    private fun memInfo(): android.app.ActivityManager.MemoryInfo {
        val mi = android.app.ActivityManager.MemoryInfo()
        try { (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi) } catch (e: Exception) { }
        return mi
    }
    private fun storageUsed(): Pair<Long, Long> = try {
        val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val total = st.blockCountLong * st.blockSizeLong
        (total - st.availableBlocksLong * st.blockSizeLong) to total
    } catch (e: Exception) { 0L to 0L }
    private fun pctOf(a: Long, b: Long) = if (b > 0) (a * 100 / b).toInt().coerceIn(0, 100) else 0

    private fun meter(name: String, pct: Int, right: String, warn: Boolean): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        l.addView(TextView(this).apply { text = name; textSize = 12.5f; setTextColor(txt); typeface = fSemi }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        val bar = LinearLayout(this).apply { background = GradientDrawable().apply { setColor(0x1AFFFFFF); cornerRadius = dp(3).toFloat() } }
        val fillCol = if (warn) intArrayOf(0xFF00D4FF.toInt(), 0xFFFF6B4A.toInt()) else intArrayOf(0xFF00D4FF.toInt(), 0xFF00D4FF.toInt())
        bar.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, fillCol).apply { cornerRadius = dp(3).toFloat() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct.coerceIn(1, 100).toFloat()))
        bar.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (100 - pct).coerceAtLeast(0).toFloat()))
        l.addView(bar, LinearLayout.LayoutParams(0, dp(6), 1f).apply { setMargins(dp(6), 0, dp(8), 0) })
        l.addView(TextView(this).apply { text = right; textSize = 11.5f; setTextColor(mute); typeface = fReg; gravity = Gravity.END }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
        return l
    }

    private fun optimiserCard(body: LinearLayout, tiles: List<View> = emptyList()) {
        val mi = memInfo(); val (su, st) = storageUsed()
        val mp = pctOf(mi.totalMem - mi.availMem, mi.totalMem); val sp = pctOf(su, st)
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = card(16); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        c.addView(meter("Memory", mp, Dock.size(mi.totalMem - mi.availMem) + "/" + Dock.size(mi.totalMem), mp > 70))
        c.addView(meter("Storage", sp, Dock.size(su) + "/" + Dock.size(st), sp > 85))
        if (tiles.isNotEmpty()) c.addView(row(*tiles.toTypedArray()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, 0) })
        body.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    /** SYSTEM STATUS: four rings (battery, memory, storage, temperature). */
    private fun statusCard(body: LinearLayout) {
        body.addView(label("SYSTEM STATUS"))
        val bi = try { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: Exception) { null }
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val bat = if (lvl >= 0 && scale > 0) lvl * 100 / scale else 0
        val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
        val mi = memInfo(); val (su, st) = storageUsed()
        val mp = pctOf(mi.totalMem - mi.availMem, mi.totalMem); val sp = pctOf(su, st)
        val c = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = card(16); setPadding(dp(8), dp(12), dp(8), dp(10)) }
        fun ring(name: String, pct: Int, col: Int, t: String) {
            val col1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col1.addView(RingView(this, pct, col, t, txt, fSemi), LinearLayout.LayoutParams(dp(54), dp(54)))
            col1.addView(TextView(this).apply { text = name; textSize = 11.5f; setTextColor(mute); typeface = fReg; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
            c.addView(col1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        ring("Battery", bat, 0xFF33FFB0.toInt(), "$bat%")
        ring("Memory", mp, if (mp > 70) 0xFFFFB84D.toInt() else 0xFF00D4FF.toInt(), "$mp%")
        ring("Storage", sp, 0xFF00D4FF.toInt(), "$sp%")
        ring("Temp", (temp * 2).coerceIn(0, 100), 0xFF2D96FF.toInt(), "$temp°")
        body.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    // ── v16: LOOK & GLOW ─────────────────────────────────────────────────

    private fun styleBtn(tv: TextView, hot: Boolean) {
        tv.setTextColor(if (hot) onTxt else txt)
        tv.background = if (hot) teal(12) else ash(12)
    }

    /** An on/off button that restyles itself in place (so the panel does not jump back to the top). */
    private fun toggleBtn(text: String, key: String, glow: Boolean = true, after: () -> Unit = {}): TextView {
        lateinit var tv: TextView
        tv = button(text, Glow.get(this, key)) {
            Glow.set(this, key, !Glow.get(this, key))
            styleBtn(tv, Glow.get(this, key))
            if (glow) applyGlow()
            after()
            // some switches can refuse (no microphone permission, no accessibility): show what really happened
            main.postDelayed({ styleBtn(tv, Glow.get(this, key)) }, 500)
        }
        return tv
    }

    private fun swatchRow(): View {
        val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val dots = ArrayList<View>()
        fun paintDots() {
            dots.forEachIndexed { i, d ->
                val c = Glow.COLOURS[i]
                d.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(android.graphics.Color.rgb(c[0], c[1], c[2]))
                    setStroke(dp(2), if (i == Glow.colourIndex(this@SidebarService)) 0xFFFFFFFF.toInt() else 0x44FFFFFF)
                }
            }
        }
        Glow.COLOURS.forEachIndexed { i, _ ->
            val d = View(this)
            dots.add(d)
            d.setOnClickListener { resetIdle(); Glow.setColourIndex(this, i); paintDots(); applyGlow() }
            rowL.addView(d, LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(10), dp(6), dp(10), dp(6)) })
        }
        paintDots()
        return rowL
    }

    private fun fillPanelKeepScroll() {
        val y = bodyScroll?.scrollY ?: 0
        fillPanel()
        bodyScroll?.post { bodyScroll?.scrollTo(0, y) }
    }

    /** Moves the whole sidebar (handle and panel) to the other edge and opens it again. */
    private fun switchSide() {
        setLeft(this, !leftSide(this))
        expanded = false
        main.removeCallbacks(idleClose)
        root?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        root = null; handle = null; panel = null
        try { build(viaA11yNow); expand() } catch (e: Throwable) { shutdown() }
    }

    /** Adds, updates or removes the glow window to match the saved switches. Safe to call any time. */
    private fun applyGlow() {
        main.post {
            if (!running) return@post
            if (!Glow.any(this)) { removeGlowView(); stopViz(); stopSampler(); return@post }
            ensureGlowView()
            glowView?.let {
                it.border = Glow.get(this, Glow.BORDER); it.haze = Glow.get(this, Glow.HAZE); it.ambient = Glow.get(this, Glow.AMBIENT)
            }
            if (Glow.get(this, Glow.SOUND)) startViz() else { stopViz(); vizLevel = 0f }
            if (Glow.get(this, Glow.MATCH)) startSampler() else stopSampler()
            kickGlow()
        }
    }

    private fun stopGlow() { removeGlowView(); stopViz(); stopSampler(); try { sampleExec?.shutdownNow() } catch (_: Exception) { }; sampleExec = null }

    private fun ensureGlowView() {
        if (glowView != null) return
        val gv = GlowView(this)
        val b = Glow.base(this)
        for (i in 0..2) glowCur[i] = b[i].toFloat()
        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type(viaA11yNow),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try { wm.addView(gv, p); glowView = gv }
        catch (e: Throwable) {
            try {
                if (viaA11yNow && Settings.canDrawOverlays(this)) {
                    p.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    wm.addView(gv, p); glowView = gv
                }
            } catch (_: Throwable) { }
        }
    }

    private fun removeGlowView() {
        main.removeCallbacks(glowTick)
        glowView?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        glowView = null
    }

    private fun kickGlow() { main.removeCallbacks(glowTick); main.post(glowTick) }

    /** One animation step: eases the sound level and the colour, then redraws. Stops by itself when nothing moves. */
    private val glowTick = object : Runnable {
        override fun run() {
            val gv = glowView ?: return
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val snd = Glow.get(this@SidebarService, Glow.SOUND) && viz != null
            val tl = if (snd) vizLevel else 0f
            glowLevel += (tl - glowLevel) * (if (tl > glowLevel) 0.5f else 0.12f)
            val s = sampled
            val follow = Glow.get(this@SidebarService, Glow.MATCH) && am.isMusicActive && s != null && System.currentTimeMillis() - sampledAt < 3000
            val tgt = if (follow) s!! else Glow.base(this@SidebarService)
            var moving = false
            for (i in 0..2) {
                val d = tgt[i] - glowCur[i]
                if (kotlin.math.abs(d) > 1f) moving = true
                glowCur[i] += d * 0.12f
            }
            gv.cr = glowCur[0]; gv.cg = glowCur[1]; gv.cb = glowCur[2]
            gv.level = glowLevel
            gv.phase += 0.13f
            gv.invalidate()
            if (gv.haze || snd || follow || moving || glowLevel > 0.01f) main.postDelayed(this, 40)
        }
    }

    // sound reactive: taps the phone's audio output (needs the microphone permission Pragon already asks for)
    private fun startViz() {
        if (viz != null) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Glow.set(this, Glow.SOUND, false); say("Allow the microphone for Pragon first, then turn Sound reactive on again."); return
        }
        try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[0]
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, wf: ByteArray?, sr: Int) {
                    if (wf == null || wf.isEmpty()) return
                    var sum = 0.0
                    for (b in wf) { val d = (b.toInt() and 0xFF) - 128; sum += d * d }
                    vizLevel = (kotlin.math.sqrt(sum / wf.size).toFloat() / 40f).coerceIn(0f, 1f)
                }
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, sr: Int) { }
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            v.enabled = true
            viz = v
        } catch (e: Throwable) {
            viz = null; Glow.set(this, Glow.SOUND, false)
            say("Sound reactive is not available: Android would not let Pragon listen to the audio.")
        }
    }

    private fun stopViz() {
        viz?.let { try { it.enabled = false; it.release() } catch (_: Throwable) { } }
        viz = null; vizLevel = 0f
    }

    // match video colour: while something plays, the glow takes the average colour of the middle of the screen
    private fun startSampler() {
        if (sampling) return
        if (Build.VERSION.SDK_INT < 30 || PragonAccessibilityService.instance == null) {
            Glow.set(this, Glow.MATCH, false)
            say(if (Build.VERSION.SDK_INT < 30) "Match video colour needs Android 11 or newer." else "Turn on Pragon accessibility to match the video colour.")
            return
        }
        sampling = true
        if (sampleExec == null) sampleExec = java.util.concurrent.Executors.newSingleThreadExecutor()
        main.post(sampleLoop)
    }

    private fun stopSampler() { sampling = false; main.removeCallbacks(sampleLoop); sampled = null }

    private val sampleLoop = object : Runnable {
        override fun run() {
            if (!sampling) return
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.isMusicActive) grabColour()
            main.postDelayed(this, 700)
        }
    }

    @Suppress("NewApi")
    private fun grabColour() {
        val svc = PragonAccessibilityService.instance ?: return
        val ex = sampleExec ?: return
        try {
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, ex, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val small: Bitmap? = hw?.let {
                            try { Bitmap.createScaledBitmap(it, 18, 32, true) }
                            catch (e: Exception) { it.copy(Bitmap.Config.ARGB_8888, false)?.let { b -> Bitmap.createScaledBitmap(b, 18, 32, true) } }
                        }
                        if (small != null) {
                            // only the middle of the screen: the glow lives on the edges and must not feed back into itself
                            var r = 0L; var g = 0L; var b = 0L; var n = 0
                            for (y in 6..25) for (x in 3..14) {
                                val px = small.getPixel(x, y)
                                val rr = (px shr 16) and 255; val gg = (px shr 8) and 255; val bb = px and 255
                                if (maxOf(rr, gg, bb) > 40) { r += rr; g += gg; b += bb; n++ }
                            }
                            if (n > 8) {
                                val mr = r / n.toFloat(); val mg = g / n.toFloat(); val mb = b / n.toFloat()
                                val hi = maxOf(mr, mg, mb).coerceAtLeast(1f); val lo = minOf(mr, mg, mb)
                                // grey or white screens carry no colour: keep the chosen colour instead
                                sampled = if (hi - lo < 25f) null else { val k = 255f / hi; intArrayOf((mr * k).toInt(), (mg * k).toInt(), (mb * k).toInt()) }
                                sampledAt = System.currentTimeMillis()
                                main.post { kickGlow() }
                            }
                        }
                    } catch (e: Exception) { }
                    try { result.hardwareBuffer.close() } catch (e: Exception) { }
                }
                override fun onFailure(errorCode: Int) { }
            })
        } catch (e: Throwable) { }
    }

    // ── v15: Optimiser shortcuts ─────────────────────────────────────────

    /** v18: Tools hub (map, calendar, camera, calculator, lens, messages, clock). */
    private fun openTools() {
        try {
            startActivity(Intent(this, ToolsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            collapse()
        } catch (e: Exception) { say("Could not open Tools.") }
    }

    private fun openOptimiser(tab: String) {
        try {
            startActivity(Intent(this, OptimiserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).apply { if (tab.isNotEmpty()) putExtra("tab", tab) })
            collapse()
        } catch (e: Exception) { say("Could not open the optimiser.") }
    }

    private fun boostNow() {
        say("Boosting…")
        Thread {
            val msg = try {
                val r = JSONObject(Optimiser.boost(applicationContext))
                val gain = r.optLong("after") - r.optLong("before")
                if (gain > 1048576) "Freed ${Dock.size(gain)} of memory." else "Boosted. Nothing much was running."
            } catch (e: Exception) { "Boost failed: ${e.message}" }
            main.post { say(msg) }
        }.start()
    }

    // ── v15: APPS (Favourites and All apps) ──────────────────────────────

    class AppEntry(val pkg: String, val label: String, val installed: Long)

    private fun appsTab() = prefs(this).getString("atab", "fav") ?: "fav"
    private fun appSort(): Pair<String, Boolean> {
        val v = prefs(this).getString("asort", "name:asc") ?: "name:asc"
        return v.substringBefore(':') to (v.substringAfter(':') != "desc")
    }

    /** Lets the search box take the keyboard (the panel is normally not focusable so it never steals focus from the app underneath). */
    private fun typingMode(on: Boolean) {
        val params = lp ?: return
        val has = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        if (on == has) return
        params.flags = if (on) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { wm.updateViewLayout(root, params) } catch (_: Exception) { }
        if (!on) try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(root?.windowToken, 0) } catch (_: Exception) { }
    }

    private fun loadAllApps(): List<AppEntry> {
        appCache?.let { if (System.currentTimeMillis() - appCacheAt < 60_000) return it }
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            val pk = ri.activityInfo.packageName
            if (pk == packageName || !seen.add(pk)) continue
            if (!Guard.canOpen(this, pk)) continue
            val name = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pk }
            val inst = try { pm.getPackageInfo(pk, 0).firstInstallTime } catch (_: Exception) { 0L }
            out.add(AppEntry(pk, name, inst))
        }
        appCache = out; appCacheAt = System.currentTimeMillis()
        return out
    }

    private fun launchApp(pk: String, name: String) {
        resetIdle(); countOpen(this, pk)
        val i = packageManager.getLaunchIntentForPackage(pk)
        if (i != null) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); collapse(); return } catch (_: Exception) { }
        }
        cmd("open_app", name, true)
    }

    private fun toggleFav(pk: String, name: String) {
        val cur = Guard.sidebarApps(this).toMutableSet()
        if (!cur.add(pk)) { cur.remove(pk); say("$name removed from favourites.") } else say("$name added to favourites.")
        Guard.setSidebarApps(this, cur)
        fillApps()
    }

    private fun appCell(pk: String, name: String, fav: Boolean): View {
        val pm = packageManager
        val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(2), dp(4), dp(2), dp(4)) }
        val ic = ImageView(this).apply { try { setImageDrawable(pm.getApplicationIcon(pk)) } catch (e: Exception) { } }
        cell.addView(ic, LinearLayout.LayoutParams(dp(40), dp(40)))
        cell.addView(TextView(this).apply { text = (if (fav && appsTab() == "all") "★ " else "") + name; textSize = 10.5f; maxLines = 1; gravity = Gravity.CENTER; setTextColor(mute); typeface = fReg; ellipsize = android.text.TextUtils.TruncateAt.END })
        cell.setOnClickListener { launchApp(pk, name) }
        cell.setOnLongClickListener { toggleFav(pk, name); true }
        return cell
    }

    private fun appRows(into: LinearLayout, apps: List<Pair<String, String>>, favs: Set<String>) {
        apps.chunked(4).forEach { rowPk ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((pk, name) in rowPk) r.addView(appCell(pk, name, pk in favs), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            repeat(4 - rowPk.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            into.addView(r)
        }
    }

    fun fillApps() {
        val box = appsBox ?: return
        appsGen++
        box.removeAllViews()
        val tab = appsTab()
        box.addView(label("APPS"))
        box.addView(row(
            mini("★ Favourites", tab == "fav") { prefs(this).edit().putString("atab", "fav").apply(); appQuery = ""; typingMode(false); fillApps() },
            mini("▦ All apps", tab == "all") { prefs(this).edit().putString("atab", "all").apply(); appLimit = 24; fillApps() }))
        if (tab == "fav") { favGrid(box); return }

        // All apps: search, sort chips and the grid
        val search = android.widget.EditText(this).apply {
            hint = "Search apps"; textSize = 13f; setSingleLine(true); setText(appQuery)
            setTextColor(txt); setHintTextColor(0xFF6B7686.toInt()); typeface = fReg
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0x1AFFFFFF) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    typingMode(true)
                    v.post { v.requestFocus(); try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(v, 0) } catch (_: Exception) { } }
                }
                false
            }
            setOnEditorActionListener { _, _, _ -> typingMode(false); true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { resetIdle(); appQuery = s?.toString() ?: ""; appLimit = 24; fillAppGrid() }
            })
        }
        box.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        val (key, asc) = appSort()
        val chips = listOf("name" to "Name", "used" to "Used", "new" to "Newest").map { (k, t) ->
            val on = key == k
            mini(if (on) t + (if (asc) " ↑" else " ↓") else t, on) {
                val nextAsc = if (on) !asc else (k == "name")        // Name starts A→Z; Used and Newest start with the most first
                prefs(this).edit().putString("asort", k + ":" + (if (nextAsc) "asc" else "desc")).apply()
                fillApps()
            }
        }
        box.addView(row(*chips.toTypedArray()))
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appGrid = g
        box.addView(g)
        fillAppGrid()
    }

    private fun favGrid(box: LinearLayout) {
        val pm = packageManager
        val uses = uses(this)
        var pkgs = Guard.sidebarApps(this)
        if (pkgs.isEmpty() && Guard.allowlistOn(this)) pkgs = Guard.allowedApps(this).toList()
        pkgs = pkgs.filter { pm.getLaunchIntentForPackage(it) != null && Guard.canOpen(this, it) }
            .sortedByDescending { uses[it] ?: 0 }.take(16)
        if (pkgs.isEmpty()) { box.addView(note("No favourites yet. Open All apps and press and hold an app to add it here.")); return }
        val names = pkgs.map { pk -> pk to (try { pm.getApplicationLabel(pm.getApplicationInfo(pk, 0)).toString() } catch (e: Exception) { pk }) }
        appRows(box, names, pkgs.toSet())
        box.addView(note("Press and hold an app to remove it from favourites."))
    }

    private fun fillAppGrid() {
        val g = appGrid ?: return
        g.removeAllViews()
        val gen = appsGen
        val stale = appCache == null || System.currentTimeMillis() - appCacheAt >= 60_000
        val wait = if (stale) note("Loading apps…").also { g.addView(it) } else null
        Thread {
            val all = try { loadAllApps() } catch (_: Exception) { emptyList() }
            main.post {
                if (gen != appsGen || appGrid !== g || !expanded) return@post
                g.removeAllViews()
                wait?.visibility = View.GONE
                val q = appQuery.trim().lowercase()
                val (key, asc) = appSort()
                val uses = uses(this)
                var list = all.filter { q.isEmpty() || it.label.lowercase().contains(q) }
                val cmp: Comparator<AppEntry> = when (key) {
                    "used" -> compareBy<AppEntry> { uses[it.pkg] ?: 0 }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    "new" -> compareBy<AppEntry> { it.installed }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                    else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
                }
                list = list.sortedWith(if (asc) cmp else cmp.reversed())
                if (list.isEmpty()) { g.addView(note(if (q.isEmpty()) "No apps found." else "No app matches \"$appQuery\".")); return@post }
                val favs = Guard.sidebarApps(this).toSet()
                appRows(g, list.take(appLimit).map { it.pkg to it.label }, favs)
                if (list.size > appLimit) g.addView(mini("Show more (${list.size - appLimit})") { appLimit += 24; fillAppGrid() },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(6), dp(3), dp(3)) })
                g.addView(note("${list.size} apps · press and hold one to add or remove it from Favourites."))
            }
        }.start()
    }

    // ── v14: FILES (File Dock, Doc box, Quick files) ─────────────────────

    fun setFilesTab(t: String) { prefs(this).edit().putString("ftab", t).apply() }
    private fun filesTab() = prefs(this).getString("ftab", "") ?: ""
    private fun docFilter() = prefs(this).getString("dfilter", "all") ?: "all"

    private fun mini(text: String, hot: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 12.5f; gravity = Gravity.CENTER; maxLines = 1
        setTextColor(if (hot) onTxt else 0xFFD3DAE4.toInt()); typeface = fSemi
        background = if (hot) teal(10) else ash(10)
        setPadding(dp(2), dp(7), dp(2), dp(7))
        setOnClickListener { resetIdle(); onClick() }
        shine(this)
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 12.5f; setTextColor(mute); typeface = fReg; setPadding(dp(6), dp(6), dp(6), dp(6)) }

    private fun fmtTime(ms: Long): String {
        if (ms <= 0) return ""
        val c = Calendar.getInstance(); val now = Calendar.getInstance(); c.timeInMillis = ms
        val hm = String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val d = dayKey(ms); val today = dayKey(now.timeInMillis)
        return when {
            d == today -> "Today $hm"
            d == today - 1 -> "Yesterday $hm"
            else -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}" + (if (c.get(Calendar.YEAR) != now.get(Calendar.YEAR)) " ${c.get(Calendar.YEAR)}" else "") + ", $hm"
        }
    }
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private fun dayKey(ms: Long): Long { val c = Calendar.getInstance(); c.timeInMillis = ms; return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR) }
    private fun dayLabel(ms: Long): String {
        val t = dayKey(System.currentTimeMillis()); val d = dayKey(ms)
        val c = Calendar.getInstance(); c.timeInMillis = ms
        return if (d == t) "TODAY" else if (d == t - 1) "YESTERDAY" else "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)].uppercase()} ${c.get(Calendar.YEAR)}"
    }

    fun fillFiles() {
        val box = filesBox ?: return
        filesGen++
        box.removeAllViews()
        val tab = filesTab()
        box.addView(label("FILES"))
        fun tabBtn(id: String, text: String) = mini(text, tab == id) { setFilesTab(if (tab == id) "" else id); openEntry = null; fillFiles() }
        box.addView(row(tabBtn("dock", "📥 Dock " + Dock.items(this).size.let { if (it > 0) "($it)" else "" }), tabBtn("docs", "📄 Docs"), tabBtn("quick", "⭐ Quick")))
        when (tab) { "dock" -> dockTab(box); "docs" -> docsTab(box); "quick" -> quickTab(box) }
    }

    /** Date / Name / Size / Type chips. The chip that is on shows ↑ or ↓; tap it again to flip. */
    private fun sortBar(list: String): View {
        val cur = Dock.sort(this, list)
        val chips = listOf("date" to "Date", "name" to "Name", "size" to "Size", "type" to "Type").map { (k, t) ->
            val on = cur.key == k
            mini(if (on) t + (if (cur.asc) " ↑" else " ↓") else t, on) { Dock.setSort(this, list, k); fillFiles() }
        }
        return row(*chips.toTypedArray())
    }

    private fun dockTab(box: LinearLayout) {
        box.addView(row(
            mini("📋 Paste") { val e = Dock.addFromClipboard(this); say(e ?: "Added to the dock."); fillFiles() },
            mini("🗑 Clear all") { Dock.clear(this); openEntry = null; say("Dock emptied."); fillFiles() }))
        box.addView(dropZone())
        val items = Dock.items(this)
        if (items.isEmpty()) {
            box.addView(note("Drag text, links, photos or files here, or use Share > Pragon File Dock in any app. Then press and hold an item and drop it into another app (Notes, Gmail, WhatsApp). Tap an item for Share / Copy / Remove."))
            return
        }
        box.addView(sortBar("dock"))
        for (e in Dock.sorted(items, Dock.sort(this, "dock"))) {
            box.addView(entryView(e, "dock"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            if (openEntry == e.id) box.addView(row(
                mini("↗ Share") { if (Dock.share(this, e)) collapse() else say("Could not share it.") },
                mini(if (e.kind == "text" || e.kind == "link") "⧉ Copy" else "▶ Open") {
                    if (e.kind == "text" || e.kind == "link") { Dock.copyText(this, e.text ?: e.name); say("Copied.") }
                    else if (Dock.open(this, e)) collapse() else say("No app can open this.")
                },
                mini("✕ Remove") { Dock.remove(this, e.id); openEntry = null; fillFiles() }))
        }
    }

    private fun dropZone() = TextView(this).apply {
        text = "⬇ Drop text, links or files here"; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(0xFF8FD8EE.toInt()); setPadding(dp(8), dp(14), dp(8), dp(14))
        background = GradientDrawable().apply { setColor(0x2200D4FF); setStroke(dp(1), 0x8800D4FF.toInt(), dp(5).toFloat(), dp(4).toFloat()); cornerRadius = dp(12).toFloat() }
        setOnDragListener { v, ev ->
            when (ev.action) {
                DragEvent.ACTION_DRAG_STARTED -> ev.localState != "pragon_dock"
                DragEvent.ACTION_DRAG_ENTERED -> { v.alpha = 0.55f; true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
                DragEvent.ACTION_DROP -> {
                    v.alpha = 1f
                    val clip = ev.clipData
                    Thread {
                        val err = Dock.addClip(applicationContext, clip)
                        main.post { say(err ?: "Added to the dock."); if (expanded) fillFiles() }
                    }.start()
                    true
                }
                else -> true
            }
        }
    }

    private fun docsTab(box: LinearLayout) {
        if (!Dock.hasFileAccess()) {
            box.addView(note("The Doc box lists your downloaded documents. It needs Android's \"All files access\" once."))
            box.addView(button("Allow in My Files") { startActivity(Intent(this, FilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); collapse() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            return
        }
        val cur = docFilter()
        Dock.GROUPS.entries.chunked(4).forEach { grp ->
            box.addView(row(*grp.map { g -> mini(g.value, cur == g.key) { prefs(this).edit().putString("dfilter", g.key).apply(); docLimit = 12; fillFiles() } }.toTypedArray()))
        }
        box.addView(sortBar("docs"))
        val wait = note("Loading…")
        box.addView(wait)
        val gen = filesGen
        Thread {
            val all = Dock.docs(applicationContext, cur)
            main.post {
                if (gen != filesGen || filesBox == null || !expanded) return@post
                wait.visibility = View.GONE
                renderDocs(box, all, Dock.sort(this, "docs"))
            }
        }.start()
    }

    private fun renderDocs(box: LinearLayout, all: List<Dock.FEntry>, s: Dock.Sort) {
        if (all.isEmpty()) { box.addView(note("No files of this kind yet.")); return }
        val list = Dock.sorted(all, s)
        var lastDay = -1L
        for (e in list.take(docLimit)) {
            if (s.key == "date") {
                val d = dayKey(e.time)
                if (d != lastDay) { lastDay = d; box.addView(label(dayLabel(e.time), 10f, mute, false)) }
            }
            box.addView(entryView(e, "docs"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        if (list.size > docLimit) box.addView(mini("Show more (${list.size - docLimit})") { docLimit += 20; fillFiles() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(6), dp(3), dp(3)) })
    }

    private fun quickTab(box: LinearLayout) {
        val items = Dock.quick(this)
        if (items.isEmpty()) {
            box.addView(note("Files you use a lot. Tap ☆ on a file in Docs, or choose Pin to Quick files in My Files. Files you open 3 or more times through Pragon appear here on their own."))
            return
        }
        box.addView(sortBar("quick"))
        for (e in Dock.sorted(items, Dock.sort(this, "quick")))
            box.addView(entryView(e, "quick"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
    }

    /** One file / dock item: badge or thumbnail, name, date-time and size. Tap opens (dock: shows its buttons); press and hold drags it into another app. */
    private fun entryView(e: Dock.FEntry, src: String): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = card(12)
            setPadding(dp(6), dp(5), dp(2), dp(5))
        }
        val lead = FrameLayout(this)
        lead.addView(TextView(this).apply {
            text = Dock.badge(e); textSize = 9.5f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(Dock.badgeColor(e)); cornerRadius = dp(8).toFloat() }
        }, FrameLayout.LayoutParams(dp(36), dp(36)))
        if (e.path != null && (e.kind == "image" || e.ext in Dock.IMG)) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE; clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } } }
            lead.addView(iv, FrameLayout.LayoutParams(dp(36), dp(36)))
            val path = e.path!!
            val hit = thumbCache.get(path)
            if (hit != null) { iv.setImageBitmap(hit); iv.visibility = View.VISIBLE }
            else Thread {
                val b = try { thumb(path) } catch (_: Throwable) { null }
                if (b != null) { thumbCache.put(path, b); main.post { iv.setImageBitmap(b); iv.visibility = View.VISIBLE } }
            }.start()
        }
        line.addView(lead, LinearLayout.LayoutParams(dp(36), dp(36)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = e.name; textSize = 13.5f; maxLines = 1; setTextColor(txt); typeface = fSemi; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
        val meta = when {
            e.kind == "text" || e.kind == "link" -> fmtTime(e.time) + " · " + (if (e.kind == "link") "link" else "${e.size} chars")
            src == "quick" -> fmtTime(e.time) + " · " + Dock.size(e.size) + (if (e.opens > 0) " · opened ${e.opens}×" else "")
            else -> fmtTime(e.time) + " · " + Dock.size(e.size)
        }
        col.addView(TextView(this).apply { text = meta; textSize = 11.5f; maxLines = 1; setTextColor(mute); typeface = fReg; ellipsize = android.text.TextUtils.TruncateAt.END })
        line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(8), 0, dp(2), 0) })

        fun side(t: String, onTap: () -> Unit) = TextView(this).apply {
            text = t; textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xFFBFEFFF.toInt()); setPadding(dp(7), dp(6), dp(7), dp(6))
            setOnClickListener { resetIdle(); onTap() }
        }
        if (src == "docs") {
            val pinned = e.path != null && Dock.isPinned(this, e.path)
            line.addView(side("＋") { val err = e.path?.let { Dock.addFile(this, File(it)) }; say(err ?: "Added to the dock."); fillFiles() })
            line.addView(side(if (pinned) "★" else "☆") { e.path?.let { Dock.pin(this, it, !pinned) }; say(if (pinned) "Unpinned." else "Pinned to Quick files."); fillFiles() })
        } else if (src == "quick") {
            line.addView(side("✕") { e.path?.let { Dock.pin(this, it, false) }; fillFiles() })
        }

        line.setOnClickListener {
            resetIdle()
            if (src == "dock") { openEntry = if (openEntry == e.id) null else e.id; fillFiles() }
            else if (Dock.open(this, e)) collapse() else say("No app can open this file.")
        }
        line.setOnLongClickListener { dragOut(it, e) }
        return line
    }

    private fun thumb(path: String): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= 96 && o.outHeight / (s * 2) >= 96) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    /** Press and hold: lifts the item out of the panel. The panel closes so the app underneath can take the drop (that app must support drag and drop; it works best in split screen or a pop-up window). */
    private fun dragOut(v: View, e: Dock.FEntry): Boolean {
        val clip = Dock.clipFor(this, e) ?: run { say("That file is gone."); return false }
        dragging = true   // set first: Android sends the "drag started" event right away
        val ok = try {
            v.startDragAndDrop(clip, View.DragShadowBuilder(v), "pragon_dock", View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ)
        } catch (_: Exception) { false }
        if (!ok) { dragging = false; say("Android would not start the drag. Tap the item and use Share."); return false }
        collapse()   // the window shrinks back to the edge handle so the app underneath is fully visible and can take the drop
        main.postDelayed({ dragging = false }, 25000)
        return true
    }
}

/** One progress ring with the value in the middle (SYSTEM STATUS). */
private class RingView(ctx: Context, private val pct: Int, private val col: Int, private val label: String, private val tc: Int, private val face: Typeface) : View(ctx) {
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private var prog = 0f
    private var anim: android.animation.ValueAnimator? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200; interpolator = android.view.animation.DecelerateInterpolator(2f)
            addUpdateListener { prog = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat()
        val sw = 5f * d
        val r = RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
        pt.style = Paint.Style.STROKE; pt.strokeWidth = sw; pt.strokeCap = Paint.Cap.ROUND
        pt.color = 0x22FFFFFF; c.drawArc(r, 0f, 360f, false, pt)
        pt.color = col; c.drawArc(r, -90f, 360f * pct.coerceIn(0, 100) / 100f * prog, false, pt)
        pt.style = Paint.Style.FILL; pt.color = tc; pt.textAlign = Paint.Align.CENTER; pt.typeface = face; pt.textSize = 12f * d
        c.drawText(label, w / 2, h / 2 + 4f * d, pt)
    }
}
