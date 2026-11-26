package com.pragon.mobile;

/**
 * v36 voice morph engine. Plain Java (no Android) so it can be compiled and tested on a laptop.
 *
 * Goal: Gemini's "Charon" voice should be HEARD as the Pragon reference voice (the "assistant voice" clip).
 * Runs on every 24 kHz 16-bit mono chunk before it is played. Three stages, in this order:
 *
 *   1. PITCH. A delay-line pitch shifter with pitch-synchronous splices (it repeats or drops whole voice periods and
 *      crossfades), so the voice really moves to the reference pitch. It does not depend on the phone honouring
 *      AudioTrack playback pitch (many ignore it on a call stream, which is why v35 sounded unchanged).
 *   2. VOICE SIZE (formants). Moving the pitch drags the vocal-tract resonances along ("chipmunk / giant"). A cepstral
 *      envelope warp moves them back, most of the way (FORMANT_KEEP).
 *   3. TONE. A 21-band, smooth, per-bin spectral gain pulls Charon's long-term average spectrum onto the reference
 *      voice's, closed loop (it measures its own output, so it cannot over- or under-shoot).
 *
 * Then optional colour (warmth, presence, metallic, space), loudness and a soft limiter.
 * REF_BAND_DB / REF_F0 were measured from the reference clips with this very code (see tests/VoiceMorphTest.java).
 */
public final class VoiceMorph {

    public static final int SR = 24000;

    /** Settings. Same meaning as the v35 tuner knobs, plus formant and pitchMode. */
    public static final class Params {
        public boolean on = true;
        /** 0..1: how far the tone is pulled toward the reference. */
        public float match = 0.85f;
        /** Move Charon's average pitch to the reference pitch by itself. */
        public boolean pitchAuto = true;
        /** Extra pitch on top of the automatic match, semitones (-6..6). */
        public float pitchSemi = 0f;
        /** Extra voice size (formants), semitones (-6..6). Up = smaller vocal tract, brighter. */
        public float formant = 0f;
        /** Low shelf ~180 Hz, dB (-6..6). */
        public float warmth = 0f;
        /** High shelf ~3.5 kHz, dB (-6..6). */
        public float presence = 0f;
        /** 0..1 short comb filter (synthetic edge). */
        public float metallic = 0f;
        /** 0..1 small room. */
        public float space = 0f;
        /** Output level, dB (-6..6). */
        public float gainDb = 0f;
        /** 0 = this engine shifts the pitch (default). 1 = the phone's player does (AudioTrack playback pitch). */
        public int pitchMode = 0;
        /** Pitch the automatic match aims for, Hz. 0 = the reference voice's pitch. */
        public float targetF0 = 0f;
        /** 0..1: how much of the pitch move's effect on the voice size is undone (0 = size follows pitch, 1 = size stays put). */
        public float formantKeep = 0.65f;
        /** Automatic tone match on/off. Off = only the manual equaliser below shapes the tone. */
        public boolean autoTone = true;
        /** Manual 21-band equaliser, dB per band (-12..12), added on top of the automatic tone match. */
        public final float[] eq = new float[NB];
    }

    // ------------------------------------------------------------------ reference (measured with this code)
    static final float[] EDGES = {70f, 100f, 140f, 190f, 250f, 320f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
            2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12000f};
    public static final int NB = EDGES.length - 1;
    /** Reference voice: each band's share of the total energy, dB (smoothed 0.4 octave), speech frames only. */
    public static final float[] REF_BAND_DB = {-14.26f, -14.07f, -12.37f, -10.89f, -9.62f, -9.25f, -10.04f, -11.43f, -11.94f, -11.70f, -12.14f, -13.93f, -16.51f, -18.34f, -18.94f, -18.10f, -16.62f, -15.96f, -16.72f, -18.56f, -20.32f};
    /** Reference voice: median pitch, Hz (measured with this file's pitch method). */
    public static final float REF_F0 = 137.3f;

