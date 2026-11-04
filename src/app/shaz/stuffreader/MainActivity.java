package app.shaz.stuffreader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final int PICK_FILE = 1;

    // Google TTS voice codes known to be female (e.g. "en-us-x-sfg-local").
    private static final String[] FEMALE_CODES = {
        "sfg", "iob", "tpc", "tpf", "gba", "gbc", "gbg", "afh", "aua", "auc",
        "ahp", "cxx", "ene", "fra", "frc", "vlf", "dea", "dec", "esc", "esf",
        "itb", "itc", "jab", "jac", "cmn", "bmh", "pte", "ptd"
    };

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final List<String> lines = new ArrayList<>();
    private final List<Voice> voices = new ArrayList<>();   // voices shown in the list
    private final Set<String> chosen = new HashSet<>();      // names of ticked voices
    private boolean noFemaleDetected = false;
    private int idx = 0;
    private boolean playing = false;
    private boolean repeat = false;
    private String fileName = null;

    private SharedPreferences prefs;
    private final Handler handler = new Handler();

    private TextView fileLabel, lineView, infoView;
    private Button playBtn;
    private CheckBox repeatBox;
    private LinearLayout voiceBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("stuff", MODE_PRIVATE);
        repeat = prefs.getBoolean("repeat", false);
        buildUi();
        tts = new TextToSpeech(this, this);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(final String id) { runOnUiThread(new Runnable() { public void run() { advance(id); } }); }
            @Override public void onError(final String id) { runOnUiThread(new Runnable() { public void run() { advance(id); } }); }
        });

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

    // ---------- UI ----------

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);

        Button open = new Button(this);
        open.setText("Open stuff.txt");
        open.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pickFile(); } });
        root.addView(open);

        fileLabel = new TextView(this);
        fileLabel.setText("No file loaded");
        fileLabel.setPadding(0, dp(4), 0, dp(12));
        root.addView(fileLabel);

        lineView = new TextView(this);
        lineView.setTextSize(24);
        lineView.setTypeface(Typeface.DEFAULT_BOLD);
        lineView.setGravity(Gravity.CENTER);
        lineView.setMinHeight(dp(140));
        lineView.setPadding(dp(12), dp(12), dp(12), dp(12));
        lineView.setBackgroundColor(Color.rgb(252, 228, 236));
        lineView.setTextColor(Color.rgb(60, 20, 40));
        lineView.setText("Tap Open, pick stuff.txt, then Play.");
        root.addView(lineView);

        infoView = new TextView(this);
        infoView.setGravity(Gravity.CENTER);
        infoView.setPadding(0, dp(6), 0, dp(6));
        root.addView(infoView);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = new Button(this); prev.setText("⏮");
        playBtn = new Button(this); playBtn.setText("▶ Play");
        Button next = new Button(this); next.setText("⏭");
        Button restart = new Button(this); restart.setText("↺");
        LinearLayout.LayoutParams w1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2);
        controls.addView(restart, w1); controls.addView(prev, w1); controls.addView(playBtn, w2); controls.addView(next, w1);
        root.addView(controls);

        playBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { if (playing) stop(); else play(); } });
        prev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { jump(-1); } });
        next.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { jump(1); } });
        restart.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { idx = 0; saveIdx(); if (playing) speakCurrent(); else showLine(); } });

        repeatBox = new CheckBox(this);
        repeatBox.setText("Repeat when finished");
        repeatBox.setChecked(repeat);
        repeatBox.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            repeat = repeatBox.isChecked(); prefs.edit().putBoolean("repeat", repeat).apply(); } });
        root.addView(repeatBox);

        TextView vh = new TextView(this);
        vh.setText("Voices (each line uses the next ticked voice). Long-press a voice to hear it.");
        vh.setTypeface(Typeface.DEFAULT_BOLD);
        vh.setPadding(0, dp(16), 0, dp(4));
        root.addView(vh);

        voiceBox = new LinearLayout(this);
        voiceBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(voiceBox);

        Button more = new Button(this);
        more.setText("Get more voices (TTS settings)");
        more.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
            catch (Exception e) { toast("Open Settings → Accessibility → Text-to-speech"); } } });
        root.addView(more);

        setContentView(scroll);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

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
            if (persist || sameFile) prefs.edit().putString("uri", uri.toString()).apply();
            setLines(sb.toString(), name);
            if (sameFile) { idx = Math.min(prefs.getInt("idx", 0), Math.max(0, lines.size() - 1)); showLine(); }
        } catch (Exception e) {
            fileLabel.setText("Couldn't open file — tap Open stuff.txt again.");
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
        fileName = name;
        idx = 0;
        saveIdx();
        fileLabel.setText((name == null ? "File" : name) + " — " + lines.size() + " lines");
        showLine();
    }

    private void saveIdx() { prefs.edit().putInt("idx", idx).apply(); }

    // ---------- Voices ----------

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            infoView.setText("Text-to-speech isn't available. Install \"Speech Services by Google\" from the Play Store.");
            return;
        }
        ttsReady = true;
        loadVoices(0);
    }

    private void loadVoices(final int attempt) {
        Set<Voice> all = null;
        try { all = tts.getVoices(); } catch (Exception ignored) { }
        if ((all == null || all.isEmpty()) && attempt < 5) {
            handler.postDelayed(new Runnable() { public void run() { loadVoices(attempt + 1); } }, 700);
            return;
        }
        voices.clear();
        String devLang = Locale.getDefault().getLanguage();
        List<Voice> female = new ArrayList<>();
        List<Voice> sameLang = new ArrayList<>();
        if (all != null) for (Voice v : all) {
            if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            String lang = v.getLocale().getLanguage();
            if (!lang.equals("en") && !lang.equals(devLang)) continue;
            sameLang.add(v);
            if (isFemale(v)) female.add(v);
        }
        Comparator<Voice> cmp = new Comparator<Voice>() { public int compare(Voice a, Voice b) {
            int c = Boolean.compare(a.isNetworkConnectionRequired(), b.isNetworkConnectionRequired());
            return c != 0 ? c : a.getName().compareTo(b.getName()); } };
        Collections.sort(female, cmp);
        Collections.sort(sameLang, cmp);

        noFemaleDetected = female.isEmpty();
        voices.addAll(noFemaleDetected ? sameLang : female);
        if (!noFemaleDetected) for (Voice v : sameLang) if (!female.contains(v)) voices.add(v); // others, unticked

        Set<String> saved = prefs.getStringSet("voices", null);
        chosen.clear();
        if (saved != null) for (Voice v : voices) if (saved.contains(v.getName())) chosen.add(v.getName());
        if (chosen.isEmpty()) {
            for (Voice v : (noFemaleDetected ? sameLang : female)) chosen.add(v.getName());
        }
        renderVoices();
        if (noFemaleDetected && !voices.isEmpty())
            infoView.setText("Couldn't tell which voices are female on this phone, so pitch is raised a bit. Untick any male voices below.");
        if (voices.isEmpty()) {
            Voice d = tts.getVoice() != null ? tts.getVoice() : tts.getDefaultVoice();
            if (d != null) { voices.add(d); chosen.add(d.getName()); noFemaleDetected = true; renderVoices(); }
        }
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

    private String label(Voice v, int n) {
        String code = v.getName();
        int x = code.indexOf("-x-");
        if (x >= 0 && code.length() >= x + 6) code = code.substring(x + 3, x + 6);
        String s = "Voice " + n + " — " + v.getLocale().getDisplayName() + " (" + code + ")";
        if (v.isNetworkConnectionRequired()) s += " · needs internet";
        return s;
    }

    private void renderVoices() {
        voiceBox.removeAllViews();
        int n = 1;
        for (final Voice v : voices) {
            final CheckBox cb = new CheckBox(this);
            cb.setText(label(v, n++));
            cb.setChecked(chosen.contains(v.getName()));
            cb.setOnClickListener(new View.OnClickListener() { public void onClick(View view) {
                if (cb.isChecked()) chosen.add(v.getName()); else chosen.remove(v.getName());
                prefs.edit().putStringSet("voices", new HashSet<>(chosen)).apply(); } });
            cb.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View view) {
                boolean was = playing; stop();
                tts.setVoice(v); tts.setPitch(noFemaleDetected ? 1.25f : 1.0f);
                tts.speak("Hi, this is how I sound.", TextToSpeech.QUEUE_FLUSH, null, "preview");
                return true; } });
            voiceBox.addView(cb);
        }
    }

    private List<Voice> activeVoices() {
        List<Voice> out = new ArrayList<>();
        for (Voice v : voices) if (chosen.contains(v.getName())) out.add(v);
        return out;
    }

    // ---------- Playback ----------

    private void play() {
        if (!ttsReady) { toast("Speech engine still starting…"); return; }
        if (lines.isEmpty()) { toast("Open stuff.txt first"); return; }
        if (activeVoices().isEmpty()) { toast("Tick at least one voice"); return; }
        if (idx >= lines.size()) idx = 0;
        playing = true;
        playBtn.setText("■ Stop");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        speakCurrent();
    }

    private void stop() {
        playing = false;
        if (tts != null) tts.stop();
        if (playBtn != null) playBtn.setText("▶ Play");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void jump(int d) {
        if (lines.isEmpty()) return;
        idx = Math.max(0, Math.min(lines.size() - 1, idx + d));
        saveIdx();
        if (playing) speakCurrent(); else showLine();
    }

    private Voice voiceFor(int i) {
        List<Voice> a = activeVoices();
        return a.isEmpty() ? null : a.get(i % a.size());
    }

    private void showLine() {
        if (lines.isEmpty()) return;
        lineView.setText(lines.get(Math.min(idx, lines.size() - 1)));
        Voice v = voiceFor(idx);
        String vs = "";
        if (v != null) vs = " · " + label(v, voices.indexOf(v) + 1).split(" — ")[0];
        infoView.setText("Line " + (Math.min(idx, lines.size() - 1) + 1) + " of " + lines.size() + vs);
    }

    private void speakCurrent() {
        showLine();
        Voice v = voiceFor(idx);
        if (v != null) tts.setVoice(v);
        float pitch = 1.0f;
        if (noFemaleDetected) pitch = 1.2f + 0.05f * (idx % 3);
        tts.setPitch(pitch);
        tts.speak(lines.get(idx), TextToSpeech.QUEUE_FLUSH, null, "L" + idx);
    }

    private void advance(String id) {
        if (!playing || !("L" + idx).equals(id)) return;
        idx++;
        if (idx >= lines.size()) {
            if (repeat) idx = 0;
            else { idx = 0; saveIdx(); stop(); showLine(); infoView.setText("Finished — " + lines.size() + " lines"); return; }
        }
        saveIdx();
        speakCurrent();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
