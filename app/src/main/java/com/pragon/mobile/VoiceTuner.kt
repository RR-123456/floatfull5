package com.pragon.mobile

import android.content.Context
import org.json.JSONObject

/**
 * v35 voice tuner (Android side). Makes Gemini's Charon voice come out sounding like the Pragon reference voice
 * (the "assistant voice" clip): tone match + pitch match + a few knobs. The maths lives in VoiceTunerCore.
 *
 * Settings are one JSON string in Prefs ("tuner"); changing any of them takes effect on the next chunk of speech.
 * Settings > Pragon Live voice > Voice tuner.
 */
object VoiceTuner {

    /** Bumped on every change so a playing session notices it. */
    @Volatile var version = 0
        private set
    @Volatile private var heardF0 = 0f
    @Volatile private var heardSecs = 0f

    private fun load(c: Context): JSONObject = try { JSONObject(Prefs.tuner(c)) } catch (e: Exception) { JSONObject() }

    fun params(c: Context): TunerParams {
        val j = load(c)
        fun f(k: String, d: Double) = j.optDouble(k, d).toFloat()
        return TunerParams(
            on = j.optBoolean("on", true),
            match = f("match", 0.85).coerceIn(0f, 1f),
            pitchAuto = j.optBoolean("pitchAuto", true),
            pitchSemi = f("pitchSemi", 0.0).coerceIn(-6f, 6f),
            warmth = f("warmth", 0.0).coerceIn(-6f, 6f),
            presence = f("presence", 0.0).coerceIn(-6f, 6f),
            metallic = f("metallic", 0.0).coerceIn(0f, 1f),
            space = f("space", 0.0).coerceIn(0f, 1f),
            gainDb = f("gainDb", 0.0).coerceIn(-6f, 6f),
        )
    }

    /** For the Settings screen. */
    fun json(c: Context): String {
        val p = params(c)
        return JSONObject()
            .put("on", p.on).put("match", p.match.toDouble()).put("pitchAuto", p.pitchAuto).put("pitchSemi", p.pitchSemi.toDouble())
            .put("warmth", p.warmth.toDouble()).put("presence", p.presence.toDouble()).put("metallic", p.metallic.toDouble())
            .put("space", p.space.toDouble()).put("gainDb", p.gainDb.toDouble())
            .put("refF0", VoiceTunerCore.REF_F0.toDouble()).put("heardF0", heardF0.toDouble()).put("heardSecs", heardSecs.toDouble())
            .toString()
    }

    /** key: on, match, pitchAuto, pitchSemi, warmth, presence, metallic, space, gainDb. Booleans arrive as 0 or 1. */
    fun set(c: Context, key: String, v: Double) {
        val j = load(c)
        when (key) {
            "on", "pitchAuto" -> j.put(key, v >= 0.5)
            "match", "pitchSemi", "warmth", "presence", "metallic", "space", "gainDb" -> j.put(key, v)
            else -> return
        }
        Prefs.setTuner(c, j.toString()); version++
    }

    fun reset(c: Context) { Prefs.setTuner(c, ""); Prefs.setTunerState(c, ""); version++ }

    /** Exposed to the UI customiser page as window.PragonTuner (the "Charon voice" panel). */
    class Bridge(private val c: Context) {
        @android.webkit.JavascriptInterface fun get(): String = json(c)
        @android.webkit.JavascriptInterface fun set(key: String, v: Double) { VoiceTuner.set(c, key, v) }
    }

    /** One per playing session; owned by the audio player. */
    class Chain(private val app: Context) {
        private val core = VoiceTunerCore(24000)
        private var p = TunerParams()
        private var ver = -1
        private var saveAt = 0L

        init {
            try {
                val s = Prefs.tunerState(app)
                if (s.isNotBlank()) core.importState(s.split(',').map { it.trim().toFloat() }.toFloatArray())
            } catch (e: Exception) { }
        }

        fun process(pcm: ByteArray): ByteArray {
            if (ver != version) { p = params(app); ver = version }
            if (!p.on) return pcm
            val out = core.process(pcm, p)
            heardF0 = core.heardF0(); heardSecs = core.heardSeconds()
            val now = System.currentTimeMillis()
            if (now - saveAt > 10_000) { saveAt = now; save() }
            return out
        }

        /** Playback pitch multiplier for the AudioTrack (1.0 = unchanged). */
        fun pitchRatio(): Float = Math.pow(2.0, core.semitones(p) / 12.0).toFloat().coerceIn(0.6f, 1.6f)

        private fun save() {
            try { Prefs.setTunerState(app, core.exportState().joinToString(",")) } catch (e: Exception) { }
        }

        fun close() = save()
    }
}
