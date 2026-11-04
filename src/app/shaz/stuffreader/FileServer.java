package app.shaz.stuffreader;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Locale;

/** Tiny HTTP server so a Cast device can fetch the spoken-line WAV files from the phone. */
public class FileServer {
    private final File dir;
    private ServerSocket ss;
    private volatile boolean running;

    public FileServer(File dir) { this.dir = dir; }

    public synchronized int start() throws Exception {
        if (ss != null) return ss.getLocalPort();
        ss = new ServerSocket(0);
        running = true;
        new Thread(new Runnable() { public void run() {
            while (running) {
                try {
                    final Socket s = ss.accept();
                    new Thread(new Runnable() { public void run() { handle(s); } }).start();
                } catch (Exception e) { if (!running) return; }
            }
        }}, "http").start();
        return ss.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (Exception ignored) { }
        ss = null;
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            StringBuilder hdr = new StringBuilder();
            int c, n = 0;
            while ((c = in.read()) != -1 && n++ < 16384) {
                hdr.append((char) c);
                if (hdr.length() >= 4 && hdr.substring(hdr.length() - 4).equals("\r\n\r\n")) break;
            }
            String[] lines = hdr.toString().split("\r\n");
            String[] req = lines[0].split(" ");
            String method = req[0];
            String path = req.length > 1 ? req[1] : "/";
            String range = null;
            for (String l : lines) if (l.toLowerCase(Locale.US).startsWith("range:")) range = l.substring(6).trim();

            String name = path.startsWith("/") ? path.substring(1) : path;
            File f = new File(dir, name);
            if (!name.matches("[A-Za-z0-9_.-]+") || !f.isFile()) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                out.flush(); s.close(); return;
            }
            long len = f.length(), start = 0, end = len - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=")) {
                String[] r = range.substring(6).split("-", -1);
                try {
                    if (r[0].length() > 0) start = Long.parseLong(r[0].trim());
                    if (r.length > 1 && r[1].trim().length() > 0) end = Math.min(len - 1, Long.parseLong(r[1].trim()));
                    partial = true;
                } catch (NumberFormatException ignored) { }
            }
            long count = Math.max(0, end - start + 1);
            StringBuilder h = new StringBuilder();
            h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: audio/wav\r\n");
            h.append("Content-Length: ").append(count).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n");
            h.append("Access-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n");
            out.write(h.toString().getBytes("US-ASCII"));
            if (!"HEAD".equals(method)) {
                RandomAccessFile raf = new RandomAccessFile(f, "r");
                raf.seek(start);
                byte[] buf = new byte[32768];
                long left = count;
                while (left > 0) {
                    int r = raf.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) break;
                    out.write(buf, 0, r);
                    left -= r;
                }
                raf.close();
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** The phone's Wi-Fi IPv4 address, or null. */
    public static String localIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ni.getName().startsWith("wlan")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }
}
