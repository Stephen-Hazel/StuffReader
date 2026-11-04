package app.shaz.stuffreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int PICK_FILE = 1;

    // Google TTS voice codes known to be female (e.g. "en-us-x-sfg-local").
    private static final String[] FEMALE_CODES = {
        "sfg", "iob", "tpc", "tpf", "gba", "gbc", "gbg", "afh", "aua", "auc",
        "ahp", "cxx", "ene", "fra", "frc", "vlf", "dea", "dec", "esc", "esf",
        "itb", "itc", "jab", "jac", "cmn", "bmh", "pte", "ptd"
    };

    // ---- palette ----
    private static final int BG        = Color.rgb(255, 246, 249);
    private static final int CARD      = Color.WHITE;
    private static final int ROSE      = Color.rgb(216, 27, 96);
    private static final int ROSE_DARK = Color.rgb(173, 20, 87);
    private static final int ROSE_SOFT = Color.rgb(252, 228, 236);
    private static final int ROSE_MID  = Color.rgb(248, 187, 208);
    private static final int TINT_ROW  = Color.rgb(255, 240, 245);
    private static final int TEXT      = Color.rgb(58, 26, 42);
    private static final int SUBTEXT   = Color.rgb(138, 106, 122);
    private static final int LINE      = Color.rgb(243, 217, 227);

    // ---- voice sources ----
    private static final int SYS = 0, PIPER = 1, CLOUD = 2;
    private static class Eng {
        int kind; String pkg, label; TextToSpeech tts; boolean ready;
        final List<Voice> voices = new ArrayList<>();                    // SYS
        final List<Cloud.CVoice> cloudVoices = new ArrayList<>();        // CLOUD
    }
    private static class VItem { Eng e; Voice v; String id, name, key; }
    private final List<Eng> engines = new ArrayList<>();   // installed system engines, default first
    private Eng piperEng;
    private final Map<String, Eng> cloudEngs = new LinkedHashMap<>();
    private final List<VItem> voices = new ArrayList<>();   // voices shown in the list
    private final Set<String> chosen = new HashSet<>();      // keys of ticked voices
    private boolean noFemaleDetected = false;
    private boolean ttsReady = false;
    private int uttSeq = 0;
    private String curUtt = null;
    private Piper piper;
    private final Map<String, Integer> downloads = new HashMap<>();   // piper id -> percent
    private Runnable refreshPiperDialog = null;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // ---- lines & order ----
    private final List<String> lines = new ArrayList<>();
    private List<Integer> order = new ArrayList<>();   // position -> line index
    private List<Integer> upcoming = null;              // next round's shuffle, made early so the next line can be prepared
    private final Random rnd = new Random();
    private int idx = 0;                                 // position within order
    private boolean playing = false;
    private boolean randomize = true;

    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private TextView fileLabel, lineView, infoView, noticeView, playBtn, castBtn;
    private LinearLayout voiceBox;

    // ---- audio-file playback (Piper, cloud voices, and casting) ----
    private MediaPlayer player;
    private File castDir, cloudCache;
    private final Map<Integer, File> synthDone = new HashMap<>();   // keyed by line index
    private final Set<Integer> synthPending = new HashSet<>();
    private final Map<String, File> synthFiles = new HashMap<>();   // utterance id -> output file
    private int synthGen = 0, fileSeq = 0, failures = 0;
    private String lastError = null;
    private int pendingLoad = -1;   // line waiting for its audio
    private int awaitingIdx = -1;   // line whose audio is playing

    // ---- casting ----
    private static class CastDevice { String name, host; int port; }
    private final LinkedHashMap<String, CastDevice> devices = new LinkedHashMap<>();
    private final List<NsdServiceInfo> resolveQueue = new ArrayList<>();
    private boolean resolving = false;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock mcLock;
    private CastClient cast;
    private FileServer server;
    private int serverPort = -1;
    private boolean sawPlaying = false;

    private final UtteranceProgressListener progress = new UtteranceProgressListener() {
        @Override public void onStart(String id) { }
        @Override public void onDone(final String id) { runOnUiThread(new Runnable() { public void run() { utteranceDone(id, true); } }); }
        @Override public void onError(final String id) { runOnUiThread(new Runnable() { public void run() { utteranceDone(id, false); } }); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("stuff", MODE_PRIVATE);
        randomize = prefs.getBoolean("randomize", true);
        piper = new Piper(getFilesDir());
        castDir = new File(getCacheDir(), "cast");
        castDir.mkdirs();
        for (File f : castDir.listFiles()) f.delete();
        cloudCache = new File(getFilesDir(), "cloudcache");
        cloudCache.mkdirs();

        buildUi();

        piperEng = new Eng();
        piperEng.kind = PIPER; piperEng.pkg = "piper"; piperEng.label = "Piper"; piperEng.ready = true;
        for (String p : Cloud.PROVIDERS) if (prefs.getString("key_" + p, null) != null) loadCloudEng(p);
        addEngine(null, null);   // default system engine; the others are added once it's up
        rebuildVoices();

        startDiscovery();

        if (!handleIncoming(getIntent())) {
            String saved = prefs.getString("uri", null);
            if (saved != null) loadFile(Uri.parse(saved), false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        scanEngines();   // pick up speech engines installed while we were away
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    private boolean handleIncoming(Intent in) {
        if (in == null) return false;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(in.getAction())) u = in.getData();
        else if (Intent.ACTION_SEND.equals(in.getAction())) {
            u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u == null && in.getStringExtra(Intent.EXTRA_TEXT) != null) {
                stop();
                setLines(in.getStringExtra(Intent.EXTRA_TEXT), "shared text");
                return true;
            }
        }
        if (u == null) return false;
        stop();
        loadFile(u, false);
        return true;
    }

    // ---------- UI helpers ----------

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private GradientDrawable shape(int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, null);
    }

    /** Rounded, pill-shaped text button. */
    private TextView pill(String text, boolean primary) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setTextColor(primary ? Color.WHITE : ROSE_DARK);
        t.setBackground(ripple(shape(primary ? ROSE : CARD, 28, primary ? 0 : ROSE_MID), primary ? ROSE_MID : ROSE_SOFT));
        t.setElevation(dp(primary ? 3 : 1));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Small pill used inside rows. */
    private TextView chip(String text, boolean primary) {
        TextView t = pill(text, primary);
        t.setTextSize(13);
        t.setPadding(dp(12), dp(6), dp(12), dp(6));
        t.setElevation(0);
        return t;
    }

    /** Circular icon button. */
    private TextView roundBtn(String glyph, int sizeDp, boolean primary) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextSize(primary ? 26 : 18);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(primary ? Color.WHITE : ROSE_DARK);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(primary ? ROSE : CARD);
        if (!primary) g.setStroke(dp(1), ROSE_MID);
        t.setBackground(ripple(g, primary ? ROSE_MID : ROSE_SOFT));
        t.setElevation(dp(primary ? 6 : 2));
        t.setClickable(true);
        t.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(shape(CARD, 20, 0));
        c.setElevation(dp(2));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(14), 0, 0);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private CheckBox checkBox() {
        CheckBox cb = new CheckBox(this);
        cb.setButtonTintList(new ColorStateList(
            new int[][]{ new int[]{ android.R.attr.state_checked }, new int[]{} },
            new int[]{ ROSE, SUBTEXT }));
        cb.setClickable(false);   // the whole row handles taps
        cb.setFocusable(false);
        return cb;
    }

    /** A tappable rounded row: optional leading view, title + subtitle, optional trailing view. */
    private LinearLayout row(View leading, String title, String sub, View trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(leading == null ? 14 : 6), dp(8), dp(8), dp(8));
        row.setClickable(true);
        row.setFocusable(true);
        if (leading != null) row.addView(leading);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(4), 0, dp(4), 0);
        col.addView(text(title, 16, TEXT, true));
        if (sub != null) col.addView(text(sub, 13, SUBTEXT, false));
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (trailing != null) row.addView(trailing);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        row.setLayoutParams(lp);
        styleRow(row, false);
        return row;
    }

    private void styleRow(View row, boolean on) {
        row.setBackground(ripple(shape(on ? TINT_ROW : CARD, 14, on ? ROSE_MID : LINE), ROSE_SOFT));
    }

    /** A dialog whose body is a scrolling list of rows. */
    private LinearLayout dialogBody(String intro) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        if (intro != null) {
            TextView t = text(intro, 14, SUBTEXT, false);
            t.setPadding(0, 0, 0, dp(6));
            body.addView(t);
        }
        return body;
    }

    private ScrollView scrollOf(View v) { ScrollView s = new ScrollView(this); s.addView(v); return s; }

    // ---------- UI ----------

    private void buildUi() {
        Window w = getWindow();
        w.setStatusBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        else w.setStatusBarColor(ROSE_DARK);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setClipToPadding(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        root.setClipToPadding(false);
        scroll.addView(root);

        // header
        TextView title = text("Stuff Reader", 28, ROSE_DARK, true);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        root.addView(title);
        fileLabel = text("No file loaded", 14, SUBTEXT, false);
        fileLabel.setPadding(0, dp(2), 0, dp(4));
        root.addView(fileLabel);

        // open / cast
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(0, dp(10), 0, 0);
        TextView open = pill("📄  Open file", true);
        open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickFile(); } });
        castBtn = pill("🔊  This phone", false);
        castBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showCastPicker(); } });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.setMargins(dp(10), 0, 0, 0);
        top.addView(open, half);
        top.addView(castBtn, half2);
        root.addView(top);

        // current line
        LinearLayout lineCard = new LinearLayout(this);
        lineCard.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{ ROSE_SOFT, ROSE_MID });
        grad.setCornerRadius(dp(24));
        lineCard.setBackground(grad);
        lineCard.setElevation(dp(3));
        lineCard.setPadding(dp(20), dp(22), dp(20), dp(16));
        LinearLayout.LayoutParams lcp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lcp.setMargins(0, dp(18), 0, 0);
        lineView = text("Tap Open, pick stuff.txt, then Play.", 26, TEXT, true);
        lineView.setGravity(Gravity.CENTER);
        lineView.setMinHeight(dp(140));
        lineCard.addView(lineView);
        infoView = text("", 13, ROSE_DARK, false);
        infoView.setGravity(Gravity.CENTER);
        infoView.setPadding(0, dp(10), 0, 0);
        lineCard.addView(infoView);
        root.addView(lineCard, lcp);

        noticeView = text("", 13, SUBTEXT, false);
        noticeView.setGravity(Gravity.CENTER);
        noticeView.setVisibility(View.GONE);
        noticeView.setPadding(dp(8), dp(10), dp(8), 0);
        root.addView(noticeView);

        // transport
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(10), 0, dp(2));
        TextView restart = roundBtn("↺", 50, false);
        TextView prev = roundBtn("⏮", 58, false);
        playBtn = roundBtn("▶", 78, true);
        TextView next = roundBtn("⏭", 58, false);
        controls.addView(restart); controls.addView(prev); controls.addView(playBtn); controls.addView(next);
        root.addView(controls);

        playBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (playing) stop(); else play(); } });
        prev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { back(); } });
        next.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (!lines.isEmpty()) advance(); } });
        restart.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { restart(); } });

        // options
        LinearLayout opts = card();
        final CheckBox rb = checkBox();
        rb.setChecked(randomize);
        final LinearLayout rrow = row(rb, "Randomize lines", "A fresh shuffle each time the app opens, and every round", null);
        styleRow(rrow, randomize);
        rrow.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            rb.setChecked(!rb.isChecked()); styleRow(rrow, rb.isChecked()); setRandomize(rb.isChecked()); } });
        ((LinearLayout.LayoutParams) rrow.getLayoutParams()).setMargins(0, 0, 0, 0);
        opts.addView(rrow);
        root.addView(opts);

        // voices
        LinearLayout vc = card();
        vc.addView(text("Voices", 18, TEXT, true));
        TextView vh = text("Each line uses the next ticked voice. Tap ▶ to hear one, tap a heading to fold it.", 13, SUBTEXT, false);
        vh.setPadding(0, dp(2), 0, dp(4));
        vc.addView(vh);
        voiceBox = new LinearLayout(this);
        voiceBox.setOrientation(LinearLayout.VERTICAL);
        vc.addView(voiceBox);
        TextView more = pill("＋  Get more voices", false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(0, dp(14), 0, dp(2));
        more.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { moreVoices(); } });
        vc.addView(more, mlp);
        root.addView(vc);

        setContentView(scroll);
    }

    private void notice(String s) {
        noticeView.setText(s);
        noticeView.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ---------- "Get more voices" ----------

    private void moreVoices() {
        String[] items = {
            "🎙  Piper voices — free, offline, natural-sounding",
            "☁  Cloud voices — Google, OpenAI, ElevenLabs (your API key)",
            "🧩  Free speech engines — SherpaTTS, RHVoice, eSpeak",
            "⚙  Phone speech settings — more Google accents",
        };
        new AlertDialog.Builder(this).setTitle("Get more voices")
            .setItems(items, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                if (w == 0) piperDialog();
                else if (w == 1) cloudDialog();
                else if (w == 2) enginesDialog();
                else googleVoiceData();
            } })
            .setNegativeButton("Close", null)
            .show();
    }

    private void googleVoiceData() {
        new AlertDialog.Builder(this).setTitle("More Google voices")
            .setMessage("In the next screen tap the ⚙ next to Speech Services by Google → Install voice data → English, and download the other accents (UK, Australia, India…). Each adds several voices.")
            .setPositiveButton("Open speech settings", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                catch (Exception e) { toast("Open Settings → Accessibility → Text-to-speech"); } } })
            .setNegativeButton("Close", null)
            .show();
    }

    private void piperDialog() {
        if (!Piper.available()) {
            new AlertDialog.Builder(this).setTitle("Piper voices")
                .setMessage("Piper voices need a 64-bit phone, and this one can't run them.\n\n(" + Piper.unavailableReason() + ")")
                .setPositiveButton("OK", null).show();
            return;
        }
        final LinearLayout body = dialogBody("Natural-sounding voices that run on your phone, no internet needed once downloaded. About 20–30 MB each.");
        refreshPiperDialog = new Runnable() { public void run() {
            while (body.getChildCount() > 1) body.removeViewAt(1);
            for (final Piper.Model m : Piper.CATALOG) {
                final boolean have = piper.installed(m.id);
                Integer pc = downloads.get(m.id);
                TextView btn = chip(pc != null ? pc + "%" : have ? "Remove" : "Download", !have && pc == null);
                LinearLayout r = row(null, m.name, m.accent + " English" + (have ? " · downloaded ✓" : ""), btn);
                if (pc == null) btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                    if (have) removePiper(m); else downloadPiper(m); } });
                body.addView(r);
            }
        } };
        refreshPiperDialog.run();
        new AlertDialog.Builder(this).setTitle("Piper voices").setView(scrollOf(body))
            .setPositiveButton("Done", null)
            .setOnDismissListener(new DialogInterface.OnDismissListener() { public void onDismiss(DialogInterface d) { refreshPiperDialog = null; } })
            .show();
    }

    private void downloadPiper(final Piper.Model m) {
        downloads.put(m.id, 0);
        if (refreshPiperDialog != null) refreshPiperDialog.run();
        new Thread(new Runnable() { public void run() {
            String err = null;
            try {
                piper.download(m.id, new Piper.Progress() { public void update(final int pc) {
                    handler.post(new Runnable() { public void run() {
                        downloads.put(m.id, pc);
                        if (refreshPiperDialog != null) refreshPiperDialog.run(); } });
                } });
            } catch (Exception e) { err = e.getMessage(); }
            final String error = err;
            handler.post(new Runnable() { public void run() {
                downloads.remove(m.id);
                if (error != null) toast("Couldn't download " + m.name + ": " + error);
                else {
                    chosen.add("piper|" + m.id);
                    saveChosen();
                    rebuildVoices();
                    toast(m.name + " is ready and ticked");
                }
                if (refreshPiperDialog != null) refreshPiperDialog.run();
            } });
        } }).start();
    }

    private void removePiper(final Piper.Model m) {
        new AlertDialog.Builder(this).setMessage("Remove the " + m.name + " voice from this phone?")
            .setPositiveButton("Remove", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                stop();
                bg.execute(new Runnable() { public void run() {
                    piper.delete(m.id);
                    handler.post(new Runnable() { public void run() {
                        chosen.remove("piper|" + m.id);
                        saveChosen();
                        clearSynth();
                        rebuildVoices();
                        if (refreshPiperDialog != null) refreshPiperDialog.run(); } });
                } });
            } })
            .setNegativeButton("Cancel", null).show();
    }

    private void cloudDialog() {
        final LinearLayout body = dialogBody("Studio-quality voices from an online service, using your own API key. Needs internet; each new line costs a little (lines are saved after the first time, so repeats are free).");
        final AlertDialog[] dlg = new AlertDialog[1];
        for (final String p : Cloud.PROVIDERS) {
            Eng e = cloudEngs.get(p);
            String status = e == null ? "Not set up" : e.cloudVoices.size() + " voices";
            TextView btn = chip(e == null ? "Add key" : "Change", e == null);
            LinearLayout r = row(null, Cloud.label(p), status, btn);
            View.OnClickListener l = new View.OnClickListener() { public void onClick(View v) { dlg[0].dismiss(); keyDialog(p); } };
            btn.setOnClickListener(l);
            r.setOnClickListener(l);
            body.addView(r);
        }
        dlg[0] = new AlertDialog.Builder(this).setTitle("Cloud voices").setView(scrollOf(body))
            .setPositiveButton("Done", null).show();
    }

    private void keyDialog(final String p) {
        final boolean has = cloudEngs.containsKey(p);
        LinearLayout body = dialogBody(Cloud.keyHelp(p));
        final EditText key = new EditText(this);
        key.setHint("Paste API key");
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        String old = prefs.getString("key_" + p, null);
        if (old != null) key.setText(old);
        body.addView(key);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(Cloud.label(p)).setView(body)
            .setPositiveButton("Save", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                String k = key.getText().toString().trim();
                if (k.length() > 0) saveKey(p, k); } })
            .setNegativeButton("Cancel", null);
        if (has) b.setNeutralButton("Remove", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
            stop();
            prefs.edit().remove("key_" + p).remove("cv_" + p).apply();
            cloudEngs.remove(p);
            clearSynth();
            rebuildVoices(); } });
        b.show();
    }

    private void saveKey(final String p, final String key) {
        toast("Checking " + Cloud.label(p) + " key…");
        bg.execute(new Runnable() { public void run() {
            String err = null;
            List<Cloud.CVoice> list = null;
            try { list = Cloud.listVoices(p, key); } catch (Exception e) { err = e.getMessage(); }
            final String error = err;
            final List<Cloud.CVoice> vs = list;
            handler.post(new Runnable() { public void run() {
                if (error != null) { toast(Cloud.label(p) + ": " + error); return; }
                JSONArray arr = new JSONArray();
                try { for (Cloud.CVoice v : vs) arr.put(new JSONObject().put("id", v.id).put("name", v.name)); } catch (Exception ignored) { }
                prefs.edit().putString("key_" + p, key).putString("cv_" + p, arr.toString()).apply();
                loadCloudEng(p);
                rebuildVoices();
                toast("Added " + vs.size() + " " + Cloud.label(p) + " voices — tick the ones you want");
            } });
        } });
    }

    private void loadCloudEng(String p) {
        Eng e = new Eng();
        e.kind = CLOUD; e.pkg = p; e.label = Cloud.label(p); e.ready = true;
        try {
            JSONArray arr = new JSONArray(prefs.getString("cv_" + p, "[]"));
            for (int i = 0; i < arr.length(); i++)
                e.cloudVoices.add(new Cloud.CVoice(arr.getJSONObject(i).getString("id"), arr.getJSONObject(i).getString("name")));
        } catch (Exception ignored) { }
        cloudEngs.put(p, e);
    }

    private void enginesDialog() {
        LinearLayout body = dialogBody("Speech engines from the Play Store. Once installed, their English voices appear in your list automatically.");
        String[][] list = {
            { "SherpaTTS", "Piper and Coqui neural voices as a phone-wide engine", "sherpa" },
            { "RHVoice", "Light, clear offline voices", "rhvoice" },
            { "eSpeak NG", "Very small, robotic — a deliberately different sound", "espeak" },
        };
        for (final String[] it : list) {
            boolean installed = false;
            for (Eng e : engines) {
                String s = ((e.label == null ? "" : e.label) + " " + (e.pkg == null ? "" : e.pkg)).toLowerCase(Locale.US);
                if (s.contains(it[2])) installed = true;
            }
            TextView btn = chip(installed ? "Installed ✓" : "Get", !installed);
            LinearLayout r = row(null, it[0], it[1], btn);
            if (!installed) btn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { playStore(it[0]); } });
            body.addView(r);
        }
        new AlertDialog.Builder(this).setTitle("Free speech engines").setView(scrollOf(body))
            .setPositiveButton("Done", null).show();
    }

    private void playStore(String q) {
        String enc = Uri.encode(q);
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + enc + "&c=apps"))); }
        catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + enc + "&c=apps"))); }
            catch (Exception e2) { toast("Search the Play Store for " + q); }
        }
    }

    // ---------- File ----------

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/*", "application/octet-stream"});
        startActivityForResult(i, PICK_FILE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_FILE && res == RESULT_OK && data != null && data.getData() != null) {
            stop();
            loadFile(data.getData(), true);
        }
    }

    private void loadFile(Uri uri, boolean persist) {
        if (persist) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
        }
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            r.close();
            String name = uri.getLastPathSegment();
            if (name != null && name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
            if (name != null && name.contains(":")) name = name.substring(name.lastIndexOf(':') + 1);
            boolean sameFile = uri.toString().equals(prefs.getString("uri", null));
            int savedIdx = prefs.getInt("idx", 0);
            if (persist || sameFile) prefs.edit().putString("uri", uri.toString()).apply();
            setLines(sb.toString(), name);
            // in-order mode picks up where it left off; random mode gets a fresh shuffle each launch
            if (sameFile && !randomize) { idx = Math.min(savedIdx, Math.max(0, lines.size() - 1)); saveIdx(); showLine(); }
        } catch (Exception e) {
            fileLabel.setText("Couldn't open file — tap Open file again.");
            prefs.edit().remove("uri").apply();
        }
    }

    private void setLines(String text, String name) {
        lines.clear();
        if (text.startsWith("﻿")) text = text.substring(1);
        for (String l : text.split("\\r?\\n")) {
            String t = l.trim();
            if (t.length() > 0) lines.add(t);
        }
        clearSynth();
        order = newOrder(-1);
        upcoming = null;
        idx = 0;
        saveIdx();
        fileLabel.setText((name == null ? "File" : name) + " · " + lines.size() + " lines");
        showLine();
    }

    private void saveIdx() { prefs.edit().putInt("idx", idx).apply(); }

    // ---------- Order ----------

    /** Identity order, or a shuffle whose first line isn't {@code avoidFirst}. */
    private List<Integer> newOrder(int avoidFirst) {
        List<Integer> o = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) o.add(i);
        if (randomize) {
            Collections.shuffle(o, rnd);
            if (o.size() > 1 && o.get(0) == avoidFirst) Collections.swap(o, 0, 1 + rnd.nextInt(o.size() - 1));
        }
        return o;
    }

    private int cur() { return order.isEmpty() ? -1 : order.get(Math.min(idx, order.size() - 1)); }

    private int nextPos() { return idx + 1 < order.size() ? idx + 1 : 0; }

    /** Line index that plays after the current position (wrapping into the next round). */
    private int lineAfter() {
        if (order.isEmpty()) return -1;
        if (idx + 1 < order.size()) return order.get(idx + 1);
        if (!randomize) return order.get(0);
        if (upcoming == null) upcoming = newOrder(order.get(order.size() - 1));
        return upcoming.get(0);
    }

    private void setRandomize(boolean on) {
        randomize = on;
        prefs.edit().putBoolean("randomize", on).apply();
        if (lines.isEmpty()) return;
        int c = cur();
        upcoming = null;
        clearSynth();
        order = newOrder(-1);
        if (on) {
            Collections.swap(order, 0, order.indexOf(c));   // keep the current line, shuffle the rest after it
            idx = 0;
        } else {
            idx = c;
        }
        saveIdx();
        showLine();
    }

    // ---------- System speech engines ----------

    private void addEngine(String pkg, String label) {
        final Eng e = new Eng();
        e.kind = SYS;
        e.pkg = pkg;
        e.label = label;
        TextToSpeech.OnInitListener l = new TextToSpeech.OnInitListener() {
            public void onInit(final int status) { handler.post(new Runnable() { public void run() { engineInit(e, status); } }); }
        };
        e.tts = pkg == null ? new TextToSpeech(this, l) : new TextToSpeech(this, l, pkg);
        e.tts.setOnUtteranceProgressListener(progress);
        engines.add(e);
    }

    private void engineInit(Eng e, int status) {
        boolean first = engines.indexOf(e) == 0;
        if (status != TextToSpeech.SUCCESS) {
            if (first) notice("Phone text-to-speech isn't available. Install \"Speech Services by Google\" from the Play Store, or add Piper or cloud voices below.");
            else { engines.remove(e); try { e.tts.shutdown(); } catch (Exception ignored) { } }
            return;
        }
        e.ready = true;
        ttsReady = true;
        if (first) {
            try { e.pkg = e.tts.getDefaultEngine(); } catch (Exception ignored) { }
            scanEngines();
        }
        loadVoices(e, 0);
    }

    /** Add any installed speech engine we aren't using yet. */
    private void scanEngines() {
        if (engines.isEmpty() || !engines.get(0).ready) return;
        try {
            for (TextToSpeech.EngineInfo ei : engines.get(0).tts.getEngines()) {
                boolean have = false;
                for (Eng e : engines) if (ei.name.equals(e.pkg)) { have = true; if (e.label == null) e.label = ei.label; }
                if (!have) addEngine(ei.name, ei.label);
            }
        } catch (Exception ignored) { }
    }

    private void loadVoices(final Eng e, final int attempt) {
        Set<Voice> all = null;
        try { all = e.tts.getVoices(); } catch (Exception ignored) { }
        if ((all == null || all.isEmpty()) && attempt < 5) {
            handler.postDelayed(new Runnable() { public void run() { loadVoices(e, attempt + 1); } }, 700);
            return;
        }
        e.voices.clear();
        if (all != null) for (Voice v : all) {
            if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            if (v.getLocale() == null || !v.getLocale().getLanguage().equals("en")) continue;   // English voices only
            e.voices.add(v);
        }
        if (e.voices.isEmpty() && engines.indexOf(e) == 0) {
            Voice d = null;
            try { d = e.tts.getVoice() != null ? e.tts.getVoice() : e.tts.getDefaultVoice(); } catch (Exception ignored) { }
            if (d != null) e.voices.add(d);
        }
        rebuildVoices();
    }

    // ---------- Voice list ----------

    private VItem item(Eng e, Voice v, String id, String name) {
        VItem it = new VItem();
        it.e = e; it.v = v; it.id = id; it.name = name;
        it.key = e.pkg + "|" + (v != null ? v.getName() : id);
        return it;
    }

    private void rebuildVoices() {
        voices.clear();
        List<VItem> sysFemale = new ArrayList<>(), sysAll = new ArrayList<>(), piperItems = new ArrayList<>();
        Comparator<VItem> cmp = new Comparator<VItem>() { public int compare(VItem a, VItem b) {
            int c = Boolean.compare(a.v.isNetworkConnectionRequired(), b.v.isNetworkConnectionRequired());
            return c != 0 ? c : a.v.getName().compareTo(b.v.getName()); } };
        for (Eng e : engines) {
            List<VItem> f = new ArrayList<>(), o = new ArrayList<>();
            for (Voice v : e.voices) {
                VItem it = item(e, v, null, v.getName());
                if (isFemale(v)) f.add(it); else o.add(it);
            }
            Collections.sort(f, cmp);
            Collections.sort(o, cmp);
            sysFemale.addAll(f);
            sysAll.addAll(f);
            sysAll.addAll(o);
            voices.addAll(f);
            voices.addAll(o);
        }
        noFemaleDetected = sysFemale.isEmpty() && !sysAll.isEmpty();

        for (Piper.Model m : Piper.CATALOG) if (piper.installed(m.id)) piperItems.add(item(piperEng, null, m.id, m.name + " · " + m.accent));
        voices.addAll(piperItems);
        for (Eng e : cloudEngs.values()) for (Cloud.CVoice cv : e.cloudVoices) voices.add(item(e, null, cv.id, cv.name));

        Set<String> saved = prefs.getStringSet("voices", null);
        chosen.clear();
        // older versions saved bare voice names (default engine only)
        if (saved != null) for (VItem v : voices)
            if (saved.contains(v.key) || (v.v != null && !engines.isEmpty() && v.e == engines.get(0) && saved.contains(v.v.getName()))) chosen.add(v.key);
        if (chosen.isEmpty()) {
            for (VItem v : (sysFemale.isEmpty() ? sysAll : sysFemale)) chosen.add(v.key);
            for (VItem v : piperItems) chosen.add(v.key);
        }

        renderVoices();
        notice(noFemaleDetected
            ? "Couldn't tell which phone voices are female, so their pitch is raised a bit. Untick any male voices below." : null);
        showLine();
    }

    private void saveChosen() { prefs.edit().putStringSet("voices", new HashSet<>(chosen)).apply(); }

    private static boolean isFemale(Voice v) {
        String n = v.getName().toLowerCase(Locale.US);
        if (n.contains("female") || n.contains("woman")) return true;
        if (n.contains("male")) return false;
        if (n.matches(".*smtf\\d.*")) return true;           // Samsung: SMTf00 = female
        int x = n.indexOf("-x-");
        if (x >= 0 && n.length() >= x + 6) {               // Google: en-us-x-sfg-local
            String code = n.substring(x + 3, x + 6);
            for (String f : FEMALE_CODES) if (f.equals(code)) return true;
        }
        return false;
    }

    private String engineLabel(Eng e) {
        if (e.label != null) return e.label;
        return e.pkg != null ? e.pkg : "Phone voice";
    }

    private String subtitle(VItem it) {
        String s = engineLabel(it.e) + " · " + it.name;
        if (it.v != null && it.v.isNetworkConnectionRequired()) s += " · needs internet";
        return s;
    }

    private void renderVoices() {
        voiceBox.removeAllViews();
        int n = 1;
        Eng group = null;
        boolean folded = false;
        for (int i = 0; i < voices.size(); i++) {
            final VItem it = voices.get(i);
            if (it.e != group) {
                group = it.e;
                int total = 0, on = 0;
                for (VItem v : voices) if (v.e == group) { total++; if (chosen.contains(v.key)) on++; }
                final String fk = "fold_" + group.pkg;
                folded = prefs.getBoolean(fk, total > 8);
                final boolean f = folded;
                TextView h = text((folded ? "▸  " : "▾  ") + engineLabel(group) + "   ·   " + on + " of " + total + " on", 14, ROSE_DARK, true);
                h.setPadding(dp(4), dp(14), dp(4), dp(4));
                h.setClickable(true);
                h.setBackground(ripple(shape(Color.TRANSPARENT, 8, 0), ROSE_SOFT));
                h.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                    prefs.edit().putBoolean(fk, !f).apply(); renderVoices(); } });
                voiceBox.addView(h);
            }
            final int num = n++;
            if (folded) continue;
            final CheckBox cb = checkBox();
            cb.setChecked(chosen.contains(it.key));
            TextView play = roundBtn("▶", 38, false);
            play.setTextSize(13);
            play.setElevation(0);
            ((LinearLayout.LayoutParams) play.getLayoutParams()).setMargins(dp(4), 0, 0, 0);
            play.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { preview(it); } });
            final LinearLayout r = row(cb, "Voice " + num, subtitle(it), play);
            styleRow(r, cb.isChecked());
            r.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
                if (cb.isChecked()) chosen.remove(it.key); else chosen.add(it.key);
                clearSynth();
                saveChosen();
                renderVoices();
                showLine(); } });
            r.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View view) { preview(it); return true; } });
            voiceBox.addView(r);
        }
        if (voices.isEmpty()) {
            TextView t = text("No voices yet — tap Get more voices.", 14, SUBTEXT, false);
            t.setPadding(dp(4), dp(10), dp(4), 0);
            voiceBox.addView(t);
        }
    }

    private void preview(final VItem it) {
        stop();
        final String hi = "Hi, this is how I sound.";
        if (it.e.kind == SYS) {
            it.e.tts.setVoice(it.v);
            it.e.tts.setPitch(noFemaleDetected ? 1.25f : 1.0f);
            it.e.tts.speak(hi, TextToSpeech.QUEUE_FLUSH, null, "preview");
            return;
        }
        if (it.e.kind == CLOUD) toast("Fetching sample…");
        final File f = new File(castDir, "preview" + (++fileSeq) + (it.e.kind == CLOUD ? ".mp3" : ".wav"));
        bg.execute(new Runnable() { public void run() {
            String err = null;
            try { synthFile(it, hi, f); } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
            final String error = err;
            handler.post(new Runnable() { public void run() {
                if (error != null) { toast("Couldn't play sample: " + error); return; }
                if (!playing) playLocal(f, -1);
            } });
        } });
    }

    private List<VItem> activeVoices() {
        List<VItem> out = new ArrayList<>();
        for (VItem v : voices) if (chosen.contains(v.key)) out.add(v);
        return out;
    }

    private void stopAllEngines(Eng except) {
        for (Eng e : engines) if (e != except && e.ready) try { e.tts.stop(); } catch (Exception ignored) { }
    }

    // ---------- Playback ----------

    private void play() {
        if (lines.isEmpty()) { toast("Open a file first"); return; }
        List<VItem> a = activeVoices();
        if (a.isEmpty()) { toast("Tick at least one voice"); return; }
        boolean needSys = false;
        for (VItem v : a) if (v.e.kind == SYS) needSys = true;
        if (needSys && !ttsReady) { toast("Speech engine still starting…"); return; }
        if (cast != null && !cast.isReady()) { toast("Still connecting to " + cast.name + "…"); return; }
        if (idx >= order.size()) idx = 0;
        playing = true;
        failures = 0;
        playBtn.setText("■");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        speakCurrent();
    }

    private void stop() {
        playing = false;
        curUtt = null;
        stopAllEngines(null);
        releasePlayer();
        synthPending.clear();
        pendingLoad = -1;
        if (cast != null && awaitingIdx >= 0) cast.stopMedia();
        awaitingIdx = -1;
        if (playBtn != null) playBtn.setText("▶");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void back() {
        if (lines.isEmpty()) return;
        idx = Math.max(0, idx - 1);
        saveIdx();
        if (playing) speakCurrent(); else showLine();
    }

    private void restart() {
        if (lines.isEmpty()) return;
        if (randomize) { order = newOrder(cur()); upcoming = null; clearSynth(); }
        idx = 0;
        saveIdx();
        if (playing) speakCurrent(); else showLine();
    }

    /** Move to the next line; after the last one, start over (reshuffled when randomizing). */
    private void advance() {
        idx++;
        if (idx >= order.size()) {
            idx = 0;
            if (randomize) { order = upcoming != null ? upcoming : newOrder(order.get(order.size() - 1)); upcoming = null; }
        }
        saveIdx();
        if (playing) speakCurrent(); else showLine();
    }

    private VItem voiceFor(int pos) {
        List<VItem> a = activeVoices();
        return a.isEmpty() ? null : a.get(pos % a.size());
    }

    private void showLine() {
        if (lines.isEmpty()) return;
        int p = Math.min(idx, order.size() - 1);
        int c = cur();
        lineView.setText(lines.get(c));
        VItem v = voiceFor(p);
        String vs = v == null ? "" : "  ·  Voice " + (voices.indexOf(v) + 1);
        infoView.setText(randomize
            ? "Line " + (c + 1) + "  ·  " + (p + 1) + " of " + lines.size() + " this round" + vs
            : "Line " + (c + 1) + " of " + lines.size() + vs);
    }

    private VItem applyVoice(int pos) {
        VItem v = voiceFor(pos);
        if (v == null || v.e.kind != SYS) return v;
        v.e.tts.setVoice(v.v);
        v.e.tts.setPitch(noFemaleDetected ? 1.2f + 0.05f * (pos % 3) : 1.0f);
        return v;
    }

    private void speakCurrent() {
        showLine();
        awaitingIdx = -1;
        releasePlayer();
        VItem v = voiceFor(idx);
        if (v == null) { stop(); return; }
        if (cast != null || v.e.kind != SYS) { stopAllEngines(null); fileCurrent(); return; }
        stopAllEngines(v.e);
        applyVoice(idx);
        curUtt = "L" + (++uttSeq);
        v.e.tts.speak(lines.get(cur()), TextToSpeech.QUEUE_FLUSH, null, curUtt);
        prefetchNext();
    }

    private void utteranceDone(String id, boolean ok) {
        if (id.startsWith("S")) { synthFinished(id, ok, null); return; }
        if (playing && id.equals(curUtt)) {
            if (ok) failures = 0;
            else if (++failures >= 3) { stop(); toast("The speech engine keeps failing — try other voices"); return; }
            advance();
        }
    }

    // ---------- Audio files ----------

    private void clearSynth() {
        synthGen++;
        synthDone.clear();
        synthPending.clear();
        synthFiles.clear();
        pendingLoad = -1;
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(s.hashCode()); }
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    /** Make audio for a Piper or cloud voice. Blocking; runs on the background thread. */
    private void synthFile(VItem v, String text, File out) throws IOException {
        if (v.e.kind == PIPER) { piper.synth(v.id, text, out); return; }
        String key = prefs.getString("key_" + v.e.pkg, null);
        if (key == null) throw new IOException("No API key");
        File cached = new File(cloudCache, sha1(v.key + "\n" + text) + ".mp3");
        if (!cached.isFile()) {   // each line is paid for once, then reused
            File tmp = new File(cloudCache, cached.getName() + ".part");
            Cloud.synth(v.e.pkg, key, v.id, text, tmp);
            if (!tmp.renameTo(cached)) throw new IOException("Couldn't save audio");
        }
        copy(cached, out);
    }

    /** Make an audio file for {@code line}, using the voice for position {@code pos}. */
    private void ensureSynth(final int line, int pos) {
        if (line < 0 || line >= lines.size() || synthDone.containsKey(line) || synthPending.contains(line)) return;
        final VItem v = applyVoice(pos);
        if (v == null) return;
        synthPending.add(line);
        final File f = new File(castDir, "u" + (++fileSeq) + (v.e.kind == CLOUD ? ".mp3" : ".wav"));
        final String id = "S" + synthGen + "_" + line + "_" + fileSeq;
        synthFiles.put(id, f);
        if (v.e.kind == SYS) {
            v.e.tts.synthesizeToFile(lines.get(line), new Bundle(), f, id);
            return;
        }
        final String text = lines.get(line);
        bg.execute(new Runnable() { public void run() {
            String err = null;
            try { synthFile(v, text, f); } catch (Throwable t) { err = String.valueOf(t.getMessage()); }
            final String error = err;
            handler.post(new Runnable() { public void run() { synthFinished(id, error == null, error); } });
        } });
    }

    private void synthFinished(String id, boolean ok, String err) {
        String[] p = id.substring(1).split("_");
        if (Integer.parseInt(p[0]) != synthGen) return;
        int i = Integer.parseInt(p[1]);
        File f = synthFiles.remove(id);
        if (!synthPending.remove(i)) return;
        if (!ok || f == null || !f.isFile()) {
            if (err != null) lastError = err;
            if (playing && pendingLoad == i) {
                pendingLoad = -1;
                if (++failures >= 3) { stop(); toast("Couldn't make speech" + (lastError != null ? ": " + lastError : "")); }
                else advance();
            }
            return;
        }
        synthDone.put(i, f);
        if (playing && pendingLoad == i) { pendingLoad = -1; playFile(i); }
    }

    private void fileCurrent() {
        int c = cur();
        if (synthDone.containsKey(c)) playFile(c);
        else { pendingLoad = c; ensureSynth(c, idx); }
    }

    /** Get the next line's audio ready while this one plays (if it needs a file). */
    private void prefetchNext() {
        int np = nextPos();
        VItem nv = voiceFor(np);
        if (nv != null && (cast != null || nv.e.kind != SYS)) ensureSynth(lineAfter(), np);
    }

    private void playFile(int line) {
        File f = synthDone.get(line);
        awaitingIdx = line;
        failures = 0;
        if (cast != null) {
            String ip = FileServer.localIp();
            if (ip == null) { stop(); toast("Lost Wi-Fi"); return; }
            sawPlaying = false;
            cast.load("http://" + ip + ":" + serverPort + "/" + f.getName(), lines.get(line));
        } else {
            playLocal(f, line);
        }
        prefetchNext();
        int n = lineAfter();
        // drop audio files we no longer need
        for (Integer k : new ArrayList<>(synthDone.keySet()))
            if (k != line && k != n) { synthDone.get(k).delete(); synthDone.remove(k); }
    }

    private void playLocal(File f, final int line) {
        releasePlayer();
        final MediaPlayer mp = new MediaPlayer();
        player = mp;
        MediaPlayer.OnCompletionListener done = new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer m) {
            if (mp != player) return;
            releasePlayer();
            if (line >= 0 && playing && awaitingIdx == line && cur() == line) { awaitingIdx = -1; advance(); } } };
        mp.setOnCompletionListener(done);
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() { public boolean onError(MediaPlayer m, int what, int extra) {
            if (mp == player && line >= 0 && playing && awaitingIdx == line && ++failures >= 3) { stop(); toast("Couldn't play audio"); return true; }
            if (mp == player && line >= 0 && playing && awaitingIdx == line) { releasePlayer(); awaitingIdx = -1; advance(); }
            return true; } });
        try {
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            mp.start();
        } catch (Exception e) {
            releasePlayer();
            if (line >= 0 && playing) { if (++failures >= 3) { stop(); toast("Couldn't play audio"); } else advance(); }
        }
    }

    private void releasePlayer() {
        if (player == null) return;
        MediaPlayer mp = player;
        player = null;
        try { mp.stop(); } catch (Exception ignored) { }
        try { mp.release(); } catch (Exception ignored) { }
    }

    // ---------- Casting ----------

    private void startDiscovery() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mcLock = wm.createMulticastLock("stuffreader");
            mcLock.setReferenceCounted(false);
            mcLock.acquire();
        } catch (Exception ignored) { }
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) { }
            public void onStopDiscoveryFailed(String t, int e) { }
            public void onDiscoveryStarted(String t) { }
            public void onDiscoveryStopped(String t) { }
            public void onServiceFound(final NsdServiceInfo si) {
                handler.post(new Runnable() { public void run() { resolveQueue.add(si); resolveNext(); } });
            }
            public void onServiceLost(final NsdServiceInfo si) {
                handler.post(new Runnable() { public void run() { devices.remove(si.getServiceName()); } });
            }
        };
        try { nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (Exception e) { discovery = null; }
    }

    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty()) return;
        resolving = true;
        NsdServiceInfo si = resolveQueue.remove(0);
        try {
            nsd.resolveService(si, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int e) {
                    handler.post(new Runnable() { public void run() { resolving = false; resolveNext(); } });
                }
                public void onServiceResolved(final NsdServiceInfo s) {
                    handler.post(new Runnable() { public void run() {
                        resolving = false;
                        if (s.getHost() != null) {
                            CastDevice d = new CastDevice();
                            d.host = s.getHost().getHostAddress();
                            d.port = s.getPort();
                            d.name = s.getServiceName();
                            try {
                                byte[] fn = s.getAttributes().get("fn");
                                if (fn != null) d.name = new String(fn, "UTF-8");
                            } catch (Exception ignored) { }
                            devices.put(s.getServiceName(), d);
                        }
                        resolveNext();
                    }});
                }
            });
        } catch (Exception e) { resolving = false; }
    }

    private void showCastPicker() {
        final List<CastDevice> list = new ArrayList<>(devices.values());
        String[] items = new String[list.size() + 1];
        items[0] = (cast == null ? "✓ " : "") + "This phone";
        for (int i = 0; i < list.size(); i++)
            items[i + 1] = (cast != null && cast.name.equals(list.get(i).name) ? "✓ " : "") + list.get(i).name;
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Play on…");
        if (list.isEmpty()) b.setMessage("No Cast devices found yet. Make sure your phone is on the same Wi-Fi as your Chromecast or speaker, then try again in a few seconds.")
                .setPositiveButton("This phone", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { useDevice(null); } })
                .setNegativeButton("Close", null);
        else b.setItems(items, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                useDevice(w == 0 ? null : list.get(w - 1)); } });
        b.show();
    }

    private void useDevice(CastDevice d) {
        boolean wasPlaying = playing;
        stop();
        if (cast != null) { cast.shutdown(); cast = null; }
        if (d == null) { castBtn.setText("🔊  This phone"); if (wasPlaying) play(); return; }
        if (FileServer.localIp() == null) { toast("Connect your phone to Wi-Fi to cast"); castBtn.setText("🔊  This phone"); return; }
        try {
            if (server == null) server = new FileServer(castDir);
            serverPort = server.start();
        } catch (Exception e) { toast("Couldn't start casting: " + e.getMessage()); return; }
        castBtn.setText("⏳  " + d.name);
        final boolean resume = wasPlaying;
        final CastClient[] me = new CastClient[1];
        me[0] = new CastClient(d.name, d.host, d.port, new CastClient.Listener() {
            public void onReady() {
                if (cast != me[0]) return;
                castBtn.setText("📺  " + cast.name);
                toast("Casting to " + cast.name + " — volume keys control it");
                if (resume) play();
            }
            public void onMediaStatus(JSONObject s) { if (cast == me[0]) mediaStatus(s); }
            public void onClosed(String reason) {
                if (cast != me[0]) return;
                stop();
                cast = null;
                castBtn.setText("🔊  This phone");
                toast(reason);
            }
        });
        cast = me[0];
        cast.connect();
    }

    private void mediaStatus(JSONObject s) {
        if (!playing || awaitingIdx < 0 || awaitingIdx != cur()) return;
        String state = s.optString("playerState");
        if ("PLAYING".equals(state) || "BUFFERING".equals(state)) { sawPlaying = true; return; }
        if (!"IDLE".equals(state)) return;
        String why = s.optString("idleReason");
        if ("ERROR".equals(why) || ("FINISHED".equals(why) && sawPlaying)) { awaitingIdx = -1; advance(); }
        else if ("CANCELLED".equals(why) && sawPlaying) { awaitingIdx = -1; stop(); }   // stopped from the device / Google Home
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (cast != null && cast.isReady() && (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                double v = cast.getVolume() < 0 ? 0.5 : cast.getVolume();
                cast.setVolume(v + (k == KeyEvent.KEYCODE_VOLUME_UP ? 0.05 : -0.05));
                toast("Volume " + Math.round(cast.getVolume() * 100) + "%");
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        if (cast != null) cast.shutdown();
        if (server != null) server.stop();
        try { if (discovery != null) nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
        try { if (mcLock != null) mcLock.release(); } catch (Exception ignored) { }
        for (Eng e : engines) try { e.tts.stop(); e.tts.shutdown(); } catch (Exception ignored) { }
        bg.execute(new Runnable() { public void run() { piper.releaseAll(); } });
        bg.shutdown();
        super.onDestroy();
    }
}
