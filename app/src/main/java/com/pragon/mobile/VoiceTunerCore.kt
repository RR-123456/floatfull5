package com.pragon.mobile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * v35 voice tuner, the engine. Plain Kotlin (no Android) so it can be tested on a laptop.
 *
 * Goal: Gemini's "Charon" voice should be HEARD as the Pragon reference voice (the "assistant voice" clip).
 * What it does, on every 24 kHz 16-bit mono chunk that is about to be played:
 *   1. Listens to Charon: a running average of how loud each of 11 frequency bands is, and a running median of the pitch.
 *   2. Tone match: a 11-band equaliser pulls Charon's average tone toward the reference voice's average tone (REF_BAND_DB).
 *   3. Pitch match: asks the player (AudioTrack playback pitch) to move Charon's average pitch to the reference pitch (REF_F0).
 *   4. Optional colour: warmth, presence, metallic, space, loudness, and a soft limiter so nothing clips.
 *
 * REF_BAND_DB / REF_F0 were measured from the reference clip with exactly this filter bank and pitch method.
 */
class TunerParams(
    var on: Boolean = true,
    /** 0..1: how far the tone is pulled toward the reference (0 = leave Charon's tone alone). */
    var match: Float = 0.85f,
    /** Move Charon's average pitch to the reference pitch by itself. */
    var pitchAuto: Boolean = true,
    /** Extra pitch on top of the automatic match, in semitones (-6..+6). */
    var pitchSemi: Float = 0f,
    /** Low shelf at ~180 Hz, dB (-6..+6). */
    var warmth: Float = 0f,
    /** High shelf at ~3.5 kHz, dB (-6..+6). */
    var presence: Float = 0f,
    /** 0..1: short comb filter, a slightly synthetic/metallic edge. */
    var metallic: Float = 0f,
    /** 0..1: small-room reverb. */
    var space: Float = 0f,
    /** Output level, dB (-6..+6). */
    var gainDb: Float = 0f,
)

class VoiceTunerCore(private val sr: Int = 24000) {

    companion object {
        /** Band edges (Hz). 11 bands. */
        val EDGES = floatArrayOf(60f, 120f, 200f, 320f, 500f, 800f, 1200f, 2000f, 3200f, 5000f, 8000f, 11000f)
        /** Reference voice: each band's share of the total energy, dB (measured with this filter bank, speech frames only). */
        val REF_BAND_DB = floatArrayOf(-11.61f, -10.15f, -7.62f, -6.49f, -8.78f, -8.76f, -11.44f, -14.83f, -15.38f, -14.74f, -23.77f)
        /** Reference voice: median pitch (Hz), measured with the pitch method below. */
        const val REF_F0 = 133.1f
        const val NB = 11
        private const val MAX_EQ_DB = 9f
        private const val MAX_AUTO_SEMI = 5f
        private const val TAU_SEC = 8f
        private const val GATE_RMS = 0.01f
        private const val F0_RMS = 0.03f
        private const val F0_FRAME = 960
        private const val F0_HOP = 480
        private const val F0_MIN_LAG = 80
        private const val F0_MAX_LAG = 342
        private const val F0_KEEP = 300
    }

