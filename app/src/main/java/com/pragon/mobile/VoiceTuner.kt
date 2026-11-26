package com.pragon.mobile

import android.content.Context
import org.json.JSONObject

/**
 * v36 voice tuner (Android side). Makes Gemini's Charon voice come out sounding like the Pragon reference voice
 * (the "assistant voice" clip). The signal processing lives in VoiceMorph.java (pitch shifter + voice-size correction + tone match).
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

    fun params(c: Context): VoiceMorph.Params {
        val j = load(c)
        fun f(k: String, d: Double) = j.optDouble(k, d).toFloat()
        return VoiceMorph.Params().apply {
            on = j.optBoolean("on", true)
            match = f("match", 0.85).coerceIn(0f, 1f)
            pitchAuto = j.optBoolean("pitchAuto", true)
            pitchSemi = f("pitchSemi", 0.0).coerceIn(-6f, 6f)
            formant = f("formant", 0.0).coerceIn(-6f, 6f)
            warmth = f("warmth", 0.0).coerceIn(-6f, 6f)
            presence = f("presence", 0.0).coerceIn(-6f, 6f)
            metallic = f("metallic", 0.0).coerceIn(0f, 1f)
            space = f("space", 0.0).coerceIn(0f, 1f)
            gainDb = f("gainDb", 0.0).coerceIn(-6f, 6f)
            pitchMode = if (j.optInt("pitchMode", 0) == 1) 1 else 0
        }
    }

    /** For the Settings screen. */
    fun json(c: Context): String {
        val p = params(c)
        return JSONObject()
            .put("on", p.on).put("match", p.match.toDouble()).put("pitchAuto", p.pitchAuto).put("pitchSemi", p.pitchSemi.toDouble())
            .put("formant", p.formant.toDouble())
            .put("warmth", p.warmth.toDouble()).put("presence", p.presence.toDouble()).put("metallic", p.metallic.toDouble())
            .put("space", p.space.toDouble()).put("gainDb", p.gainDb.toDouble()).put("pitchMode", p.pitchMode)
            .put("refF0", VoiceMorph.REF_F0.toDouble()).put("heardF0", heardF0.toDouble()).put("heardSecs", heardSecs.toDouble())
            .toString()
    }

    /** key: on, match, pitchAuto, pitchSemi, formant, warmth, presence, metallic, space, gainDb, pitchMode. Booleans arrive as 0 or 1. */
    fun set(c: Context, key: String, v: Double) {
        val j = load(c)
        when (key) {
            "on", "pitchAuto" -> j.put(key, v >= 0.5)
            "pitchMode" -> j.put(key, if (v >= 0.5) 1 else 0)
            "match", "pitchSemi", "formant", "warmth", "presence", "metallic", "space", "gainDb" -> j.put(key, v)
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
        private val core = VoiceMorph()
        private var p = VoiceMorph.Params()
        private var ver = -1
        private var saveAt = 0L

        init {
            try {
                val s = Prefs.tunerState(app)
                if (s.isNotBlank()) core.importState(s.split(',').map { it.trim().toFloat() }.toFloatArray())
            } catch (e: Exception) { }
        }

        private fun refresh() { if (ver != version) { p = params(app); ver = version } }

        fun process(pcm: ByteArray): ByteArray {
            refresh()
            if (!p.on) return pcm
            val out = core.process(pcm, p)
            heardF0 = core.heardF0(); heardSecs = core.heardSeconds()
            val now = System.currentTimeMillis()
            if (now - saveAt > 10_000) { saveAt = now; save() }
            return out
        }

        /** True while the last words of speech may still sit inside the shifter's delay (call flush() after a pause). */
        fun pending(): Boolean = p.on && core.pending()

        /** A little silence pushed through, so the end of a sentence is not held back. Returns audio to play. */
        fun flush(): ByteArray { refresh(); return core.flush(p) }

        /** The listener interrupted Pragon: throw away what is still inside the tuner. */
        fun interrupt() { core.requestDrop() }

        /** Playback pitch multiplier for the AudioTrack: 1.0 unless the phone's player is set to do the shifting (pitchMode 1). */
        fun pitchRatio(): Float { refresh(); return core.playerRatio(p) }

        private fun save() {
            try { Prefs.setTunerState(app, core.exportState().joinToString(",")) } catch (e: Exception) { }
        }

        fun close() = save()
    }
}