    // ------------------------------------------------------------------ constants
    static final int N = 1024, HOP = 256, HALF = N / 2;
    private static final float GATE_RMS = 0.01f;
    private static final float F0_RMS = 0.03f;
    private static final int F0_FRAME = 960, F0_HOP = 480, F0_MIN_LAG = 80, F0_MAX_LAG = 342, F0_KEEP = 800;
    private static final float MAX_EQ_DB = 9f;
    private static final float MAX_AUTO_SEMI = 6f;
    private static final float TAU_SEC = 12f;
    /** How much of the pitch-shift's formant movement is undone (0 = formants ride the pitch, 1 = formants stay put). */
    static final float FORMANT_KEEP = 0.65f;
    private static final int LIFTER = 40;
    // delay-line shifter
    private static final int RING = 1 << 15, RMASK = RING - 1;
    private static final int D_LO = 900, D_HI = 1600, D_MID = 1250, XF = 160;

    // ------------------------------------------------------------------ FFT
    private static final class Fft {
        final int n; final float[] cs, sn; final int[] rev;
        Fft(int n) {
            this.n = n; cs = new float[n / 2]; sn = new float[n / 2]; rev = new int[n];
            for (int i = 0; i < n / 2; i++) { cs[i] = (float) Math.cos(2 * Math.PI * i / n); sn[i] = (float) -Math.sin(2 * Math.PI * i / n); }
            int bits = Integer.numberOfTrailingZeros(n);
            for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        }
        void run(float[] re, float[] im, boolean inv) {
            for (int i = 0; i < n; i++) { int j = rev[i]; if (j > i) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
            for (int len = 2; len <= n; len <<= 1) {
                int half = len >> 1, step = n / len;
                for (int i = 0; i < n; i += len) {
                    for (int j = 0, k = 0; j < half; j++, k += step) {
                        float wr = cs[k], wi = inv ? -sn[k] : sn[k];
                        int a = i + j, b = a + half;
                        float xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                    }
                }
            }
            if (inv) { float s = 1f / n; for (int i = 0; i < n; i++) { re[i] *= s; im[i] *= s; } }
        }
    }

    // ------------------------------------------------------------------ state
    private final Fft fft = new Fft(N);
    private final float[] win = new float[N];
    private final double[] centers = new double[NB];
    private final int[] binBand = new int[HALF + 1];
    private final double[] binLogF = new double[HALF + 1];
    private final float[] re = new float[N], im = new float[N], mag = new float[HALF + 1], lg = new float[HALF + 1];
    private final float[] env = new float[HALF + 1], warpG = new float[HALF + 1], toneG = new float[HALF + 1];
    private final float[] stIn = new float[N], ola = new float[N];
    private int stCnt = 0;
    private final float[] outQ = new float[HOP * 4];
    private int outHead = 0, outLen = 0;

    // heard
    private final double[] energy = new double[NB];
    private int frames = 0;
    private float spoken = 0f;
    private final float[] eqDb = new float[NB];

    // pitch
    private final float[] f0Hist = new float[F0_KEEP];
    private int f0N = 0, f0Pos = 0, f0Fill = 0;
    private final float[] f0Buf = new float[F0_FRAME];
    private final float[] f0Tmp = new float[F0_FRAME];
    private final double[] acR = new double[F0_MAX_LAG + 3];
    private double acScore = 0;
    private float autoSemi = 0f;
    private float rhoNow = 1f;

    // delay line shifter
    private final float[] ring = new float[RING];
    private long w = 0;
    private double r = -D_MID, r2 = 0;
    private int xfLeft = 0;

    // colour
    private final float[] comb = new float[SR / 50];
    private int combPos = 0;
    private final Reverb verb = new Reverb(SR);
    private boolean dirty = false;
    private volatile boolean dropAsked = false;

    public VoiceMorph() {
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        for (int b = 0; b < NB; b++) centers[b] = Math.sqrt((double) EDGES[b] * EDGES[b + 1]);
        for (int k = 0; k <= HALF; k++) {
            double f = (double) k * SR / N; binLogF[k] = Math.log(Math.max(f, 1.0));
            int b = -1;
            for (int j = 0; j < NB; j++) if (f >= EDGES[j] && f < EDGES[j + 1]) { b = j; break; }
            binBand[k] = b;
        }
        java.util.Arrays.fill(toneG, 1f); java.util.Arrays.fill(warpG, 1f);
    }

