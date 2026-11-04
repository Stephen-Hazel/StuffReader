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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    // ---- speech engines & voices ----
    private static class Eng { String pkg, label; TextToSpeech tts; boolean ready; final List<Voice> voices = new ArrayList<>(); }
    private static class VItem { Eng e; Voice v; String key; }
    private final List<Eng> engines = new ArrayList<>();
    private final List<VItem> voices = new ArrayList<>();   // voices shown in the list
    private final Set<String> chosen = new HashSet<>();      // keys of ticked voices
    private boolean noFemaleDetected = false;
    private boolean ttsReady = false;
    private int uttSeq = 0;
    private String curUtt = null;

    // ---- lines & order ----
    private final List<String> lines = new ArrayList<>();
    private List<Integer> order = new ArrayList<>();   // position -> line index
    private List<Integer> upcoming = null;              // next round's shuffle, made early so casting can prefetch
    private final Random rnd = new Random();
    private int idx = 0;                                 // position within order
    private boolean playing = false;
    private boolean randomize = true;

    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private TextView fileLabel, lineView, infoView, noticeView, playBtn, castBtn;
    private LinearLayout voiceBox;

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
    private File castDir;
    private final Map<Integer, File> synthDone = new HashMap<>();   // keyed by line index
    private final Set<Integer> synthPending = new HashSet<>();
    private int synthGen = 0, fileSeq = 0;
    private int pendingLoad = -1;   // line waiting for its audio before being sent to the device
    private int awaitingIdx = -1;   // line currently playing on the device
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
        buildUi();
        addEngine(null, null);   // default engine; the others are added once it's up

        castDir = new File(getCacheDir(), "cast");
        castDir.mkdirs();
        for (File f : castDir.listFiles()) f.delete();
        startDiscovery();

        if (!handleIncoming(getIntent())) {
            String saved = prefs.getString("uri", null);
            if (saved != null) loadFile(Uri.parse(saved), false);
        }
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

    /** A tappable rounded row: checkbox, title + subtitle, optional trailing view. */
    private LinearLayout checkRow(final CheckBox cb, String title, String sub, View trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(6), dp(8), dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        row.addView(cb);
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
        styleRow(row, cb.isChecked());
        return row;
    }

    private void styleRow(View row, boolean on) {
        row.setBackground(ripple(shape(on ? TINT_ROW : CARD, 14, on ? ROSE_MID : LINE), ROSE_SOFT));
    }

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
        final LinearLayout rrow = checkRow(rb, "Randomize lines", "A fresh shuffle each time the app opens, and every round", null);
        rrow.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            rb.setChecked(!rb.isChecked()); styleRow(rrow, rb.isChecked()); setRandomize(rb.isChecked()); } });
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) rrow.getLayoutParams();
        rlp.setMargins(0, 0, 0, 0);
        opts.addView(rrow);
        root.addView(opts);

        // voices
        LinearLayout vc = card();
        vc.addView(text("Voices", 18, TEXT, true));
        TextView vh = text("Each line uses the next ticked voice. Tap ▶ to hear one.", 13, SUBTEXT, false);
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

    private void moreVoices() {
        new AlertDialog.Builder(this).setTitle("More voices")
            .setMessage("Stuff Reader uses every speech engine installed on your phone.\n\n"
                + "• Speech Services by Google: in its settings, tap Install voice data → English and download "
                + "the other accents (UK, Australia, India…). Each one adds several voices.\n\n"
                + "• Install another engine from the Play Store, e.g. Samsung TTS, RHVoice, or Acapela. "
                + "Their voices show up here automatically next time you open the app.")
            .setPositiveButton("Open speech settings", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                catch (Exception e) { toast("Open Settings → Accessibility → Text-to-speech"); } } })
            .setNegativeButton("Close", null)
            .show();
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

    /** Line index that plays after the given position (wrapping into next round). */
    private int lineAfter(int pos) {
        if (order.isEmpty()) return -1;
        if (pos + 1 < order.size()) return order.get(pos + 1);
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
        if (on) {
            order = newOrder(-1);
            Collections.swap(order, 0, order.indexOf(c));   // keep the current line, shuffle the rest after it
            idx = 0;
        } else {
            order = newOrder(-1);
            idx = c;
        }
        saveIdx();
        showLine();
    }

    // ---------- Speech engines & voices ----------

    private void addEngine(String pkg, String label) {
        final Eng e = new Eng();
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
            if (first) notice("Text-to-speech isn't available. Install \"Speech Services by Google\" from the Play Store.");
            else { engines.remove(e); try { e.tts.shutdown(); } catch (Exception ignored) { } }
            return;
        }
        e.ready = true;
        ttsReady = true;
        if (first) {
            try {
                e.pkg = e.tts.getDefaultEngine();
                for (TextToSpeech.EngineInfo ei : e.tts.getEngines()) {
                    if (ei.name.equals(e.pkg)) e.label = ei.label;
                    else addEngine(ei.name, ei.label);
                }
            } catch (Exception ignored) { }
        }
        loadVoices(e, 0);
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
            if (v.getLocale() == null) continue;
            if (!v.getLocale().getLanguage().equals("en")) continue;   // English voices only
            e.voices.add(v);
        }
        if (e.voices.isEmpty() && engines.indexOf(e) == 0) {
            Voice d = null;
            try { d = e.tts.getVoice() != null ? e.tts.getVoice() : e.tts.getDefaultVoice(); } catch (Exception ignored) { }
            if (d != null) e.voices.add(d);
        }
        rebuildVoices();
    }

    private void rebuildVoices() {
        final List<VItem> female = new ArrayList<>(), all = new ArrayList<>();
        for (Eng e : engines) for (Voice v : e.voices) {
            VItem it = new VItem();
            it.e = e; it.v = v; it.key = e.pkg + "|" + v.getName();
            all.add(it);
            if (isFemale(v)) female.add(it);
        }
        Comparator<VItem> cmp = new Comparator<VItem>() { public int compare(VItem a, VItem b) {
            int c = Boolean.compare(a.v.isNetworkConnectionRequired(), b.v.isNetworkConnectionRequired());
            if (c != 0) return c;
            c = Integer.compare(engines.indexOf(a.e), engines.indexOf(b.e));
            return c != 0 ? c : a.v.getName().compareTo(b.v.getName()); } };
        Collections.sort(female, cmp);
        Collections.sort(all, cmp);

        noFemaleDetected = female.isEmpty();
        voices.clear();
        voices.addAll(noFemaleDetected ? all : female);
        if (!noFemaleDetected) for (VItem v : all) if (!female.contains(v)) voices.add(v);   // others, unticked

        Set<String> saved = prefs.getStringSet("voices", null);
        chosen.clear();
        // older versions saved bare voice names (default engine only)
        if (saved != null) for (VItem v : voices)
            if (saved.contains(v.key) || (v.e == engines.get(0) && saved.contains(v.v.getName()))) chosen.add(v.key);
        if (chosen.isEmpty()) for (VItem v : (noFemaleDetected ? all : female)) chosen.add(v.key);

        renderVoices();
        notice(noFemaleDetected && !voices.isEmpty()
            ? "Couldn't tell which voices are female on this phone, so pitch is raised a bit. Untick any male voices below." : null);
        showLine();
    }

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

    private String subtitle(VItem it) {
        String code = it.v.getName();
        int x = code.indexOf("-x-");
        if (x >= 0 && code.length() >= x + 6) code = code.substring(x + 3, x + 6);
        String s = code;
        if (engines.size() > 1 && it.e.label != null) s += " · " + it.e.label;
        if (it.v.isNetworkConnectionRequired()) s += " · needs internet";
        return s;
    }

    private void renderVoices() {
        voiceBox.removeAllViews();
        int n = 1;
        for (final VItem it : voices) {
            final CheckBox cb = checkBox();
            cb.setChecked(chosen.contains(it.key));
            TextView play = roundBtn("▶", 38, false);
            play.setTextSize(13);
            play.setElevation(0);
            LinearLayout.LayoutParams plp = (LinearLayout.LayoutParams) play.getLayoutParams();
            plp.setMargins(dp(4), 0, 0, 0);
            play.setOnClickListener(new View.OnClickListener() { public void onClick(View view) { preview(it); } });
            final LinearLayout row = checkRow(cb, "Voice " + (n++), subtitle(it), play);
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
                cb.setChecked(!cb.isChecked());
                styleRow(row, cb.isChecked());
                if (cb.isChecked()) chosen.add(it.key); else chosen.remove(it.key);
                clearSynth();
                prefs.edit().putStringSet("voices", new HashSet<>(chosen)).apply();
                showLine(); } });
            row.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View view) { preview(it); return true; } });
            voiceBox.addView(row);
        }
    }

    private void preview(VItem it) {
        stop();
        it.e.tts.setVoice(it.v);
        it.e.tts.setPitch(noFemaleDetected ? 1.25f : 1.0f);
        it.e.tts.speak("Hi, this is how I sound.", TextToSpeech.QUEUE_FLUSH, null, "preview");
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
        if (!ttsReady) { toast("Speech engine still starting…"); return; }
        if (lines.isEmpty()) { toast("Open a file first"); return; }
        if (activeVoices().isEmpty()) { toast("Tick at least one voice"); return; }
        if (cast != null && !cast.isReady()) { toast("Still connecting to " + cast.name + "…"); return; }
        if (idx >= order.size()) idx = 0;
        playing = true;
        playBtn.setText("■");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        speakCurrent();
    }

    private void stop() {
        playing = false;
        curUtt = null;
        stopAllEngines(null);
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
        if (v == null) return null;
        v.e.tts.setVoice(v.v);
        v.e.tts.setPitch(noFemaleDetected ? 1.2f + 0.05f * (pos % 3) : 1.0f);
        return v;
    }

    private void speakCurrent() {
        showLine();
        if (cast != null) { castCurrent(); return; }
        VItem v = applyVoice(idx);
        if (v == null) { stop(); return; }
        stopAllEngines(v.e);
        curUtt = "L" + (++uttSeq);
        v.e.tts.speak(lines.get(cur()), TextToSpeech.QUEUE_FLUSH, null, curUtt);
    }

    private void utteranceDone(String id, boolean ok) {
        if (id.startsWith("S")) { synthFinished(id, ok); return; }
        if (playing && cast == null && id.equals(curUtt)) advance();
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

    private void clearSynth() {
        synthGen++;
        synthDone.clear();
        synthPending.clear();
        pendingLoad = -1;
    }

    /** Synthesize line {@code line} with the voice for position {@code pos}. */
    private void ensureSynth(int line, int pos) {
        if (line < 0 || line >= lines.size() || synthDone.containsKey(line) || synthPending.contains(line)) return;
        VItem v = applyVoice(pos);
        if (v == null) return;
        synthPending.add(line);
        File f = new File(castDir, "u" + (++fileSeq) + ".wav");
        v.e.tts.synthesizeToFile(lines.get(line), new Bundle(), f, "S" + synthGen + "_" + line + "_" + fileSeq);
    }

    private void synthFinished(String id, boolean ok) {
        String[] p = id.substring(1).split("_");
        if (Integer.parseInt(p[0]) != synthGen) return;
        int i = Integer.parseInt(p[1]);
        if (!synthPending.remove(i)) return;
        File f = new File(castDir, "u" + p[2] + ".wav");
        if (!ok || !f.isFile()) {
            if (playing && pendingLoad == i) { pendingLoad = -1; advance(); }
            return;
        }
        synthDone.put(i, f);
        if (playing && pendingLoad == i) { pendingLoad = -1; castLoad(i); }
    }

    private void castCurrent() {
        awaitingIdx = -1;
        int c = cur();
        if (synthDone.containsKey(c)) castLoad(c);
        else { pendingLoad = c; ensureSynth(c, idx); }
    }

    private void castLoad(int line) {
        String ip = FileServer.localIp();
        if (ip == null || cast == null) { stop(); toast("Lost Wi-Fi"); return; }
        awaitingIdx = line;
        sawPlaying = false;
        cast.load("http://" + ip + ":" + serverPort + "/" + synthDone.get(line).getName(), lines.get(line));
        int nextPos = idx + 1 < order.size() ? idx + 1 : 0;
        int n = lineAfter(idx);
        ensureSynth(n, nextPos);   // get the next line ready while this one plays
        // drop audio files we no longer need
        for (Integer k : new ArrayList<>(synthDone.keySet()))
            if (k != line && k != n) { synthDone.get(k).delete(); synthDone.remove(k); }
    }

    private void mediaStatus(JSONObject s) {
        if (!playing || awaitingIdx != cur()) return;
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
        if (cast != null) cast.shutdown();
        if (server != null) server.stop();
        try { if (discovery != null) nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) { }
        try { if (mcLock != null) mcLock.release(); } catch (Exception ignored) { }
        for (Eng e : engines) try { e.tts.stop(); e.tts.shutdown(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
