package com.pragon.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * v7.9: the offline realistic voice. It is now Kokoro (82M parameters, English, 11 voices, int8) run by sherpa-onnx, replacing the
 * Piper "amy-low" voice, which did not speak (its word list used multi-letter sounds that its token list does not have).
 * Kokoro uses sherpa-onnx's own documented setup: model + voices.bin + tokens.txt + espeak-ng-data. No word list.
 * The object keeps its old name so Speaker, Offline and Settings did not need to change.
 *
 * Speaker.kt uses it whenever it is installed AND loaded in memory; otherwise (not downloaded yet, still loading, switched off,
 * or any error) the phone's own text to speech speaks, exactly as before. So it can never leave Pragon silent.
 * Files live in filesDir/kokoro: model.int8.onnx, voices.bin, tokens.txt, espeak-ng-data/.
 */
object PiperTts {

    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"

    private const val MIN_MODEL_BYTES = 50_000_000L
    private const val THREADS = 4
    /** Kokoro en-v0_19 voices: 0 af, 1 af_bella, 2 af_nicole, 3 af_sarah, 4 af_sky, 5 am_adam, 6 am_michael, 7 bf_emma, 8 bf_isabella, 9 bm_george, 10 bm_lewis. */
    private const val SPEAKER = 1

    @Volatile var state = "idle"                   // "idle", "loading", "ready" or "error: ..."
    @Volatile var downloading = false
    @Volatile var dlState = ""                     // "", "37%", "unpacking", "done" or "error: ..."

    @Volatile private var tts: OfflineTts? = null
    private val loadLock = Any()

    // ── where things live ────────────────────────────────────────────────

    private fun dir(c: Context) = File(c.filesDir, "kokoro")
    private fun modelFile(c: Context) = File(dir(c), "model.int8.onnx")
    private fun voicesFile(c: Context) = File(dir(c), "voices.bin")
    private fun tokensFile(c: Context) = File(dir(c), "tokens.txt")
    private fun dataDir(c: Context) = File(dir(c), "espeak-ng-data")

    fun hasModel(c: Context): Boolean =
        modelFile(c).length() > MIN_MODEL_BYTES && voicesFile(c).length() > 0L && tokensFile(c).length() > 0L &&
            File(dataDir(c), "phontab").exists()

    /** True when Piper is loaded in memory. */
    fun loaded(): Boolean = tts != null

    /** Should Speaker speak with Piper right now? Installed, loaded and not switched off. */
    fun usable(c: Context): Boolean = tts != null && Prefs.piperOn(c)

    // ── loading ──────────────────────────────────────────────────────────

    fun warmup(c: Context) {
        if (tts != null || !hasModel(c)) return
        val app = c.applicationContext
        Thread { try { load(app) } catch (e: Throwable) { } }.start()
    }

    private fun load(c: Context): OfflineTts? = synchronized(loadLock) { loadLocked(c) }

