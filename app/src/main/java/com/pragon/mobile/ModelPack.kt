package com.pragon.mobile

import android.content.Context
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.TimeUnit

/**
 * v7.7: "Download and load all models" in one tap (Settings > Offline), or one model at a time (the Download button on each row).
 *
 * Runs the downloads one after another on a background thread, skipping what is already installed:
 *   1. speech pack         (sherpa-onnx English streaming model + Silero VAD, about 70 MB)
 *      Realistic voice     (Kokoro 82M English voice via sherpa-onnx, about 103 MB download)
 *   2. cut-out model       (ML Kit subject segmentation, through Google Play services, a few MB)
 *   3. command model       (FunctionGemma 270M, about 283 MB)
 *   4. chat model          (Gemma 3 1B IT int4, about 529 MB; gated on Hugging Face, so it needs your token once)
 * The other ML Kit models (text recognition, image labelling, object detection) are inside the APK, so there is nothing to fetch.
 * v7.8: the realistic voice (Kokoro, about 103 MB) is part of the run. Until it is loaded, Pragon speaks with the phone's own text to speech.
 * One failure never stops the rest; the summary at the end says what is still missing.
 */
object ModelPack {

    /** Gemma 3 1B IT, 4-bit .litertlm (about 529 MB). Gated: log in and accept the Gemma licence once on Hugging Face. */
    const val CHAT_URL = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm"

    @Volatile var running = false
    /** "" idle, or progress text such as "Step 2 of 4: command model 37%", or the final summary. */
    @Volatile var state = ""
    /** Cut-out model: "unknown", "checking", "installed", "missing", "downloading" or "error: ...". */
    @Volatile var cutState = "unknown"

