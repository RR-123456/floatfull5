import java.io.*;
public class WavIO {
    public static float[] read(String path) throws IOException {
        byte[] b = java.nio.file.Files.readAllBytes(new File(path).toPath());
        int p = 12, dataOff = -1, dataLen = 0;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4); int len = (b[p+4]&0xFF)|((b[p+5]&0xFF)<<8)|((b[p+6]&0xFF)<<16)|((b[p+7]&0xFF)<<24);
            if (id.equals("data")) { dataOff = p + 8; dataLen = Math.min(len, b.length - dataOff); break; }
            p += 8 + len + (len & 1);
        }
        float[] x = new float[dataLen / 2];
        for (int i = 0; i < x.length; i++) x[i] = (short)((b[dataOff+2*i]&0xFF)|(b[dataOff+2*i+1]<<8)) / 32768f;
        return x;
    }
    public static void write(String path, float[] x, int sr) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(o);
        int n = x.length * 2;
        d.writeBytes("RIFF"); d.writeInt(Integer.reverseBytes(36 + n)); d.writeBytes("WAVEfmt ");
        d.writeInt(Integer.reverseBytes(16)); d.writeShort(Short.reverseBytes((short)1)); d.writeShort(Short.reverseBytes((short)1));
        d.writeInt(Integer.reverseBytes(sr)); d.writeInt(Integer.reverseBytes(sr*2)); d.writeShort(Short.reverseBytes((short)2)); d.writeShort(Short.reverseBytes((short)16));
        d.writeBytes("data"); d.writeInt(Integer.reverseBytes(n));
        for (float v : x) { int s = Math.round(v*32767f); s = Math.max(-32768, Math.min(32767, s)); d.writeShort(Short.reverseBytes((short)s)); }
        try (FileOutputStream f = new FileOutputStream(path)) { f.write(o.toByteArray()); }
    }
}