    private fun loadLocked(c: Context): OfflineTts? {
        tts?.let { return it }
        if (!hasModel(c)) return null
        state = "loading"
        return try {
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = modelFile(c).absolutePath,
                        voices = voicesFile(c).absolutePath,
                        tokens = tokensFile(c).absolutePath,
                        dataDir = dataDir(c).absolutePath,
                    ),
                    numThreads = THREADS,
                    provider = "cpu",
                ),
            )
            val t = OfflineTts(config = cfg)
            tts = t
            state = "ready"
            t
        } catch (e: Throwable) {
            state = "error: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    /** Frees the memory (used before a new download replaces the files). */
    fun release() {
        stop()
        synchronized(loadLock) {
            try { tts?.release() } catch (e: Throwable) { }
            tts = null
            state = "idle"
        }
    }

    // ── speaking: one worker thread, sentence by sentence ────────────────

    private class Job(val text: String, val speed: Float, val gen: Int, val done: (Boolean) -> Unit)

    private val queue = LinkedBlockingQueue<Job>()
    @Volatile private var gen = 0
    @Volatile private var current: AudioTrack? = null
    @Volatile private var played = false
    private var worker: Thread? = null

    /**
     * Queues one sentence. [done] is called on the worker thread with true when it has been spoken, or false when Piper failed
     * before any sound came out (the caller then speaks it with the phone's own voice). Nothing is called after [stop].
     */
    fun enqueue(text: String, speed: Float, done: (Boolean) -> Unit) {
        synchronized(this) {
            if (worker == null) {
                worker = Thread({ loop() }, "kokoro-tts").also { it.isDaemon = true; it.start() }
            }
        }
        queue.put(Job(text, speed.coerceIn(0.6f, 1.6f), gen, done))
    }

    /** Stops at once: drops everything queued and cuts the sentence being spoken. */
    fun stop() {
        gen++
        queue.clear()
        try { current?.let { it.pause(); it.flush() } } catch (e: Throwable) { }
    }

    private fun loop() {
        while (true) {
            val j = try { queue.take() } catch (e: InterruptedException) { return }
            if (j.gen != gen) continue
            var ok = true
            played = false
            try {
                speakOne(j)
            } catch (e: Throwable) {
                ok = played           // failed after some sound = treat as spoken; failed before any = let the phone voice do it
            }
            if (j.gen == gen) try { j.done(ok) } catch (e: Throwable) { }
        }
    }

    private fun speakOne(j: Job) {
        val t = tts ?: throw IllegalStateException("The offline voice is not loaded")
        val rate = t.sampleRate()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val buf = maxOf(minBuf, rate / 2 * 4)             // about half a second of float audio
        val attrs = AudioAttributes.Builder()
            .setUsage(if (AudioRoute.listeningOverBluetooth()) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val at = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
            .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build()
        current = at
        var frames = 0L
        try {
            at.play()
            t.generateWithCallback(text = prepare(j.text), sid = SPEAKER, speed = j.speed) { samples ->
                if (j.gen != gen) {
                    0                                         // stop generating
                } else {
                    if (samples.isNotEmpty()) {
                        at.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        played = true
                        frames += samples.size
                    }
                    1
                }
            }
            // wait until the speaker has really played what was written, so Pragon doesn't un-mute the mic too early
            val limit = System.currentTimeMillis() + frames * 1000L / rate + 1500L
            while (j.gen == gen && at.playbackHeadPosition < frames && System.currentTimeMillis() < limit) {
                try { Thread.sleep(30) } catch (e: InterruptedException) { break }
            }
        } finally {
            current = null
            try { at.stop() } catch (e: Throwable) { }
            try { at.release() } catch (e: Throwable) { }
        }
    }

    // ── text preparation: numbers are spelled out so they are always read the same way ─────────────────

    private val ONES = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** 0 up to 999,999,999 in words. */
    internal fun words(n: Long): String {
        fun rest(r: Long) = if (r > 0) " " + words(r) else ""
        return when {
            n < 20 -> ONES[n.toInt()]
            n < 100 -> TENS[(n / 10).toInt()] + rest(n % 10)
            n < 1_000 -> ONES[(n / 100).toInt()] + " hundred" + rest(n % 100)
            n < 1_000_000 -> words(n / 1_000) + " thousand" + rest(n % 1_000)
            else -> words(n / 1_000_000) + " million" + rest(n % 1_000_000)
        }
    }

    private fun digitsOnly(d: String) = d.map { ONES[it - '0'] }.joinToString(" ")

    /** "set a timer for 5 minutes" -> "set a timer for five minutes"; also 3.5, 1,000, 50%, & and +. */
    internal fun prepare(s: String): String {
        var t = s.replace("&", " and ").replace("%", " percent").replace("+", " plus ")
        t = Regex("\\d{1,3}(,\\d{3})+").replace(t) { it.value.replace(",", "") }
        t = Regex("(\\d+)\\.(\\d+)").replace(t) { m ->
            val whole = m.groupValues[1]
            (if (whole.length <= 9) words(whole.toLong()) else digitsOnly(whole)) + " point " + digitsOnly(m.groupValues[2])
        }
        t = Regex("\\d+").replace(t) { m ->
            val d = m.value
            if (d.length <= 9) words(d.toLong()) else digitsOnly(d)
        }
        return t
    }

    // ── download ─────────────────────────────────────────────────────────

    /** Fetches Kokoro (about 103 MB, about 160 MB once unpacked). Progress is in [dlState]; [downloading] is true until done. */
    fun download(c: Context) {
        if (downloading) return
        downloading = true
        dlState = "starting"
        val app = c.applicationContext
        Thread {
            val archive = File(app.cacheDir, "kokoro-model.part")
            val tmp = File(app.filesDir, "kokoro.tmp")
            try {
                val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
                tmp.deleteRecursively(); tmp.mkdirs()
                fetch(http, MODEL_URL, archive) { dlState = "voice $it" }
                dlState = "unpacking"
                untar(archive, tmp)
                archive.delete()
                if (File(tmp, "model.int8.onnx").length() < MIN_MODEL_BYTES) throw IOException("the voice file is missing or too small")
                if (File(tmp, "voices.bin").length() == 0L) throw IOException("the archive has no voices.bin")
                if (File(tmp, "tokens.txt").length() == 0L) throw IOException("the archive has no tokens.txt")
                if (!File(tmp, "espeak-ng-data/phontab").exists()) throw IOException("the archive has no espeak-ng-data")
                release()                                    // free the old voice before replacing its files
                dir(app).deleteRecursively()
                File(app.filesDir, "piper").deleteRecursively()   // the old Piper voice (about 73 MB) is not used any more
                if (!tmp.renameTo(dir(app))) throw IOException("couldn't save the voice")
                dlState = "done"
                warmup(app)
            } catch (e: Throwable) {
                tmp.deleteRecursively()
                dlState = "error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                archive.delete()
                downloading = false
            }
        }.start()
    }

    private fun fetch(http: OkHttpClient, link: String, dest: File, progress: (String) -> Unit) {
        http.newCall(Request.Builder().url(link).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty answer")
            val total = body.contentLength()
            var done = 0L
            body.byteStream().use { inp ->
                dest.outputStream().use { out ->
                    val b = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(b)
                        if (n < 0) break
                        out.write(b, 0, n)
                        done += n
                        progress(if (total > 0) "${done * 100 / total}%" else "${done / 1_000_000} MB")
                    }
                }
            }
        }
    }

    /** Unpacks the whole archive into [dest], dropping the top folder name (kokoro-int8-en-v0_19/) and refusing paths that escape [dest]. */
    private fun untar(archive: File, dest: File) {
        val root = dest.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tin ->
            while (true) {
                val e = tin.nextEntry ?: break
                if (e.isDirectory) continue
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(root, rel).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) continue
                out.parentFile?.mkdirs()
                out.outputStream().use { tin.copyTo(it) }
            }
        }
    }
}
