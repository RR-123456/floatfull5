import com.pragon.mobile.VoiceMorph;
import java.util.*;
/** Streams proxy "other voices" (made from the reference clip itself) through the engine in 100 ms chunks and scores the result. */
public class VoiceMorphTest {
    static final int SR = 24000;
    static float[] run(float[] x, VoiceMorph.Params p, VoiceMorph m, int chunk) {
        float[] out = new float[x.length + 4000]; int o = 0;
        for (int s = 0; s < x.length; s += chunk) {
            int n = Math.min(chunk, x.length - s);
            byte[] b = new byte[n * 2];
            for (int i = 0; i < n; i++) { int v = Math.round(x[s + i] * 32767f); b[2*i] = (byte)(v & 0xFF); b[2*i+1] = (byte)((v >> 8) & 0xFF); }
            byte[] y = m.process(b, p);
            for (int i = 0; i < n; i++) out[o++] = (short)((y[2*i]&0xFF)|(y[2*i+1]<<8)) / 32768f;
        }
        byte[] t = m.flush(p);
        for (int i = 0; i < t.length / 2 && o < out.length; i++) out[o++] = (short)((t[2*i]&0xFF)|(t[2*i+1]<<8)) / 32768f;
        return Arrays.copyOf(out, o);
    }
    static float[] tail(float[] x, double frac) { int s = (int)(x.length * frac); return Arrays.copyOfRange(x, s, x.length); }
    static double rmsDiff(float[] a, float[] b) { double s = 0; for (int i = 0; i < a.length; i++) s += (a[i]-b[i])*(a[i]-b[i]); return Math.sqrt(s / a.length); }
    static double semi(double a, double b) { return 12 * Math.log(a / b) / Math.log(2); }
    /** lag (samples) by which y is delayed against r, from the frame-energy envelopes. */
    static int lag(float[] r, float[] y) {
        int h = 120, n = Math.min(r.length, y.length) / h - 1;
        double[] er = new double[n], ey = new double[n];
        for (int i = 0; i < n; i++) { double a = 0, b = 0; for (int k = 0; k < h; k++) { a += r[i*h+k]*r[i*h+k]; b += y[i*h+k]*y[i*h+k]; } er[i] = Math.log(a+1e-9); ey[i] = Math.log(b+1e-9); }
        double best = -1e30; int bl = 0;
        for (int L = -5; L < 80; L++) {
            double c = 0; int cnt = 0;
            for (int i = 0; i < n; i++) { int j = i + L; if (j < 0 || j >= n) continue; c += er[i] * ey[j]; cnt++; }
            double mr = 0, my = 0; for (int i = 0; i < n; i++) { mr += er[i]; my += ey[i]; }
            // simple normalised correlation on the overlap
            double sa = 0, sb = 0, sab = 0, saa = 0, sbb = 0; cnt = 0;
            for (int i = 0; i < n; i++) { int j = i + L; if (j < 0 || j >= n) continue; sa += er[i]; sb += ey[j]; sab += er[i]*ey[j]; saa += er[i]*er[i]; sbb += ey[j]*ey[j]; cnt++; }
            double cov = sab - sa*sb/cnt, va = saa - sa*sa/cnt, vb = sbb - sb*sb/cnt;
            double cc = cov / Math.sqrt(va*vb + 1e-12);
            if (cc > best) { best = cc; bl = L; }
        }
        return bl * h;
    }
    /** per-frame spectral-envelope distance (dB RMS over the 21 bands, level-normalised) between aligned signals. */
    static double envDist(float[] ref, float[] y, int lag) {
        int s0 = Math.max(0, -lag); int n = Math.min(ref.length - s0, y.length - (s0 + lag)) ;
        int F = 2048; double acc = 0; int cnt = 0;
        for (int s = (int)(n * 0.4); s + F <= n; s += 1024) {
            float[] a = Arrays.copyOfRange(ref, s0 + s, s0 + s + F), b = Arrays.copyOfRange(y, s0 + s + lag, s0 + s + lag + F);
            if (rms(a) < 0.02 || rms(b) < 0.02) continue;
            float[] da = VoiceMorph.ltasDb(a), db = VoiceMorph.ltasDb(b);
            double ma = 0, mb = 0; for (int i = 0; i < da.length; i++) { ma += da[i]; mb += db[i]; } ma /= da.length; mb /= da.length;
            double d = 0; for (int i = 0; i < da.length; i++) d += Math.pow((da[i]-ma) - (db[i]-mb), 2);
            acc += Math.sqrt(d / da.length); cnt++;
        }
        return cnt == 0 ? Double.NaN : acc / cnt;
    }
    static double rms(float[] a) { double s = 0; for (float v : a) s += v*v; return Math.sqrt(s / a.length); }

    public static void main(String[] args) throws Exception {
        float[] ref = WavIO.read("/home/claude/ref/assistant-voice.wav");
        float[] refDb = VoiceMorph.ltasDb(ref); float refF0 = VoiceMorph.REF_F0;
        String[] names = args.length > 0 ? args : new String[]{"proxy_up130", "proxy_dn78", "proxy_up115_tilt", "proxy_rb_up122"};
        for (String nm : names) {
            float[] x = WavIO.read("/home/claude/ref/" + nm + ".wav");
            float[] xd = VoiceMorph.ltasDb(tail(x, 0.4));
            double f0in = VoiceMorph.medianF0(x);
            System.out.printf(Locale.US, "== %s: input F0 %.1f Hz (ref %.1f)  LTAS err vs ref %.2f dB  envDist(aligned) %.2f dB%n", nm, f0in, refF0, ltasErr(xd, VoiceMorph.ltasDb(tail(ref,0.4))), envDist(ref, x, 0));
            String[] modes = {"full", "pitch only", "tone only"};
            for (String mode : modes) {
                VoiceMorph.Params p = new VoiceMorph.Params();
                if (mode.equals("pitch only")) p.match = 0f;
                if (mode.equals("tone only")) p.pitchAuto = false;
                VoiceMorph m = new VoiceMorph();
                float[] y = run(x, p, m, 2400);
                WavIO.write("/home/claude/test/out_" + nm + "_" + mode.replace(' ', '_') + ".wav", y, SR);
                int L = lag(ref, y);
                float[] yt = tail(y, 0.4);
                double f0out = VoiceMorph.medianF0(yt);
                double e = ltasErr(VoiceMorph.ltasDb(yt), VoiceMorph.ltasDb(tail(ref, 0.4)));
                System.out.printf(Locale.US, "   %-10s F0 %.1f Hz (%+.2f st from ref)  LTAS err %.2f dB  envDist %.2f dB  lag %d smp  peak %.2f%n",
                        mode, f0out, semi(f0out, refF0), e, envDist(ref, y, L), L, peak(y));
            }
        }
    }
    static double peak(float[] y) { double m = 0; for (float v : y) m = Math.max(m, Math.abs(v)); return m; }
    static double ltasErr(float[] a, float[] b) { double ma = 0, mb = 0; for (int i = 0; i < a.length; i++) { ma += a[i]; mb += b[i]; } ma /= a.length; mb /= a.length; double s = 0; for (int i = 0; i < a.length; i++) s += Math.pow((a[i]-ma)-(b[i]-mb), 2); return Math.sqrt(s / a.length); }
}