    // ------------------------------------------------------------------ public API

    /** Total pitch shift currently applied or requested, semitones. */
    public float semitones(Params p) {
        if (!p.on) return 0f;
        float s = (p.pitchAuto ? autoSemi : 0f) + p.pitchSemi;
        return Math.max(-8f, Math.min(8f, s));
    }
    public float heardSeconds() { return spoken; }
    public float heardF0() { return median(); }

    public void reset() {
        java.util.Arrays.fill(energy, 0.0); frames = 0; spoken = 0f; f0N = 0; f0Pos = 0; f0Fill = 0; autoSemi = 0f;
        java.util.Arrays.fill(eqDb, 0f);
    }

    /** What has been heard so far, so the next conversation starts already tuned. */
    public float[] exportState() {
        float[] s = new float[NB + 5];
        s[0] = 36f;
        for (int i = 0; i < NB; i++) s[1 + i] = (float) energy[i];
        s[NB + 1] = spoken; s[NB + 2] = median(); s[NB + 3] = autoSemi; s[NB + 4] = frames;
        return s;
    }

    public void importState(float[] s) {
        if (s == null || s.length != NB + 5 || s[0] != 36f) return;
        for (int i = 0; i < NB; i++) energy[i] = s[1 + i];
        spoken = s[NB + 1]; autoSemi = Math.max(-MAX_AUTO_SEMI, Math.min(MAX_AUTO_SEMI, s[NB + 3])); frames = (int) s[NB + 4];
        float f = s[NB + 2];
        if (f > 50f && f < 400f) for (int k = 0; k < 20; k++) push(f);
        rhoNow = (float) Math.pow(2.0, autoSemi / 12.0);
    }

    /** The listener interrupted: forget the audio still inside the delay lines (safe to call from another thread). */
    public void requestDrop() { dropAsked = true; }

    private void dropNow() {
        dropAsked = false; dirty = false;
        java.util.Arrays.fill(ring, 0f); r = w - D_MID; xfLeft = 0;
        java.util.Arrays.fill(stIn, 0f); java.util.Arrays.fill(ola, 0f); stCnt = 0; outHead = 0; outLen = 0;
        f0Fill = 0;
    }

    /** True when some audio is still inside the delay lines (call flush() after a pause). */
    public boolean pending() { return dirty; }

    /** Pushes silence through so the last words are not left inside the delay lines. Returns PCM to play. */
    public byte[] flush(Params p) {
        if (dropAsked) dropNow();
        dirty = false;
        if (!p.on) return new byte[0];
        int n = D_HI + 400 + N + HOP;
        byte[] z = new byte[n * 2];
        byte[] o = process(z, p);
        dirty = false;
        return o;
    }