    /** One biquad section (transposed direct form II). */
    private class Biquad {
        var b0 = 1f; var b1 = 0f; var b2 = 0f; var a1 = 0f; var a2 = 0f
        private var z1 = 0f; private var z2 = 0f
        fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
            b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
            a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
        }
        fun run(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
        fun bandpass(fc: Double, q: Double, sr: Int) {
            val w = 2 * PI * fc / sr; val al = sin(w) / (2 * q)
            set(al, 0.0, -al, 1 + al, -2 * cos(w), 1 - al)
        }
        fun peaking(fc: Double, q: Double, db: Double, sr: Int) {
            val a = 10.0.pow(db / 40.0); val w = 2 * PI * fc / sr; val al = sin(w) / (2 * q); val c = cos(w)
            set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
        }
        fun lowShelf(fc: Double, db: Double, sr: Int) {
            val a = 10.0.pow(db / 40.0); val w = 2 * PI * fc / sr; val c = cos(w); val s = sin(w)
            val al = s / 2 * sqrt(2.0); val tsa = 2 * sqrt(a) * al
            set(a * ((a + 1) - (a - 1) * c + tsa), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - tsa),
                (a + 1) + (a - 1) * c + tsa, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - tsa)
        }
        fun highShelf(fc: Double, db: Double, sr: Int) {
            val a = 10.0.pow(db / 40.0); val w = 2 * PI * fc / sr; val c = cos(w); val s = sin(w)
            val al = s / 2 * sqrt(2.0); val tsa = 2 * sqrt(a) * al
            set(a * ((a + 1) + (a - 1) * c + tsa), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - tsa),
                (a + 1) - (a - 1) * c + tsa, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - tsa)
        }
    }

    private val centers = DoubleArray(NB) { sqrt(EDGES[it].toDouble() * EDGES[it + 1]) }
    private val anaBp = Array(NB) { Biquad().also { f -> f.bandpass(centers[it], centers[it] / (EDGES[it + 1] - EDGES[it]), sr) } }
    private val eq = Array(NB) { Biquad().also { f -> f.peaking(centers[it], 1.2, 0.0, sr) } }
    private val eqDb = FloatArray(NB)
    private val shelfLo = Biquad()
    private val shelfHi = Biquad()
    private var lastWarmth = 0f
    private var lastPresence = 0f

    // ---- what we have heard from Charon
    private val energy = DoubleArray(NB)
    private var chunks = 0
    private var spoken = 0f            // seconds of speech heard

    private val f0Hist = FloatArray(F0_KEEP)
    private var f0N = 0
    private var f0Pos = 0
    private val f0Buf = FloatArray(F0_FRAME)
    private val f0Score = DoubleArray(F0_MAX_LAG - F0_MIN_LAG + 3)
    private var f0Fill = 0
    private var autoSemi = 0f

    // ---- colour effects
    private val combBuf = FloatArray(sr / 50)        // up to 20 ms
    private var combPos = 0
    private val verb = Reverb(sr)

    /** Pitch to ask the player for, in semitones (automatic match + the manual offset). */
    fun semitones(p: TunerParams): Float {
        if (!p.on) return 0f
        val s = (if (p.pitchAuto) autoSemi else 0f) + p.pitchSemi
        return s.coerceIn(-8f, 8f)
    }

    /** Heard speech so far, seconds (for the screen). */
    fun heardSeconds(): Float = spoken

    /** The pitch of Charon as heard so far (Hz), 0 if not enough yet. */
    fun heardF0(): Float = median()

    fun reset() {
        java.util.Arrays.fill(energy, 0.0); chunks = 0; spoken = 0f
        f0N = 0; f0Pos = 0; f0Fill = 0; autoSemi = 0f
        java.util.Arrays.fill(eqDb, 0f)
        for (i in 0 until NB) eq[i].peaking(centers[i], 1.2, 0.0, sr)
    }

    /** What has been heard so far, so the next conversation starts already tuned (saved by the caller). */
    fun exportState(): FloatArray = FloatArray(NB + 3) { i ->
        when {
            i < NB -> energy[i].toFloat()
            i == NB -> spoken
            i == NB + 1 -> median()
            else -> chunks.toFloat()
        }
    }

    fun importState(s: FloatArray?) {
        if (s == null || s.size != NB + 3) return
        for (i in 0 until NB) energy[i] = s[i].toDouble()
        spoken = s[NB]; chunks = s[NB + 2].toInt()
        val f = s[NB + 1]
        if (f > 50f && f < 400f) { for (k in 0 until 20) push(f) }
    }

    /** Process one chunk of 16-bit little-endian mono PCM. Returns a new array (or the same one when the tuner is off). */
    fun process(pcm: ByteArray, p: TunerParams): ByteArray {
        if (!p.on || pcm.size < 2) return pcm
        val n = pcm.size / 2
        val x = FloatArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val v = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt() / 32768f
            x[i] = v; sum += v * v
        }
        val rms = sqrt(sum / n).toFloat()
        listen(x, rms)
        retune(p)

        val gain = 10f.pow(p.gainDb / 20f)
        val comb = (p.metallic.coerceIn(0f, 1f)) * 0.45f
        val dly = (sr * 0.007f).toInt()
        val wet = p.space.coerceIn(0f, 1f) * 0.28f
        val out = ByteArray(pcm.size)
        for (i in 0 until n) {
            var y = x[i]
            for (k in 0 until NB) y = eq[k].run(y)
            y = shelfLo.run(y); y = shelfHi.run(y)
            if (comb > 0f) {
                val d = combBuf[(combPos - dly + combBuf.size) % combBuf.size]
                combBuf[combPos] = y
                combPos = (combPos + 1) % combBuf.size
                y += comb * d
            } else { combBuf[combPos] = y; combPos = (combPos + 1) % combBuf.size }
            if (wet > 0f) y = y * (1f - wet * 0.5f) + verb.run(y) * wet
            y = limit(y * gain)
            val s = (y * 32767f).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (s and 0xFF).toByte(); out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    // -------------------------------------------------------------- listening

    private fun listen(x: FloatArray, rms: Float) {
        val e = DoubleArray(NB)
        val loud = rms > GATE_RMS
        for (i in x.indices) {
            for (k in 0 until NB) { val y = anaBp[k].run(x[i]); if (loud) e[k] += (y * y).toDouble() }
        }
        if (loud) {
            chunks++
            val secs = x.size / sr.toFloat()
            val w = max(1.0 / chunks, (secs / TAU_SEC).toDouble())
            for (k in 0 until NB) energy[k] = energy[k] * (1 - w) + e[k] * w
            spoken += secs
            // pitch: 40 ms frames every 20 ms, voiced and loud only
            for (v in x) {
                f0Buf[f0Fill++] = v
                if (f0Fill == F0_FRAME) {
                    estimateF0()
                    System.arraycopy(f0Buf, F0_HOP, f0Buf, 0, F0_FRAME - F0_HOP)
                    f0Fill = F0_FRAME - F0_HOP
                }
            }
        } else f0Fill = 0
    }

    private fun estimateF0() {
        var m = 0f
        for (v in f0Buf) m += v
        m /= F0_FRAME
        var ac0 = 0.0; var rmsS = 0.0
        for (i in 0 until F0_FRAME) { val d = f0Buf[i] - m; f0Buf[i] = d; ac0 += d * d; rmsS += d * d }
        if (sqrt(rmsS / F0_FRAME) < F0_RMS || ac0 <= 0.0) return
        var best = 0.0; var bk = 0
        for (k in F0_MIN_LAG - 1..F0_MAX_LAG + 1) {
            var a = 0.0
            for (i in 0 until F0_FRAME - k) a += f0Buf[i] * f0Buf[i + k]
            f0Score[k - (F0_MIN_LAG - 1)] = a
            if (k in F0_MIN_LAG..F0_MAX_LAG && a > best) { best = a; bk = k }
        }
        if (bk > 0 && best / ac0 > 0.5) {
            // parabolic interpolation around the peak gives a finer lag than whole samples
            val j = bk - (F0_MIN_LAG - 1)
            val y0 = f0Score[j - 1]; val y1 = f0Score[j]; val y2 = f0Score[j + 1]
            val den = y0 - 2 * y1 + y2
            val d = if (den < 0.0) (0.5 * (y0 - y2) / den).coerceIn(-1.0, 1.0) else 0.0
            push(sr / (bk + d.toFloat()))
        }
    }

    private fun push(f: Float) {
        f0Hist[f0Pos] = f; f0Pos = (f0Pos + 1) % F0_KEEP; if (f0N < F0_KEEP) f0N++
    }

    private fun median(): Float {
        if (f0N < 12) return 0f
        val a = f0Hist.copyOf(f0N); a.sort(); return a[f0N / 2]
    }

    // -------------------------------------------------------------- tuning

    private fun retune(p: TunerParams) {
        // pitch: move slowly toward the reference
        val med = median()
        if (p.pitchAuto && med > 0f) {
            val want = (12.0 * ln((REF_F0 / med).toDouble()) / ln(2.0)).toFloat().coerceIn(-MAX_AUTO_SEMI, MAX_AUTO_SEMI)
            autoSemi += (want - autoSemi).coerceIn(-0.08f, 0.08f)
        } else if (!p.pitchAuto) autoSemi += (0f - autoSemi).coerceIn(-0.08f, 0.08f)
        val shiftRatio = 2.0.pow(semitones(p) / 12.0)

        // tone: the player will raise every frequency by shiftRatio after this EQ, so aim the EQ at the reference seen from there
        val total = energy.sum()
        val trust = (spoken / 2f).coerceIn(0f, 1f)          // fades in over the first 2 s of speech
        if (total > 0.0 && trust > 0f) {
            val meas = FloatArray(NB) { (10.0 * log10(max(energy[it], 1e-12) / total)).toFloat() }
            val want = FloatArray(NB)
            var mean = 0f
            for (k in 0 until NB) {
                val tgt = refAt((centers[k] * shiftRatio).toFloat())
                want[k] = tgt - meas[k]; mean += want[k]
            }
            mean /= NB
            for (k in 0 until NB) {
                val g = ((want[k] - mean) * p.match.coerceIn(0f, 1f) * trust).coerceIn(-MAX_EQ_DB, MAX_EQ_DB)
                eqDb[k] += (g - eqDb[k]).coerceIn(-0.35f, 0.35f)
                eq[k].peaking(centers[k], 1.2, eqDb[k].toDouble(), sr)
            }
        } else for (k in 0 until NB) if (eqDb[k] != 0f) { eqDb[k] = 0f; eq[k].peaking(centers[k], 1.2, 0.0, sr) }

        if (p.warmth != lastWarmth) { shelfLo.lowShelf(180.0, p.warmth.coerceIn(-9f, 9f).toDouble(), sr); lastWarmth = p.warmth }
        if (p.presence != lastPresence) { shelfHi.highShelf(3500.0, p.presence.coerceIn(-9f, 9f).toDouble(), sr); lastPresence = p.presence }
    }

    /** Reference level (dB) at a frequency, interpolated across the band centres on a log-frequency axis. */
    private fun refAt(hz: Float): Float {
        val lf = ln(hz.toDouble())
        if (lf <= ln(centers[0])) return REF_BAND_DB[0]
        if (lf >= ln(centers[NB - 1])) return REF_BAND_DB[NB - 1]
        for (k in 0 until NB - 1) {
            val a = ln(centers[k]); val b = ln(centers[k + 1])
            if (lf in a..b) { val t = ((lf - a) / (b - a)).toFloat(); return REF_BAND_DB[k] * (1 - t) + REF_BAND_DB[k + 1] * t }
        }
        return REF_BAND_DB[NB - 1]
    }

    /** Current equaliser gains in dB (for the screen / tests). */
    fun eqGains(): FloatArray = eqDb.copyOf()

    private fun limit(v: Float): Float {
        val a = abs(v); val th = 0.7f
        if (a <= th) return v
        val o = th + (1f - th) * tanh(((a - th) / (1f - th)).toDouble()).toFloat()
        return if (v < 0) -o else o
    }

    /** Small-room reverb: 4 combs and 2 all-passes, scaled to the sample rate. */
    private class Reverb(sr: Int) {
        private val cl = intArrayOf(1116, 1188, 1277, 1356).map { max(8, it * sr / 44100) }
        private val cb = cl.map { FloatArray(it) }
        private val cp = IntArray(4)
        private val cs = FloatArray(4)
        private val al = intArrayOf(556, 441).map { max(8, it * sr / 44100) }
        private val ab = al.map { FloatArray(it) }
        private val ap = IntArray(2)
        fun run(x: Float): Float {
            var s = 0f
            for (i in 0 until 4) {
                val o = cb[i][cp[i]]
                cs[i] = o * 0.7f + cs[i] * 0.3f
                cb[i][cp[i]] = x * 0.03f + cs[i] * 0.80f
                cp[i] = (cp[i] + 1) % cl[i]
                s += o
            }
            for (i in 0 until 2) {
                val b = ab[i][ap[i]]
                val o = -s + b
                ab[i][ap[i]] = s + b * 0.5f
                ap[i] = (ap[i] + 1) % al[i]
                s = o
            }
            return s
        }
    }
}