    /**
     * Downloads any .litertlm link as the CHAT model (replacing the current one). The Hugging Face token is attached only for
     * huggingface.co / hf.co links, so it can never be sent to another site.
     */
    fun downloadCustom(c: Context, url0: String, tokenIn: String): String {
        val app = c.applicationContext
        val url = url0.trim()
        val host = try { java.net.URI(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        if (!url.startsWith("https://") || host.isEmpty()) return "Paste a full https:// link to a .litertlm file."
        if (!url.substringBefore('?').lowercase().endsWith(".litertlm")) return "The link must end in .litertlm (only LiteRT-LM files work)."
        if (running || Offline.downloading || SherpaStt.downloading || PiperTts.downloading) return "Another download is running. Wait for it to finish, then tap again."
        if (!Net.online(app)) return "Connect to Wi-Fi first."
        val typed = tokenIn.trim()
        if (typed.isNotEmpty()) Prefs.setHfToken(app, typed)
        val isHf = host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co"
        Offline.download(app, url, Offline.CHAT_FILE, if (isHf) Prefs.hfToken(app) else "")
        return "Downloading as the chat model. It replaces the current one when it finishes. Use Wi-Fi."
    }

    /** A file picked in the search panel. */
    fun downloadFile(c: Context, repo: String, file: String, tokenIn: String): String {
        val url = ModelSearch.fileUrl(repo, file) ?: return "That file name doesn't look right."
        return downloadCustom(c, url, tokenIn)
    }

    /** Which parts a run covers. */
    private val ALL = setOf("speech", "piper", "cut", "command", "chat")

    /** One model: "speech", "cut", "command" or "chat". Downloads it if missing. Returns the sentence to show the person. */
    fun startOne(c: Context, which: String, tokenIn: String): String = begin(c, setOf(which), false, tokenIn)

    /** Everything that is missing, then loads the models into memory. */
    fun start(c: Context, tokenIn: String): String = begin(c, ALL, true, tokenIn)

    private fun begin(app0: Context, parts: Set<String>, loadAfter: Boolean, tokenIn: String): String {
        val app = app0.applicationContext
        if (running) return "Already working: $state"
        if (Offline.downloading || SherpaStt.downloading || PiperTts.downloading) return "Another download is running. Wait for it to finish, then tap again."
        val typed = tokenIn.trim()
        if (typed.isNotEmpty()) Prefs.setHfToken(app, typed)
        val missing = parts.filter { missing(app, it) }
        if (missing.isEmpty() && !loadAfter) return "That one is already installed."
        if (missing.isNotEmpty() && !Net.online(app)) return "Connect to Wi-Fi first: the models download once."
        running = true
        state = "starting"
        Thread { try { runParts(app, parts, loadAfter) } catch (e: Throwable) { state = "error: ${e.message}" } finally { running = false } }.start()
        return if (missing.isEmpty()) "Everything is installed. Loading the models..."
        else if (loadAfter) "Downloading and loading the offline models (about 0.9 GB if all are missing). Use Wi-Fi. You can leave this screen."
        else "Downloading. Use Wi-Fi. You can leave this screen."
    }

    private fun missing(c: Context, part: String): Boolean = when (part) {
        "speech" -> !SherpaStt.hasModel(c)
        "piper" -> !PiperTts.hasModel(c)
        "cut" -> cutState != "installed"
        "command" -> Offline.routerFile(c) == null
        "chat" -> Offline.chatFile(c) == null
        else -> false
    }

    private fun runParts(c: Context, parts: Set<String>, loadAfter: Boolean) {
        val done = ArrayList<String>()
        val failed = ArrayList<String>()
        val total = parts.count { missing(c, it) }
        var n = 0
        fun label(name: String) = if (total > 1) "Step ${++n} of $total: $name" else name

        if ("speech" in parts && missing(c, "speech")) {
            val l = label("speech pack")
            SherpaStt.download(c)
            waitFor(l, { SherpaStt.downloading }, { SherpaStt.dlState })
            if (SherpaStt.hasModel(c)) done.add("speech") else failed.add("speech (${SherpaStt.dlState})")
        }
        if ("piper" in parts && missing(c, "piper")) {
            val l = label("Realistic voice")
            PiperTts.download(c)
            waitFor(l, { PiperTts.downloading }, { PiperTts.dlState })
            if (PiperTts.hasModel(c)) done.add("Realistic voice") else failed.add("Realistic voice (${PiperTts.dlState})")
        }
        if ("cut" in parts && missing(c, "cut")) {
            state = label("cut-out model")
            val err = installCutout(c)
            if (err == null) done.add("cut-out") else failed.add("cut-out ($err)")
        }
        if ("command" in parts && missing(c, "command")) {
            val l = label("command model")
            Offline.download(c, Prefs.modelUrl(c))
            waitFor(l, { Offline.downloading }, { Offline.dlState })
            if (Offline.routerFile(c) != null) done.add("command") else failed.add("command (${Offline.dlState})")
        }
        if ("chat" in parts && missing(c, "chat")) {
            val token = Prefs.hfToken(c)
            if (token.isBlank()) {
                failed.add("chat model (needs your Hugging Face token)")
            } else {
                val l = label("chat model")
                Offline.download(c, CHAT_URL, Offline.CHAT_FILE, token)
                waitFor(l, { Offline.downloading }, { Offline.dlState })
                if (Offline.chatFile(c) != null) done.add("chat") else failed.add("chat model (${Offline.dlState})")
            }
        }

        val loaded = ArrayList<String>()
        if (loadAfter) {
            state = "Loading the models into memory..."
            if (SherpaStt.hasModel(c)) { SherpaStt.warmup(c); loaded.add("speech") }
            if (PiperTts.hasModel(c)) { PiperTts.warmup(c); loaded.add("Realistic voice") }
            // the command model and the chat model load one after the other: a phone with little memory can't hold both
            if (Offline.routerFile(c) != null) { OnDeviceLlm.warmOne(c, false); loaded.add("command") }
            if (Offline.chatFile(c) != null) {
                var wait = 0
                while (OnDeviceLlm.routerState == "loading" && wait++ < 120) try { Thread.sleep(500) } catch (e: InterruptedException) { break }
                OnDeviceLlm.warmOne(c, true); loaded.add("chat")
            }
        }

        state = buildString {
            append(if (failed.isEmpty()) "All done." else "Finished with problems.")
            if (done.isNotEmpty()) append(" Downloaded: ").append(done.joinToString(", ")).append(".")
            if (loaded.isNotEmpty()) append(" Loading: ").append(loaded.joinToString(", ")).append(" (the pills show READY when each is in memory).")
            if (failed.isNotEmpty()) append(" Still missing: ").append(failed.joinToString("; ")).append(".")
        }
    }

    private fun waitFor(label: String, busy: () -> Boolean, progress: () -> String) {
        // the download functions flip their "downloading" flag before they return, so this never misses the start
        while (busy()) {
            state = "$label ${progress()}".trim()
            try { Thread.sleep(500) } catch (e: InterruptedException) { return }
        }
    }

    // ── cut-out model (ML Kit subject segmentation, delivered by Google Play services) ──

    private fun segmenter() = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())

    /** Finds out (once, in the background) whether the cut-out model is already on the phone. */
    fun refreshCutout(c: Context) {
        if (cutState != "unknown") return
        cutState = "checking"
        val app = c.applicationContext
        Thread {
            cutState = try {
                val seg = segmenter()
                try {
                    val r = Tasks.await(ModuleInstall.getClient(app).areModulesAvailable(seg), 30, TimeUnit.SECONDS)
                    if (r.areModulesAvailable()) "installed" else "missing"
                } finally { seg.close() }
            } catch (e: Throwable) { "unknown" }
        }.start()
    }

    /** null = installed, otherwise a short reason. */
    private fun installCutout(c: Context): String? {
        cutState = "downloading"
        return try {
            val seg = segmenter()
            try {
                val client = ModuleInstall.getClient(c)
                if (Tasks.await(client.areModulesAvailable(seg), 30, TimeUnit.SECONDS).areModulesAvailable()) { cutState = "installed"; return null }
                Tasks.await(client.installModules(ModuleInstallRequest.newBuilder().addApi(seg).build()), 5, TimeUnit.MINUTES)
                // the request can return before the file has landed, so look again for up to 3 minutes
                var tries = 0
                while (tries++ < 60) {
                    if (Tasks.await(client.areModulesAvailable(seg), 30, TimeUnit.SECONDS).areModulesAvailable()) { cutState = "installed"; return null }
                    Thread.sleep(3000)
                }
                cutState = "missing"
                "Google Play services is still downloading it"
            } finally { seg.close() }
        } catch (e: Throwable) {
            cutState = "missing"
            "Google Play services couldn't install it"
        }
    }
}
