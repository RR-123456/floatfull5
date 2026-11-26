import com.pragon.mobile.VoiceMorph;
import java.util.*;
public class MorphRobustTest {
    public static void main(String[] a) throws Exception {
        float[] x = WavIO.read("/home/claude/ref/proxy_up130.wav");
        VoiceMorph.Params p = new VoiceMorph.Params();
        // 1. odd chunk sizes, timing, NaN check
        Random rnd = new Random(1); VoiceMorph m = new VoiceMorph(); long t0 = System.nanoTime(); int outN = 0; boolean bad = false; double pk = 0;
        for (int s = 0; s < x.length; ) {
            int n = Math.min(x.length - s, 100 + rnd.nextInt(3000)); byte[] b = new byte[n * 2];
            for (int i = 0; i < n; i++) { int v = Math.round(x[s+i]*32767f); b[2*i]=(byte)v; b[2*i+1]=(byte)(v>>8); }
            byte[] y = m.process(b, p); if (y.length != b.length) bad = true; outN += y.length / 2;
            for (int i = 0; i < y.length/2; i++) pk = Math.max(pk, Math.abs((short)((y[2*i]&0xFF)|(y[2*i+1]<<8))) / 32768.0);
            s += n;
        }
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf(Locale.US, "random chunks: length preserved=%b, peak %.2f, %.1f s of audio in %.2f s (%.0fx real time)%n", !bad, pk, x.length/24000.0, sec, x.length/24000.0/sec);
        // 2. tail: speech then silence; flush must bring the last words out
        float[] burst = Arrays.copyOf(x, 24000 * 3);
        VoiceMorph m2 = new VoiceMorph(); VoiceMorph.Params p2 = new VoiceMorph.Params();
        float[] y = VoiceMorphTest.run(burst, p2, m2, 2400);
        double e = 0; for (int i = burst.length; i < y.length; i++) e += y[i]*y[i];
        System.out.printf(Locale.US, "flush tail: %d extra samples out, energy in the tail %.4f (>0 means the last words were released)%n", y.length - burst.length, e);
        System.out.println("pending after flush: " + m2.pending());
        // 3. interrupt drops audio
        VoiceMorph m3 = new VoiceMorph(); float[] part = Arrays.copyOf(x, 24000);
        VoiceMorphTest.run(part, p2, m3, 2400); // includes flush; feed more then interrupt
        byte[] b = new byte[4800]; for (int i = 0; i < 2400; i++) { int v = Math.round(x[30000+i]*32767f); b[2*i]=(byte)v; b[2*i+1]=(byte)(v>>8); }
        m3.process(b, p2); m3.requestDrop(); byte[] t = m3.flush(p2); double et = 0; for (int i = 0; i < t.length/2; i++) { double v=(short)((t[2*i]&0xFF)|(t[2*i+1]<<8))/32768.0; et+=v*v; }
        System.out.printf(Locale.US, "after interrupt, flushed energy %.6f (should be ~0)%n", et);
        // 4. silence and off
        float[] z = new float[24000]; float[] yz = VoiceMorphTest.run(z, new VoiceMorph.Params(), new VoiceMorph(), 2400);
        double ez = 0; for (float v : yz) ez += v*v; System.out.println("silence in -> energy out " + ez);
        VoiceMorph.Params off = new VoiceMorph.Params(); off.on = false; byte[] src = new byte[200]; src[10]=5;
        System.out.println("off bypass returns same array: " + (new VoiceMorph().process(src, off) == src));
        // 5. pitchMode 1 (phone shifts): audio length ok, ratio reported
        VoiceMorph.Params p1 = new VoiceMorph.Params(); p1.pitchMode = 1; VoiceMorph m5 = new VoiceMorph();
        float[] y5 = VoiceMorphTest.run(x, p1, m5, 2400); System.out.printf(Locale.US, "pitchMode 1: player ratio %.3f (input is 1.3x the reference pitch)%n", m5.playerRatio(p1));
        // 6. saved state round trip
        VoiceMorph m6 = new VoiceMorph(); VoiceMorphTest.run(x, new VoiceMorph.Params(), m6, 2400);
        float[] st = m6.exportState(); VoiceMorph m7 = new VoiceMorph(); m7.importState(st);
        System.out.printf(Locale.US, "state restored: heard F0 %.1f Hz, semitones %.2f%n", m7.heardF0(), m7.semitones(new VoiceMorph.Params()));
    }
}
