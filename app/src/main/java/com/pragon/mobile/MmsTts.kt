package com.pragon.mobile

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import okhttp3.OkHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * v8.2: offline Tamil, Telugu and Hindi voices (Meta MMS-TTS, VITS, run by sherpa-onnx). One model.onnx + tokens.txt per language,
 * about 110 MB each, 16 kHz, one voice. No word list and no espeak data: the voice reads the script directly.
 * Licence of the MMS voices: CC-BY-NC 4.0 (non-commercial use).
 *
 * This object only manages the files and the loaded models. Playing is done by [PiperTts]'s worker (enqueue with a language code),
 * so a reply is still never split between two engines and the phone's own voice stays the fallback.
 */
object MmsTts {

    class Lang(val code: String, val iso3: String, val name: String, val tag: String, val sample: String)

    val LANGS = listOf(
        Lang("ta", "tam", "Tamil", "ta-IN", "வணக்கம், இது ஒரு குரல் சோதனை"),
        Lang("te", "tel", "Telugu", "te-IN", "నమస్కారం, ఇది ఒక వాయిస్ పరీక్ష"),
        Lang("hi", "hin", "Hindi", "hi-IN", "नमस्ते, यह एक आवाज़ का परीक्षण है"),
    )

    fun byCode(code: String): Lang? = LANGS.firstOrNull { it.code == code }
    fun forTag(tag: String?): Lang? = if (tag == null) null else LANGS.firstOrNull { it.tag == tag }

    /** Primary source: per-language folders (model.onnx + tokens.txt). Second source: the k2-fsa release archive vits-mms-<iso3>.tar.bz2. */
    private const val HF = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/"
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-mms-"
    private const val MIN_MODEL_BYTES = 40_000_000L
    private const val THREADS = 2

    private val states = ConcurrentHashMap<String, String>()
    private val dls = ConcurrentHashMap<String, String>()
    private val busy = ConcurrentHashMap<String, Boolean>()
    private val engines = ConcurrentHashMap<String, OfflineTts>()
    private val lock = Any()
    @Volatile var lastTrace = ""

    private fun dir(c: Context, l: Lang) = File(c.filesDir, "mms/${l.iso3}")
    private fun modelFile(c: Context, l: Lang) = File(dir(c, l), "model.onnx")
    private fun tokensFile(c: Context, l: Lang) = File(dir(c, l), "tokens.txt")

    fun hasModel(c: Context, l: Lang): Boolean = modelFile(c, l).length() > MIN_MODEL_BYTES && tokensFile(c, l).length() > 0L
    fun state(l: Lang): String = states[l.code] ?: "idle"
    fun dlState(l: Lang): String = dls[l.code] ?: ""
    fun downloading(l: Lang): Boolean = busy[l.code] == true
    fun engine(code: String): OfflineTts? = engines[code]
    fun loaded(l: Lang): Boolean = engines.containsKey(l.code)
    fun anyDownloading(): Boolean = LANGS.any { downloading(it) }

    /** Should Speaker speak this language with its offline voice right now? Installed, loaded and not switched off. */
    fun usable(c: Context, l: Lang): Boolean = engines.containsKey(l.code) && Prefs.piperOn(c)

    // ── loading ──────────────────────────────────────────────────────────

    fun warmup(c: Context, l: Lang) {
        if (loaded(l) || !hasModel(c, l)) return
        val app = c.applicationContext
        Thread { try { load(app, l) } catch (e: Throwable) { } }.start()
    }

    fun load(c: Context, l: Lang): OfflineTts? = synchronized(lock) { loadLocked(c, l) }