    /** One chunk of 16-bit little-endian mono PCM in, same length out. */
    public byte[] process(byte[] pcm, Params p) {
        if (!p.on || pcm.length < 2) return pcm;
        int n = pcm.length / 2;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8)) / 32768f;
        float[] y = processFloat(x, p);
        byte[] out = new byte[pcm.length];
        for (int i = 0; i < n; i++) {
            int s = Math.round(y[i] * 32767f);
            if (s > 32767) s = 32767; else if (s < -32768) s = -32768;
            out[2 * i] = (byte) (s & 0xFF); out[2 * i + 1] = (byte) ((s >> 8) & 0xFF);
        }
        dirty = true;
        return out;
    }

    public float[] processFloat(float[] x, Params p) {
        if (dropAsked) dropNow();
        int n = x.length;
        double sum = 0; for (float v : x) sum += v * v;
        float rms = (float) Math.sqrt(sum / Math.max(1, n));
        listenPitch(x, rms);
        updatePitchTarget(p, n / (float) SR);

        float rho = (p.pitchMode == 0) ? rhoNow : 1f;
        float gain = (float) Math.pow(10.0, p.gainDb / 20.0);
        float combAmt = Math.max(0f, Math.min(1f, p.metallic)) * 0.45f;
        int dly = (int) (SR * 0.007f);
        float wet = Math.max(0f, Math.min(1f, p.space)) * 0.28f;
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            float s = shiftSample(x[i], rho);
            s = stftSample(s, p);
            if (combAmt > 0f) {
                float d = comb[(combPos - dly + comb.length) % comb.length];
                comb[combPos] = s; combPos = (combPos + 1) % comb.length; s += combAmt * d;
            } else { comb[combPos] = s; combPos = (combPos + 1) % comb.length; }
            if (wet > 0f) s = s * (1f - wet * 0.5f) + verb.run(s) * wet;
            y[i] = limit(s * gain);
        }
        return y;
    }

    // ------------------------------------------------------------------ pitch listening

    private void listenPitch(float[] x, float rms) {
        if (rms > GATE_RMS) {
            spokenSeconds(x.length);
            for (float v : x) {
                f0Buf[f0Fill++] = v;
                if (f0Fill == F0_FRAME) {
                    System.arraycopy(f0Buf, 0, f0Tmp, 0, F0_FRAME);
                    double lag = autoPeriod(f0Tmp, F0_FRAME, F0_RMS);
                    if (lag > 0 && acScore > 0.5) push((float) (SR / lag));
                    System.arraycopy(f0Buf, F0_HOP, f0Buf, 0, F0_FRAME - F0_HOP);
                    f0Fill = F0_FRAME - F0_HOP;
                }
            }
        } else f0Fill = 0;
    }

    private void spokenSeconds(int samples) { /* the tone stage counts speech time; this keeps pitch-only runs honest too */ }

    /**
     * Fractional period (samples) of a frame by autocorrelation, or 0 when not periodic or too quiet. Sets acScore (0..1).
     * Picks the shortest strong peak so an octave-low error (two periods) is not chosen. Destroys f (mean removed).
     */
    private double autoPeriod(float[] f, int len, float minRms) {
        double m = 0; for (int i = 0; i < len; i++) m += f[i]; m /= len;
        double ac0 = 0;
        for (int i = 0; i < len; i++) { f[i] -= (float) m; ac0 += f[i] * f[i]; }
        acScore = 0;
        if (ac0 <= 0 || Math.sqrt(ac0 / len) < minRms) return 0;
        double best = 0;
        for (int k = F0_MIN_LAG - 1; k <= F0_MAX_LAG + 1; k++) {
            double a = 0; int lim = len - k;
            for (int i = 0; i < lim; i++) a += f[i] * f[i + k];
            acR[k] = a;
            if (k >= F0_MIN_LAG && k <= F0_MAX_LAG && a > best) best = a;
        }
        if (best <= 0) return 0;
        int bk = 0;
        for (int k = F0_MIN_LAG; k <= F0_MAX_LAG; k++) {
            if (acR[k] >= 0.9 * best && acR[k] >= acR[k - 1] && acR[k] >= acR[k + 1]) { bk = k; break; }
        }
        if (bk == 0) return 0;
        acScore = acR[bk] / ac0;
        double y0 = acR[bk - 1], y1 = acR[bk], y2 = acR[bk + 1], den = y0 - 2 * y1 + y2;
        double d = den < 0 ? Math.max(-1, Math.min(1, 0.5 * (y0 - y2) / den)) : 0;
        return bk + d;
    }

    private void push(float f) { f0Hist[f0Pos] = f; f0Pos = (f0Pos + 1) % F0_KEEP; if (f0N < F0_KEEP) f0N++; }

    private float median() {
        if (f0N < 12) return 0f;
        float[] a = java.util.Arrays.copyOf(f0Hist, f0N); java.util.Arrays.sort(a); return a[f0N / 2];
    }

    private void updatePitchTarget(Params p, float secs) {
        float med = median();
        float want = 0f;
        if (p.pitchAuto && med > 0f) {
            float tgt = p.targetF0 > 40f ? p.targetF0 : REF_F0;
            want = (float) (12.0 * Math.log(tgt / med) / Math.log(2.0));
            want = Math.max(-MAX_AUTO_SEMI, Math.min(MAX_AUTO_SEMI, want));
        } else if (p.pitchAuto) want = autoSemi;      // not enough heard yet: hold
        float step = 2.0f * secs;                      // semitones per second the shift may move
        autoSemi += Math.max(-step, Math.min(step, want - autoSemi));
        float target = semitones(p);
        float cur = (float) (12.0 * Math.log(rhoNow) / Math.log(2.0));
        cur += Math.max(-step, Math.min(step, target - cur));
        rhoNow = (float) Math.pow(2.0, cur / 12.0);
    }

    /** Playback pitch multiplier for the phone's player, used only in pitchMode 1. */
    public float playerRatio(Params p) {
        return p.pitchMode == 1 ? Math.max(0.6f, Math.min(1.6f, rhoNow)) : 1f;
    }

    // ------------------------------------------------------------------ stage 1: pitch shifter (delay line + period splices)

    private float readRing(double pos) {
        double fl = Math.floor(pos); int i = (int) fl; float t = (float) (pos - fl);
        float a = ring[(i - 1) & RMASK], b = ring[i & RMASK], c = ring[(i + 1) & RMASK], d = ring[(i + 2) & RMASK];
        return b + 0.5f * t * (c - a + t * (2f * a - 5f * b + 4f * c - d + t * (3f * (b - c) + d - a)));
    }

    private float shiftSample(float x, float rho) {
        ring[(int) (w & RMASK)] = x; w++;
        if (xfLeft == 0 && Math.abs(rho - 1f) > 1e-4f) {
            double d = w - r;
            if (d < D_LO) startSplice(+1); else if (d > D_HI) startSplice(-1);
        }
        float y = readRing(r);
        r += rho;
        if (xfLeft > 0) {
            float y2 = readRing(r2); r2 += rho;
            float a = (float) (0.5 - 0.5 * Math.cos(Math.PI * (XF - xfLeft + 1) / XF));
            y = y * (1f - a) + y2 * a;
            if (--xfLeft == 0) r = r2;
        }
        return y;
    }

    /** dir +1: jump back (the read head caught up with the writer), -1: jump ahead. Whole periods, then a fine search. */
    private void startSplice(int dir) {
        int ri = (int) Math.round(r);
        int len = F0_FRAME;
        for (int i = 0; i < len; i++) f0Tmp[i] = ring[(ri - len / 2 + i) & RMASK];
        double lag = autoPeriod(f0Tmp, len, 0.004f);
        boolean voiced = lag > 0 && acScore > 0.35;
        int T = voiced ? (int) Math.round(lag) : 240;
        int m = Math.max(1, (int) Math.round(160.0 / T));
        int jump = m * T;
        int cand = ri - dir * jump;
        if (voiced) {
            double bestC = -1e30; int bestD = 0;
            for (int dl = -6; dl <= 6; dl++) {
                double ab = 0, aa = 0, bb = 0;
                for (int i = 0; i < XF; i += 2) {
                    float u = ring[(ri + i) & RMASK], v = ring[(cand + dl + i) & RMASK];
                    ab += u * v; aa += u * u; bb += v * v;
                }
                double c = ab / Math.sqrt(aa * bb + 1e-12);
                if (c > bestC) { bestC = c; bestD = dl; }
            }
            cand += bestD;
        }
        // keep the new head inside the valid history and behind the writer
        double dNew = w - cand;
        if (dNew < 300 || dNew > RING - 4000) return;
        r2 = cand; xfLeft = XF;
    }

    // ------------------------------------------------------------------ stages 2 and 3: STFT (formant warp + tone match)

    private float stftSample(float s, Params p) {
        stIn[N - HOP + stCnt] = s;
        stCnt++;
        if (stCnt == HOP) {
            frame(p);
            // the first HOP samples of ola are complete
            for (int i = 0; i < HOP; i++) { int idx = (outHead + outLen) % outQ.length; outQ[idx] = ola[i] * (1f / 1.5f); outLen++; }
            System.arraycopy(ola, HOP, ola, 0, N - HOP);
            java.util.Arrays.fill(ola, N - HOP, N, 0f);
            System.arraycopy(stIn, HOP, stIn, 0, N - HOP);
            stCnt = 0;
        }
        if (outLen == 0) return 0f;
        float o = outQ[outHead]; outHead = (outHead + 1) % outQ.length; outLen--;
        return o;
    }

    private void frame(Params p) {
        double sq = 0; for (int i = 0; i < N; i++) sq += stIn[i] * stIn[i];
        boolean loud = Math.sqrt(sq / N) > GATE_RMS;
        for (int i = 0; i < N; i++) { re[i] = stIn[i] * win[i]; im[i] = 0f; }
        fft.run(re, im, false);
        for (int k = 0; k <= HALF; k++) mag[k] = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) + 1e-9f;

        // formant warp (cepstral envelope)
        double ratio = formantRatio(p);
        boolean warp = loud && Math.abs(Math.log(ratio)) > 0.01;
        if (warp) {
            for (int k = 0; k <= HALF; k++) lg[k] = (float) Math.log(mag[k]);
            envelope(lg, env);
            for (int k = 0; k <= HALF; k++) {
                double src = k / ratio; int i0 = (int) Math.floor(src);
                float e2;
                if (i0 >= HALF) e2 = env[HALF]; else { float t = (float) (src - i0); e2 = env[i0] * (1 - t) + env[i0 + 1] * t; }
                float g = e2 - env[k];
                if (g > 1.5f) g = 1.5f; else if (g < -1.5f) g = -1.5f;
                warpG[k] = (float) Math.exp(g);
            }
        } else java.util.Arrays.fill(warpG, 1f);

        // listen to what comes out of the warp
        if (loud) {
            double[] e = new double[NB];
            for (int k = 0; k <= HALF; k++) { int b = binBand[k]; if (b >= 0) { float m2 = mag[k] * warpG[k]; e[b] += (double) m2 * m2; } }
            double dt = HOP / (double) SR;
            frames++;
            double wgt = Math.max(1.0 / frames, dt / TAU_SEC);
            for (int b = 0; b < NB; b++) energy[b] = energy[b] * (1 - wgt) + e[b] * wgt;
            spoken += (float) dt;
        }
        retune(p);

        for (int k = 0; k <= HALF; k++) {
            float g = warpG[k] * toneG[k];
            re[k] *= g; im[k] *= g;
            if (k > 0 && k < HALF) { re[N - k] = re[k]; im[N - k] = -im[k]; }
        }
        fft.run(re, im, true);
        for (int i = 0; i < N; i++) ola[i] += re[i] * win[i];
    }

    /** Smooth log-magnitude envelope by cepstral liftering. */
    private final float[] cr = new float[N], ci = new float[N];
    private void envelope(float[] logm, float[] outEnv) {
        for (int k = 0; k <= HALF; k++) { cr[k] = logm[k]; ci[k] = 0f; }
        for (int k = 1; k < HALF; k++) { cr[N - k] = logm[k]; ci[N - k] = 0f; }
        fft.run(cr, ci, true);
        for (int q = 0; q < N; q++) {
            int qq = q <= HALF ? q : N - q;
            float l = qq <= LIFTER ? (float) (0.5 + 0.5 * Math.cos(Math.PI * qq / (LIFTER + 1))) : 0f;
            cr[q] *= l; ci[q] = 0f;
        }
        fft.run(cr, ci, false);
        for (int k = 0; k <= HALF; k++) outEnv[k] = cr[k];
    }

    /** How the formants have to move from where the pitch shifter leaves them (>1 = up). */
    private double formantRatio(Params p) {
        double rho = Math.max(0.6, Math.min(1.6, rhoNow));
        double keep = Math.max(0f, Math.min(1f, p.formantKeep));
        double c = Math.pow(rho, -keep) * Math.pow(2.0, p.formant / 12.0);
        // The shifter moved every formant by rho; we want them at rho^(1-FORMANT_KEEP) (times the user's offset).
        // In pitchMode 1 the player does the moving later and the same correction applies.
        return Math.max(0.6, Math.min(1.7, c));
    }

    private void retune(Params p) {
        double total = 0; for (int b = 0; b < NB; b++) total += energy[b];
        float trust = Math.max(0f, Math.min(1f, spoken / 4f));
        double rhoAfter = (p.pitchMode == 1) ? Math.max(0.6, Math.min(1.6, rhoNow)) : 1.0;
        if (total > 0 && trust > 0f) {
            float[] want = new float[NB]; float mean = 0f;
            for (int b = 0; b < NB; b++) {
                float meas = (float) (10.0 * Math.log10(Math.max(energy[b], 1e-12) / total));
                want[b] = refAt(centers[b] * rhoAfter) - meas; mean += want[b];
            }
            mean /= NB;
            float[] sm = new float[NB];
            for (int b = 0; b < NB; b++) {
                float a = want[Math.max(0, b - 1)], c = want[Math.min(NB - 1, b + 1)];
                sm[b] = 0.25f * a + 0.5f * want[b] + 0.25f * c - mean;
            }
            float mt = p.autoTone ? Math.max(0f, Math.min(1f, p.match)) * trust : 0f;
            for (int b = 0; b < NB; b++) {
                float g = Math.max(-MAX_EQ_DB, Math.min(MAX_EQ_DB, sm[b] * mt));
                eqDb[b] += Math.max(-0.15f, Math.min(0.15f, g - eqDb[b]));
            }
        } else for (int b = 0; b < NB; b++) eqDb[b] = 0f;
        float wm = Math.max(-9f, Math.min(9f, p.warmth)), pr = Math.max(-9f, Math.min(9f, p.presence));
        float[] tot = new float[NB];
        for (int b = 0; b < NB; b++) tot[b] = eqDb[b] + Math.max(-12f, Math.min(12f, p.eq[b]));
        for (int k = 0; k <= HALF; k++) {
            double lf = binLogF[k];
            float db;
            if (lf <= Math.log(centers[0])) db = tot[0];
            else if (lf >= Math.log(centers[NB - 1])) db = tot[NB - 1];
            else {
                db = tot[NB - 1];
                for (int b = 0; b < NB - 1; b++) {
                    double a = Math.log(centers[b]), c = Math.log(centers[b + 1]);
                    if (lf >= a && lf <= c) { float t = (float) ((lf - a) / (c - a)); db = tot[b] * (1 - t) + tot[b + 1] * t; break; }
                }
            }
            double f = (double) k * SR / N;
            if (wm != 0f) db += wm / (1f + (float) Math.pow(f / 180.0, 2));
            if (pr != 0f) { float h = (float) Math.pow(f / 3500.0, 2); db += pr * h / (1f + h); }
            toneG[k] = (float) Math.pow(10.0, db / 20.0);
        }
    }

    private float refAt(double hz) {
        double lf = Math.log(hz);
        if (lf <= Math.log(centers[0])) return REF_BAND_DB[0];
        if (lf >= Math.log(centers[NB - 1])) return REF_BAND_DB[NB - 1];
        for (int k = 0; k < NB - 1; k++) {
            double a = Math.log(centers[k]), b = Math.log(centers[k + 1]);
            if (lf >= a && lf <= b) { float t = (float) ((lf - a) / (b - a)); return REF_BAND_DB[k] * (1 - t) + REF_BAND_DB[k + 1] * t; }
        }
        return REF_BAND_DB[NB - 1];
    }

    /** Current automatic equaliser gains, dB per band (for the screen). */
    public float[] eqGains() { return eqDb.clone(); }
    /** Pitch shift being applied right now, semitones. */
    public float appliedSemitones() { return (float) (12.0 * Math.log(rhoNow) / Math.log(2.0)); }
    /** Centre frequency of each equaliser band, Hz. */
    public static float[] bandCenters() { float[] c = new float[NB]; for (int b = 0; b < NB; b++) c[b] = (float) Math.sqrt((double) EDGES[b] * EDGES[b + 1]); return c; }

    private static float limit(float v) {
        float a = Math.abs(v), th = 0.7f;
        if (a <= th) return v;
        float o = th + (1f - th) * (float) Math.tanh((a - th) / (1f - th));
        return v < 0 ? -o : o;
    }

    // ------------------------------------------------------------------ analysis helpers (used to measure the reference)

    /** Long-term band shares in dB (speech frames only), with the same frames, window and bands the live engine uses. */
    public static float[] ltasDb(float[] x) {
        Fft f = new Fft(N);
        float[] wn = new float[N]; for (int i = 0; i < N; i++) wn[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        double[] e = new double[NB];
        float[] r_ = new float[N], i_ = new float[N];
        for (int s = 0; s + N <= x.length; s += HOP) {
            double sq = 0; for (int i = 0; i < N; i++) sq += x[s + i] * x[s + i];
            if (Math.sqrt(sq / N) <= GATE_RMS) continue;
            for (int i = 0; i < N; i++) { r_[i] = x[s + i] * wn[i]; i_[i] = 0f; }
            f.run(r_, i_, false);
            for (int k = 0; k <= HALF; k++) {
                double hz = (double) k * SR / N;
                for (int b = 0; b < NB; b++) if (hz >= EDGES[b] && hz < EDGES[b + 1]) { e[b] += r_[k] * r_[k] + i_[k] * i_[k]; break; }
            }
        }
        double t = 0; for (double v : e) t += v;
        float[] out = new float[NB];
        for (int b = 0; b < NB; b++) out[b] = (float) (10.0 * Math.log10(Math.max(e[b], 1e-12) / t));
        return out;
    }

    /** Smooth band shares over frequency (energy domain, Gaussian in log-frequency, sigma in octaves). */
    public static float[] smoothDb(float[] db, double sigmaOct) {
        float[] out = new float[NB];
        for (int i = 0; i < NB; i++) {
            double ci = Math.log(Math.sqrt((double) EDGES[i] * EDGES[i + 1])) / Math.log(2), sw = 0, sv = 0;
            for (int j = 0; j < NB; j++) {
                double cj = Math.log(Math.sqrt((double) EDGES[j] * EDGES[j + 1])) / Math.log(2);
                double wt = Math.exp(-0.5 * Math.pow((cj - ci) / sigmaOct, 2));
                sw += wt; sv += wt * Math.pow(10.0, db[j] / 10.0);
            }
            out[i] = (float) (10.0 * Math.log10(sv / sw));
        }
        return out;
    }

    /** Median pitch of a whole recording with the live pitch method. */
    public static float medianF0(float[] x) {
        VoiceMorph m = new VoiceMorph();
        float[] tmp = new float[F0_FRAME];
        java.util.ArrayList<Float> fs = new java.util.ArrayList<Float>();
        for (int s = 0; s + F0_FRAME <= x.length; s += F0_HOP) {
            double sq = 0; for (int i = 0; i < F0_FRAME; i++) sq += x[s + i] * x[s + i];
            if (Math.sqrt(sq / F0_FRAME) <= GATE_RMS) continue;
            System.arraycopy(x, s, tmp, 0, F0_FRAME);
            double lag = m.autoPeriod(tmp, F0_FRAME, F0_RMS);
            if (lag > 0 && m.acScore > 0.5) fs.add((float) (SR / lag));
        }
        if (fs.isEmpty()) return 0f;
        java.util.Collections.sort(fs); return fs.get(fs.size() / 2);
    }

    // ------------------------------------------------------------------ small-room reverb

    private static final class Reverb {
        private final int[] cl = new int[4]; private final float[][] cb = new float[4][]; private final int[] cp = new int[4];
        private final float[] cs = new float[4];
        private final int[] al = new int[2]; private final float[][] ab = new float[2][]; private final int[] ap = new int[2];
        Reverb(int sr) {
            int[] c = {1116, 1188, 1277, 1356}, a = {556, 441};
            for (int i = 0; i < 4; i++) { cl[i] = Math.max(8, c[i] * sr / 44100); cb[i] = new float[cl[i]]; }
            for (int i = 0; i < 2; i++) { al[i] = Math.max(8, a[i] * sr / 44100); ab[i] = new float[al[i]]; }
        }
        float run(float x) {
            float s = 0f;
            for (int i = 0; i < 4; i++) {
                float o = cb[i][cp[i]]; cs[i] = o * 0.7f + cs[i] * 0.3f; cb[i][cp[i]] = x * 0.03f + cs[i] * 0.80f;
                cp[i] = (cp[i] + 1) % cl[i]; s += o;
            }
            for (int i = 0; i < 2; i++) {
                float b = ab[i][ap[i]]; float o = -s + b; ab[i][ap[i]] = s + b * 0.5f; ap[i] = (ap[i] + 1) % al[i]; s = o;
            }
            return s;
        }
    }
}
