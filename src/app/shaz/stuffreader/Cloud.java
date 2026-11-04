package app.shaz.stuffreader;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Cloud text-to-speech: Google Cloud, OpenAI and ElevenLabs, each with the user's own API key. */
public class Cloud {

    public static final String GOOGLE = "google", OPENAI = "openai", ELEVEN = "elevenlabs";
    public static final String[] PROVIDERS = { GOOGLE, OPENAI, ELEVEN };

    public static class CVoice {
        public final String id, name;
        public CVoice(String id, String name) { this.id = id; this.name = name; }
    }

    public static String label(String p) {
        if (GOOGLE.equals(p)) return "Google Cloud";
        if (OPENAI.equals(p)) return "OpenAI";
        return "ElevenLabs";
    }

    public static String keyHelp(String p) {
        if (GOOGLE.equals(p)) return "Google Cloud console → APIs & Services: enable \"Cloud Text-to-Speech API\", then Credentials → Create credentials → API key. Has a free monthly allowance.";
        if (OPENAI.equals(p)) return "platform.openai.com → API keys → Create new secret key. Pay as you go.";
        return "elevenlabs.io → Profile → API keys → Create. Has a small free monthly allowance.";
    }

    /** Female English voices offered by a provider. Blocking. */
    public static List<CVoice> listVoices(String p, String key) throws IOException {
        List<CVoice> out = new ArrayList<>();
        try {
            if (GOOGLE.equals(p)) {
                JSONObject r = new JSONObject(http("GET", "https://texttospeech.googleapis.com/v1/voices?languageCode=en-US&key=" + enc(key), null, null, null));
                JSONArray vs = r.optJSONArray("voices");
                if (vs != null) for (int i = 0; i < vs.length(); i++) {
                    JSONObject v = vs.getJSONObject(i);
                    if (!"FEMALE".equals(v.optString("ssmlGender"))) continue;
                    String name = v.getString("name");
                    if (!name.startsWith("en-US-")) continue;
                    out.add(new CVoice(name, name.substring(6)));   // e.g. "Chirp3-HD-Aoede"
                }
            } else if (OPENAI.equals(p)) {
                http("GET", "https://api.openai.com/v1/models/gpt-4o-mini-tts", null, "Authorization", "Bearer " + key);   // checks the key
                for (String v : new String[]{ "nova", "shimmer", "coral", "sage", "alloy" })
                    out.add(new CVoice(v, Character.toUpperCase(v.charAt(0)) + v.substring(1)));
            } else {
                JSONObject r = new JSONObject(http("GET", "https://api.elevenlabs.io/v1/voices", null, "xi-api-key", key));
                JSONArray vs = r.optJSONArray("voices");
                if (vs != null) for (int i = 0; i < vs.length(); i++) {
                    JSONObject v = vs.getJSONObject(i);
                    JSONObject labels = v.optJSONObject("labels");
                    if (labels != null && !"female".equalsIgnoreCase(labels.optString("gender", "female"))) continue;
                    out.add(new CVoice(v.getString("voice_id"), v.optString("name", "Voice")));
                }
            }
        } catch (org.json.JSONException e) { throw new IOException("Unexpected reply from " + label(p)); }
        return out;
    }

    /** Synthesize to an MP3 file. Blocking. */
    public static void synth(String p, String key, String voice, String text, File out) throws IOException {
        byte[] audio;
        try {
            if (GOOGLE.equals(p)) {
                JSONObject body = new JSONObject()
                    .put("input", new JSONObject().put("text", text))
                    .put("voice", new JSONObject().put("languageCode", "en-US").put("name", voice))
                    .put("audioConfig", new JSONObject().put("audioEncoding", "MP3"));
                JSONObject r = new JSONObject(new String(httpBytes("POST", "https://texttospeech.googleapis.com/v1/text:synthesize?key=" + enc(key), body.toString(), null, null), "UTF-8"));
                audio = Base64.decode(r.getString("audioContent"), Base64.DEFAULT);
            } else if (OPENAI.equals(p)) {
                JSONObject body = new JSONObject().put("model", "gpt-4o-mini-tts").put("voice", voice)
                    .put("input", text).put("response_format", "mp3");
                audio = httpBytes("POST", "https://api.openai.com/v1/audio/speech", body.toString(), "Authorization", "Bearer " + key);
            } else {
                JSONObject body = new JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2");
                audio = httpBytes("POST", "https://api.elevenlabs.io/v1/text-to-speech/" + enc(voice) + "?output_format=mp3_44100_128", body.toString(), "xi-api-key", key);
            }
        } catch (org.json.JSONException e) { throw new IOException("Unexpected reply from " + label(p)); }
        FileOutputStream os = new FileOutputStream(out);
        os.write(audio);
        os.close();
    }

    private static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8"); }

    private static String http(String method, String url, String body, String hk, String hv) throws IOException {
        return new String(httpBytes(method, url, body, hk, hv), "UTF-8");
    }

    private static byte[] httpBytes(String method, String url, String body, String hk, String hv) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            if (hk != null) c.setRequestProperty(hk, hv);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            byte[] data = readAll(in);
            if (code >= 400) {
                String msg = new String(data, "UTF-8");
                try {
                    JSONObject j = new JSONObject(msg);
                    Object err = j.opt("error");
                    if (err instanceof JSONObject) msg = ((JSONObject) err).optString("message", msg);
                    else if (j.optJSONObject("detail") != null) msg = j.getJSONObject("detail").optString("message", msg);
                    else if (err != null) msg = String.valueOf(err);
                } catch (Exception ignored) { }
                if (code == 401 || code == 403) msg = "Key not accepted: " + msg;
                throw new IOException(msg.length() > 200 ? msg.substring(0, 200) + "…" : msg);
            }
            return data;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }
}
