import com.pragon.mobile.VoiceMorph;
import java.util.*;
public class RefTool {
    public static void main(String[] a) throws Exception {
        String[] f = {"/home/claude/ref/assistant-voice.wav"};
        float[] acc = new float[VoiceMorph.NB]; List<Float> f0s = new ArrayList<>();
        for (String p : f) {
            float[] x = WavIO.read(p);
            float[] d = VoiceMorph.ltasDb(x); float m = VoiceMorph.medianF0(x);
            System.err.println(p + " f0 " + m + " bands " + Arrays.toString(d));
            for (int i = 0; i < acc.length; i++) acc[i] += d[i] / f.length; f0s.add(m);
        }
        float[] sm = VoiceMorph.smoothDb(acc, 0.4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sm.length; i++) sb.append(String.format(Locale.US, "%.2ff%s", sm[i], i < sm.length - 1 ? ", " : ""));
        float f0 = f0s.get(0);
        System.out.println(sb); System.out.println(String.format(Locale.US, "%.1f", f0));
    }
}
