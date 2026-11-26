import com.pragon.mobile.VoiceMorph;
import java.util.*;
/** Every manual parameter must actually change the sound, in the direction it says. */
public class MorphParamTest {
    static float[] go(float[] x, VoiceMorph.Params p) { return VoiceMorphTest.run(x, p, new VoiceMorph(), 2400); }
    public static void main(String[] a) throws Exception {
        float[] x = WavIO.read("/home/claude/ref/proxy_rb_up122.wav");
        float[] base = VoiceMorphTest.tail(go(x, new VoiceMorph.Params()), 0.4);
        float[] bd = VoiceMorph.ltasDb(base); double bf = VoiceMorph.medianF0(base);
        System.out.printf(Locale.US, "default: F0 %.1f Hz%n", bf);
        // manual equaliser: +8 dB at band 8 (~0.7 kHz share) and -8 dB at band 16 (~4.5 kHz)
        VoiceMorph.Params p = new VoiceMorph.Params(); p.eq[8] = 8f; p.eq[16] = -8f;
        float[] d = VoiceMorph.ltasDb(VoiceMorphTest.tail(go(x, p), 0.4));
        System.out.printf(Locale.US, "EQ band8 +8: share %+.1f dB, band16 -8: share %+.1f dB (band shares change relative to the total)%n", d[8]-bd[8], d[16]-bd[16]);
        // target pitch
        p = new VoiceMorph.Params(); p.targetF0 = 110f;
        System.out.printf(Locale.US, "targetF0 110: F0 %.1f Hz%n", VoiceMorph.medianF0(VoiceMorphTest.tail(go(x, p), 0.4)));
        p = new VoiceMorph.Params(); p.targetF0 = 160f;
        System.out.printf(Locale.US, "targetF0 160: F0 %.1f Hz%n", VoiceMorph.medianF0(VoiceMorphTest.tail(go(x, p), 0.4)));
        // pitch fine-tune
        p = new VoiceMorph.Params(); p.pitchSemi = 3f;
        System.out.printf(Locale.US, "pitchSemi +3: F0 %.1f Hz (default %.1f -> expect ~x1.19)%n", VoiceMorph.medianF0(VoiceMorphTest.tail(go(x, p), 0.4)), bf);
        // auto pitch off
        p = new VoiceMorph.Params(); p.pitchAuto = false;
        System.out.printf(Locale.US, "pitchAuto off: F0 %.1f Hz (input %.1f)%n", VoiceMorph.medianF0(VoiceMorphTest.tail(go(x, p), 0.4)), VoiceMorph.medianF0(x));
        // auto tone off -> no automatic eq
        p = new VoiceMorph.Params(); p.autoTone = false; VoiceMorph m = new VoiceMorph(); VoiceMorphTest.run(x, p, m, 2400);
        double s = 0; for (float g : m.eqGains()) s += Math.abs(g);
        System.out.printf(Locale.US, "autoTone off: sum |auto eq| = %.2f dB (should be 0)%n", s);
        // formant keep 0 vs 1: the spectral envelope should differ
        p = new VoiceMorph.Params(); p.formantKeep = 0f; float[] k0 = VoiceMorph.ltasDb(VoiceMorphTest.tail(go(x, p), 0.4));
        p = new VoiceMorph.Params(); p.formantKeep = 1f; float[] k1 = VoiceMorph.ltasDb(VoiceMorphTest.tail(go(x, p), 0.4));
        double dd = 0; for (int i = 0; i < k0.length; i++) dd += Math.abs(k0[i]-k1[i]); System.out.printf(Locale.US, "formantKeep 0 vs 1: mean band difference %.2f dB%n", dd / k0.length);
        // warmth / presence / gain
        p = new VoiceMorph.Params(); p.warmth = 6f; float[] w = VoiceMorph.ltasDb(VoiceMorphTest.tail(go(x, p), 0.4));
        System.out.printf(Locale.US, "warmth +6: lowest 3 bands %+.1f dB vs default%n", (w[0]+w[1]+w[2]-bd[0]-bd[1]-bd[2])/3);
        p = new VoiceMorph.Params(); p.gainDb = 6f; double r0 = VoiceMorphTest.rms(base), r1 = VoiceMorphTest.rms(VoiceMorphTest.tail(go(x, p), 0.4));
        System.out.printf(Locale.US, "gain +6: level change %+.1f dB%n", 20*Math.log10(r1/r0));
    }
}
