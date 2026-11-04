package app.shaz.stuffreader;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Piper neural voices, run on the phone with sherpa-onnx. Voices are downloaded on demand from
 * sherpa-onnx's GitHub releases (int8 builds, ~20 MB each) into the app's private storage.
 */
public class Piper {

    public static class Model {
        public final String id, name, accent;
        Model(String id, String name, String accent) { this.id = id; this.name = name; this.accent = accent; }
    }

    /** Female English Piper voices. */
    public static final Model[] CATALOG = {
        new Model("en_US-amy-medium", "Amy", "US"),
        new Model("en_US-hfc_female-medium", "HFC", "US"),
        new Model("en_US-kathleen-low", "Kathleen", "US"),
        new Model("en_US-kristin-medium", "Kristin", "US"),
        new Model("en_US-lessac-medium", "Lessac", "US"),
        new Model("en_US-ljspeech-medium", "LJ", "US"),
        new Model("en_GB-alba-medium", "Alba", "Scottish"),
        new Model("en_GB-cori-medium", "Cori", "UK"),
        new Model("en_GB-jenny_dioco-medium", "Jenny", "UK"),
        new Model("en_GB-southern_english_female-low", "Southern", "UK"),
    };

    public interface Progress { void update(int percent); }

    static String URL_FMT = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-%s-int8.tar.bz2";

    private final File root, espeak;
    private final Map<String, OfflineTts> loaded = new LinkedHashMap<String, OfflineTts>(4, 0.75f, true);
    private static Boolean libOk = null;
    private static String libError = null;

    public Piper(File filesDir) {
        root = new File(filesDir, "piper");
        root.mkdirs();
        espeak = new File(root, "espeak-ng-data");
    }

    public static Model find(String id) {
        for (Model m : CATALOG) if (m.id.equals(id)) return m;
        return null;
    }

    /** True if this phone can run Piper (the bundled native library is 64-bit ARM). */
    public static synchronized boolean available() {
        if (libOk == null) {
            try { Class.forName("com.k2fsa.sherpa.onnx.OfflineTts"); libOk = true; }
            catch (Throwable t) { libOk = false; libError = String.valueOf(t.getMessage()); }
        }
        return libOk;
    }

    public static String unavailableReason() { return libError; }

    private File dir(String id) { return new File(root, id); }

    private static File findOnnx(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".onnx")) return f;
        return null;
    }

    public boolean installed(String id) {
        File d = dir(id);
        return findOnnx(d) != null && new File(d, "tokens.txt").isFile() && new File(espeak, ".complete").isFile();
    }

    public synchronized void delete(String id) {
        OfflineTts t = loaded.remove(id);
        if (t != null) t.release();
        rmrf(dir(id));
    }

    /** Download and unpack a voice. Blocking; call off the UI thread. */
    public void download(String id, Progress p) throws IOException {
        File tmp = new File(root, id + ".part");
        rmrf(tmp);
        tmp.mkdirs();
        boolean needEspeak = !new File(espeak, ".complete").isFile();
        File espTmp = new File(root, "espeak.part");
        if (needEspeak) { rmrf(espTmp); espTmp.mkdirs(); }

        HttpURLConnection c = (HttpURLConnection) new URL(String.format(URL_FMT, id)).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("Download failed (HTTP " + code + ")");
        final long total = c.getContentLength();
        final Progress prog = p;
        InputStream raw = new FilterInputStream(new BufferedInputStream(c.getInputStream(), 65536)) {
            long got = 0; int last = -1;
            private void tick(long n) {
                if (n <= 0) return;
                got += n;
                if (total > 0 && prog != null) { int pc = (int) (got * 100 / total); if (pc != last) { last = pc; prog.update(pc); } }
            }
            @Override public int read() throws IOException { int b = super.read(); if (b >= 0) tick(1); return b; }
            @Override public int read(byte[] b, int o, int l) throws IOException { int n = super.read(b, o, l); tick(n); return n; }
        };
        try {
            TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(raw));
            TarArchiveEntry e;
            byte[] buf = new byte[65536];
            while ((e = tar.getNextTarEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;
                name = name.substring(slash + 1);   // strip the top-level folder
                if (name.contains("..")) continue;
                File out;
                if (name.startsWith("espeak-ng-data/")) {
                    if (!needEspeak) continue;
                    out = new File(espTmp, name.substring("espeak-ng-data/".length()));
                } else if (name.indexOf('/') < 0) {
                    out = new File(tmp, name);
                } else continue;
                out.getParentFile().mkdirs();
                OutputStream os = new BufferedOutputStream(new FileOutputStream(out));
                int n;
                while ((n = tar.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
            }
            tar.close();
        } finally {
            c.disconnect();
        }
        if (findOnnx(tmp) == null || !new File(tmp, "tokens.txt").isFile()) { rmrf(tmp); throw new IOException("Download was incomplete"); }
        if (needEspeak) {
            rmrf(espeak);
            if (!espTmp.renameTo(espeak)) throw new IOException("Couldn't save voice data");
            new FileOutputStream(new File(espeak, ".complete")).close();
        }
        rmrf(dir(id));
        if (!tmp.renameTo(dir(id))) throw new IOException("Couldn't save voice");
    }

    private synchronized OfflineTts engine(String id) {
        OfflineTts t = loaded.get(id);
        if (t != null) return t;
        File d = dir(id);
        OfflineTtsConfig cfg = new OfflineTtsConfig();
        cfg.model.vits.model = findOnnx(d).getAbsolutePath();
        cfg.model.vits.tokens = new File(d, "tokens.txt").getAbsolutePath();
        cfg.model.vits.dataDir = espeak.getAbsolutePath();
        cfg.model.numThreads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        t = new OfflineTts(cfg);
        loaded.put(id, t);
        while (loaded.size() > 3) {   // keep memory in check: drop the least recently used voice
            String oldest = loaded.keySet().iterator().next();
            loaded.remove(oldest).release();
        }
        return t;
    }

    /** Speak {@code text} with voice {@code id} into a WAV file. Blocking. */
    public synchronized void synth(String id, String text, File out) throws IOException {
        if (!available()) throw new IOException("Piper isn't supported on this phone");
        if (!installed(id)) throw new IOException("Voice not downloaded");
        GeneratedAudio a = engine(id).generate(text, 0, 1.0f);
        writeWav(out, a.samples, a.sampleRate);
    }

    public synchronized void releaseAll() {
        for (OfflineTts t : loaded.values()) t.release();
        loaded.clear();
    }

    static void writeWav(File f, float[] s, int rate) throws IOException {
        int dataLen = s.length * 2;
        byte[] b = new byte[44 + dataLen];
        putStr(b, 0, "RIFF"); putInt(b, 4, 36 + dataLen); putStr(b, 8, "WAVE");
        putStr(b, 12, "fmt "); putInt(b, 16, 16); putShort(b, 20, 1); putShort(b, 22, 1);
        putInt(b, 24, rate); putInt(b, 28, rate * 2); putShort(b, 32, 2); putShort(b, 34, 16);
        putStr(b, 36, "data"); putInt(b, 40, dataLen);
        for (int i = 0; i < s.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, s[i])) * 32767f);
            b[44 + 2 * i] = (byte) v;
            b[45 + 2 * i] = (byte) (v >> 8);
        }
        FileOutputStream os = new FileOutputStream(f);
        os.write(b);
        os.close();
    }

    private static void putStr(byte[] b, int o, String s) { for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i); }
    private static void putInt(byte[] b, int o, int v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i)); }
    private static void putShort(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }

    static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) rmrf(k);
        f.delete();
    }
}
