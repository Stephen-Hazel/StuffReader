package app.shaz.stuffreader;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal Google Cast (CASTV2) sender: TLS socket + protobuf-framed JSON messages.
 * Launches the Default Media Receiver and plays audio URLs on it.
 */
public class CastClient {

    public interface Listener {
        void onReady();
        void onMediaStatus(JSONObject status);
        void onClosed(String reason);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String NS_CONN = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEART = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECV = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    private static final String APP_ID = "CC1AD845"; // Default Media Receiver
    private static final String SENDER = "sender-stuffreader";

    public final String name;
    private final String host;
    private final int port;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sendQ = Executors.newSingleThreadExecutor();

    private SSLSocket sock;
    private DataOutputStream out;
    private volatile boolean closed = false;
    private volatile String transportId, sessionId;
    private volatile long mediaSessionId = -1;
    private volatile double volume = -1;
    private int reqId = 1;

    public CastClient(String name, String host, int port, Listener l) {
        this.name = name; this.host = host; this.port = port; this.listener = l;
    }

    public boolean isReady() { return transportId != null && !closed; }
    public double getVolume() { return volume; }

    public void connect() {
        new Thread(new Runnable() { public void run() {
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{ new X509TrustManager() {   // Cast devices use self-signed certs
                    public void checkClientTrusted(X509Certificate[] c, String a) { }
                    public void checkServerTrusted(X509Certificate[] c, String a) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }}, null);
                sock = (SSLSocket) ctx.getSocketFactory().createSocket(host, port);
                sock.setSoTimeout(0);
                sock.startHandshake();
                out = new DataOutputStream(sock.getOutputStream());
                send("receiver-0", NS_CONN, new JSONObject().put("type", "CONNECT"));
                send("receiver-0", NS_RECV, new JSONObject().put("type", "LAUNCH").put("appId", APP_ID).put("requestId", nextId()));
                startHeartbeat();
                readLoop(new DataInputStream(sock.getInputStream()));
            } catch (Exception e) {
                fail("Lost connection to " + name);
            }
        }}, "cast-reader").start();
    }

    private synchronized int nextId() { return reqId++; }

    private void startHeartbeat() {
        new Thread(new Runnable() { public void run() {
            while (!closed) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                try { send("receiver-0", NS_HEART, new JSONObject().put("type", "PING")); } catch (Exception ignored) { }
            }
        }}, "cast-ping").start();
    }

    private void readLoop(DataInputStream in) throws Exception {
        while (!closed) {
            int len = in.readInt();
            if (len <= 0 || len > 1 << 20) throw new Exception("bad frame");
            byte[] buf = new byte[len];
            in.readFully(buf);
            String[] m = decode(buf); // source, dest, ns, payload
            if (m[3] == null) continue;
            JSONObject p = new JSONObject(m[3]);
            String type = p.optString("type");
            if (NS_HEART.equals(m[2])) {
                if ("PING".equals(type)) send(m[0], NS_HEART, new JSONObject().put("type", "PONG"));
            } else if (NS_RECV.equals(m[2])) {
                if ("RECEIVER_STATUS".equals(type)) onReceiverStatus(p.optJSONObject("status"));
                else if ("LAUNCH_ERROR".equals(type)) fail(name + " couldn't start the player");
            } else if (NS_MEDIA.equals(m[2])) {
                if ("MEDIA_STATUS".equals(type)) {
                    JSONArray st = p.optJSONArray("status");
                    if (st != null && st.length() > 0) {
                        final JSONObject s = st.getJSONObject(0);
                        if (s.has("mediaSessionId")) mediaSessionId = s.getLong("mediaSessionId");
                        main.post(new Runnable() { public void run() { if (!closed) listener.onMediaStatus(s); } });
                    }
                } else if ("LOAD_FAILED".equals(type) || "LOAD_CANCELLED".equals(type)) {
                    final JSONObject s = new JSONObject().put("playerState", "IDLE").put("idleReason", "ERROR");
                    main.post(new Runnable() { public void run() { if (!closed) listener.onMediaStatus(s); } });
                }
            } else if (NS_CONN.equals(m[2]) && "CLOSE".equals(type)) {
                fail(name + " closed the connection");
            }
        }
    }

    private void onReceiverStatus(JSONObject status) throws Exception {
        if (status == null) return;
        JSONObject vol = status.optJSONObject("volume");
        if (vol != null && vol.has("level")) volume = vol.getDouble("level");
        JSONArray apps = status.optJSONArray("applications");
        JSONObject ours = null;
        if (apps != null) for (int i = 0; i < apps.length(); i++)
            if (APP_ID.equals(apps.getJSONObject(i).optString("appId"))) ours = apps.getJSONObject(i);
        if (ours == null) {
            if (transportId != null) fail("Casting stopped on " + name);
            return;
        }
        if (transportId == null) {
            transportId = ours.getString("transportId");
            sessionId = ours.optString("sessionId");
            send(transportId, NS_CONN, new JSONObject().put("type", "CONNECT"));
            main.post(new Runnable() { public void run() { if (!closed) listener.onReady(); } });
        }
    }

    // ----- commands (safe to call from the UI thread) -----

    public void load(final String url, final String title) {
        sendAsync(new Job() { public void run() throws Exception {
            JSONObject meta = new JSONObject().put("metadataType", 0).put("title", title);
            JSONObject media = new JSONObject().put("contentId", url).put("streamType", "BUFFERED")
                .put("contentType", url.endsWith(".mp3") ? "audio/mpeg" : "audio/wav").put("metadata", meta);
            send(transportId, NS_MEDIA, new JSONObject().put("type", "LOAD").put("requestId", nextId())
                .put("media", media).put("autoplay", true));
        }});
    }

    public void stopMedia() {
        sendAsync(new Job() { public void run() throws Exception {
            if (mediaSessionId < 0) return;
            send(transportId, NS_MEDIA, new JSONObject().put("type", "STOP").put("requestId", nextId())
                .put("mediaSessionId", mediaSessionId));
        }});
    }

    public void setVolume(final double level) {
        volume = Math.max(0, Math.min(1, level));
        sendAsync(new Job() { public void run() throws Exception {
            send("receiver-0", NS_RECV, new JSONObject().put("type", "SET_VOLUME").put("requestId", nextId())
                .put("volume", new JSONObject().put("level", volume)));
        }});
    }

    /** Stops the player on the device and disconnects. */
    public void shutdown() {
        if (closed) return;
        sendAsync(new Job() { public void run() throws Exception {
            if (sessionId != null)
                send("receiver-0", NS_RECV, new JSONObject().put("type", "STOP").put("requestId", nextId()).put("sessionId", sessionId));
        }});
        sendAsync(new Job() { public void run() { closeQuietly(); } });
        sendQ.shutdown();
    }

    private interface Job { void run() throws Exception; }

    private void sendAsync(final Job j) {
        try {
            sendQ.execute(new Runnable() { public void run() {
                if (closed || out == null) return;
                try { j.run(); } catch (Exception e) { fail("Lost connection to " + name); }
            }});
        } catch (Exception ignored) { }
    }

    private void fail(final String why) {
        if (closed) return;
        closeQuietly();
        main.post(new Runnable() { public void run() { listener.onClosed(why); } });
    }

    private void closeQuietly() {
        closed = true;
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
    }

    // ----- protobuf CastMessage framing -----

    private void send(String dest, String ns, JSONObject payload) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        varint(o, (1 << 3)); varint(o, 0);           // protocol_version = CASTV2_1_0
        str(o, 2, SENDER);
        str(o, 3, dest);
        str(o, 4, ns);
        varint(o, (5 << 3)); varint(o, 0);           // payload_type = STRING
        str(o, 6, payload.toString());
        byte[] b = o.toByteArray();
        synchronized (this) {
            out.writeInt(b.length);
            out.write(b);
            out.flush();
        }
    }

    private static void varint(ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        o.write((int) v);
    }

    private static void str(ByteArrayOutputStream o, int field, String s) {
        byte[] b = s.getBytes(UTF8);
        varint(o, (field << 3) | 2);
        varint(o, b.length);
        o.write(b, 0, b.length);
    }

    private static String[] decode(byte[] b) {
        String[] r = new String[4];
        int[] pos = {0};
        while (pos[0] < b.length) {
            long key = readVarint(b, pos);
            int field = (int) (key >>> 3), wt = (int) (key & 7);
            if (wt == 0) readVarint(b, pos);
            else if (wt == 2) {
                int n = (int) readVarint(b, pos);
                String s = (field == 2 || field == 3 || field == 4 || field == 6) ? new String(b, pos[0], n, UTF8) : null;
                pos[0] += n;
                if (field == 2) r[0] = s; else if (field == 3) r[1] = s; else if (field == 4) r[2] = s; else if (field == 6) r[3] = s;
            } else if (wt == 5) pos[0] += 4;
            else if (wt == 1) pos[0] += 8;
            else break;
        }
        return r;
    }

    private static long readVarint(byte[] b, int[] pos) {
        long v = 0; int shift = 0;
        while (pos[0] < b.length) {
            int x = b[pos[0]++] & 0xFF;
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) break;
            shift += 7;
        }
        return v;
    }
}