    private fun loadLocked(c: Context, l: Lang): OfflineTts? {
        engines[l.code]?.let { return it }
        if (!hasModel(c, l)) return null
        states[l.code] = "loading"
        return try {
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile(c, l).absolutePath,
                        tokens = tokensFile(c, l).absolutePath,
                    ),
                    numThreads = THREADS,
                    provider = "cpu",
                ),
            )
            val t = OfflineTts(config = cfg)
            engines[l.code] = t
            states[l.code] = "ready"
            t
        } catch (e: Throwable) {
            states[l.code] = "error: ${e.message ?: e.javaClass.simpleName}"
            lastTrace = VoiceDiag.trace(e)
            null
        }
    }

    fun release(code: String) {
        PiperTts.stop()
        synchronized(lock) {
            try { engines.remove(code)?.release() } catch (e: Throwable) { }
            states[code] = "idle"
        }
    }

    // ── text preparation ─────────────────────────────────────────────────

    /** The MMS voices read characters; a trailing full stop makes sherpa-onnx add a short noisy sentence, so end marks are dropped. */
    fun prepare(s: String): String = s.trim().trimEnd('.', '!', '?', '\u0964', '\u2026', '"', '\'', ')', ':', ';', ',', ' ').trim()

    // ── download ─────────────────────────────────────────────────────────

    fun download(c: Context, l: Lang) {
        if (busy[l.code] == true) return
        busy[l.code] = true
        dls[l.code] = "starting"
        val app = c.applicationContext
        Thread {
            val tmp = File(app.filesDir, "mms/${l.iso3}.tmp")
            val arch = File(app.cacheDir, "mms-${l.iso3}.part")
            try {
                val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
                tmp.deleteRecursively(); tmp.mkdirs()
                var firstError = ""
                try {
                    PiperTts.fetch(http, HF + l.iso3 + "/tokens.txt", File(tmp, "tokens.txt")) { }
                    PiperTts.fetch(http, HF + l.iso3 + "/model.onnx", File(tmp, "model.onnx")) { dls[l.code] = "voice $it" }
                } catch (e: Throwable) { firstError = e.message ?: e.javaClass.simpleName }
                if (File(tmp, "model.onnx").length() < MIN_MODEL_BYTES || File(tmp, "tokens.txt").length() == 0L) {
                    tmp.deleteRecursively(); tmp.mkdirs()
                    dls[l.code] = "trying the second source"
                    try {
                        PiperTts.fetch(http, GH + l.iso3 + ".tar.bz2", arch) { dls[l.code] = "voice $it" }
                        dls[l.code] = "unpacking"
                        untarFlat(arch, tmp)
                    } catch (e: Throwable) {
                        throw IOException("both sources failed (1: $firstError; 2: ${e.message ?: e.javaClass.simpleName})")
                    }
                }
                if (File(tmp, "model.onnx").length() < MIN_MODEL_BYTES) throw IOException("the voice file is missing or too small (first source: $firstError)")
                if (File(tmp, "tokens.txt").length() == 0L) throw IOException("the voice has no tokens.txt")
                release(l.code)
                dir(app, l).deleteRecursively()
                dir(app, l).parentFile?.mkdirs()
                if (!tmp.renameTo(dir(app, l))) throw IOException("couldn't save the voice")
                dls[l.code] = "done"
                warmup(app, l)
            } catch (e: Throwable) {
                tmp.deleteRecursively()
                dls[l.code] = "error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                arch.delete()
                busy[l.code] = false
            }
        }.start()
    }

    /** Pulls tokens.txt and the biggest .onnx out of the archive, whatever the folder is called. */
    private fun untarFlat(archive: File, dest: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tin ->
            while (true) {
                val e = tin.nextEntry ?: break
                if (e.isDirectory) continue
                val base = File(e.name).name
                if (base == "tokens.txt" || base.endsWith(".onnx")) File(dest, base).outputStream().use { tin.copyTo(it) }
            }
        }
        val best = dest.listFiles { f -> f.name.endsWith(".onnx") }?.maxByOrNull { it.length() }
        if (best != null && best.name != "model.onnx") best.renameTo(File(dest, "model.onnx"))
    }

    // ── status and test ──────────────────────────────────────────────────

    fun statusJson(c: Context): JSONObject {
        val o = JSONObject()
        for (l in LANGS) o.put(l.code, JSONObject().put("has", hasModel(c, l)).put("state", state(l)).put("dl", dlState(l)).put("downloading", downloading(l)))
        return o
    }

    fun diagnose(c: Context, l: Lang): String {
        val sb = StringBuilder("${l.name.uppercase()} VOICE (MMS)\n")
        sb.append("model.onnx: ${modelFile(c, l).length() / 1000} KB | tokens.txt: ${tokensFile(c, l).length()} bytes | state: ${state(l)} | loaded: ${loaded(l)}\n")
        val t = engines[l.code] ?: load(c, l)
        if (t == null) { sb.append("could not load: ${state(l)}\n$lastTrace\n"); return sb.toString() }
        try {
            val t0 = System.currentTimeMillis()
            val a = t.generate(l.sample, 0, 1.0f)
            var peak = 0f
            for (s in a.samples) { val v = kotlin.math.abs(s); if (v > peak) peak = v }
            sb.append("made ${a.samples.size} samples at ${a.sampleRate} Hz in ${System.currentTimeMillis() - t0} ms, loudest ${String.format(java.util.Locale.US, "%.2f", peak)}\n")
            if (a.samples.isEmpty() || peak < 0.01f) sb.append("-> the voice made silence\n")
        } catch (e: Throwable) { sb.append("making speech FAILED: ${VoiceDiag.trace(e)}\n") }
        sb.append("playback: ${PiperTts.playTest(l.sample, l.code)}\n")
        return sb.toString()
    }
}
