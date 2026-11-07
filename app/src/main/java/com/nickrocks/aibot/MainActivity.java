package com.nickrocks.aibot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.aibot.core.AISettings;
import com.nickrocks.aibot.core.Json;
import com.nickrocks.aibot.core.Learner;
import com.nickrocks.aibot.core.PerformanceAnalyzer;
import com.nickrocks.aibot.core.RunHistoryRepository;
import com.nickrocks.aibot.core.RunSessionManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI BOT — AUTONOMOUS GAMEPLAY: the menu, settings, run history, run summaries, logs and stats.
 * Built from plain views in code (no layout files, no libraries).
 */
public class MainActivity extends Activity {

    public static final String ACTION_START_RUN = "com.nickrocks.aibot.ACTION_START_RUN";
    public static final String ACTION_SHOW_RUN = "com.nickrocks.aibot.ACTION_SHOW_RUN";
    public static final String EXTRA_RUN = "run";
    private static final int REQ_CAPTURE = 41;

    private static final int BG = 0xFF0E0716, CARD = 0xFF1C1028, ACCENT = 0xFFE8457A, TEXT = 0xFFF2E6F7, DIM = 0xFFB9A8C9, GOLD = 0xFFF2C14E;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private RunHistoryRepository repo;
    private final ArrayDeque<Runnable> back = new ArrayDeque<>();
    private Runnable current;
    private Runnable ticker;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        repo = AISettingsManager.repo(this);
        getWindow().getDecorView().setBackgroundColor(BG);
        // runs left RUNNING by a crash become INTERRUPTED (the live run, if any, is left alone)
        io.execute(() -> {
            RunSessionManager s = BotState.session;
            int n = repo.recoverInterrupted(s != null && s.isLive() ? s.runId : "");
            if (n > 0) main.post(() -> toast(n + " interrupted run(s) recovered — their logs are kept"));
        });
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        go(this::home, false);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i == null) return;
        if (ACTION_START_RUN.equals(i.getAction())) startRun();
        else if (ACTION_SHOW_RUN.equals(i.getAction())) {
            String id = i.getStringExtra(EXTRA_RUN);
            if (id != null) go(() -> runDetail(id), true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (current != null) current.run();
    }

    @Override
    public void onBackPressed() {
        if (back.isEmpty()) { super.onBackPressed(); return; }
        current = back.pop();
        current.run();
    }

    @Override
    protected void onDestroy() {
        stopTicker();
        io.shutdown();
        super.onDestroy();
    }

    // ===================================================================== small view helpers

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private void go(Runnable screen, boolean push) {
        stopTicker();
        if (push && current != null) back.push(current);
        current = screen;
        screen.run();
    }

    private LinearLayout page(String title, String subtitle) {
        stopTicker();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, dp(20), pad, dp(28));
        // keep it readable on the unfolded Fold: cap the width
        LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.addView(col, new LinearLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels, dp(720)), -2));
        sv.addView(outer);
        setContentView(sv);
        TextView t = text(title, 24, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
        if (subtitle != null) { TextView s = text(subtitle, 13, DIM); s.setPadding(0, dp(2), 0, dp(10)); col.addView(s); }
        return col;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView mono(String s) {
        TextView t = text(s, 12, TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        return t;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(12));
        c.setBackground(g);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        parent.addView(c, lp);
        return c;
    }

    private Button button(ViewGroup parent, String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.topMargin = dp(8);
        parent.addView(b, lp);
        return b;
    }

    private Button small(ViewGroup parent, String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF2C1C3C);
        g.setCornerRadius(dp(8));
        b.setBackground(g);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
        lp.rightMargin = dp(6); lp.topMargin = dp(6);
        parent.addView(b, lp);
        return b;
    }

    private LinearLayout row(ViewGroup parent) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(r);
        parent.addView(hs);
        return r;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void startTicker(Runnable r, long ms) {
        stopTicker();
        ticker = new Runnable() { @Override public void run() { r.run(); main.postDelayed(this, ms); } };
        main.postDelayed(ticker, ms);
    }

    private void stopTicker() { if (ticker != null) main.removeCallbacks(ticker); ticker = null; }

    // ===================================================================== home

    private void home() {
        LinearLayout col = page("AI BOT — AUTONOMOUS GAMEPLAY", "An AI that plays the Nick Rocks game on this phone by watching the screen and working the MOVE and SING sticks.");
        LinearLayout st = card(col);
        TextView status = text("", 14, TEXT);
        st.addView(status);
        Runnable refresh = () -> {
            boolean acc = ControlService.instance != null;
            String game = AISettingsManager.gamePackage(this);
            RunSessionManager s = BotState.session;
            StringBuilder b = new StringBuilder();
            b.append(acc ? "✓ Control service on\n" : "✗ Control service off — Start will open Accessibility settings\n");
            b.append(game.isEmpty() ? "• Game app: not chosen (it's remembered the first time the bot sees the game)\n" : "✓ Game app: " + label(game) + "\n");
            b.append(BotState.captureActive ? "✓ Screen capture on\n" : "• Screen capture off (asked for when you start a run)\n");
            if (s != null && s.isLive()) b.append(String.format(Locale.US, "\nRUN %s — %s — %s%s", s.runId, s.state(), PerformanceAnalyzer.duration(s.gameMs()), BotState.paused ? " (paused)" : ""));
            else if (!BotState.lastFinishedRunId.isEmpty()) b.append("\nLast run: ").append(BotState.lastFinishedRunId);
            status.setText(b.toString().trim());
        };
        refresh.run();
        startTicker(refresh, 1000);

        button(col, "▶  Start AI Run", 0xFF2E9E5B, v -> startRun());
        button(col, "■  Stop AI Run", 0xFFD64545, v -> {
            RunSessionManager s = BotState.session;
            if (s != null && s.isLive()) { s.requestStop("Stopped by you"); toast("Stopping " + s.runId + "…"); }
            else toast("No run is going");
        });
        button(col, "⏯  Pause / Resume", 0xFF5A4A6E, v -> {
            RunSessionManager s = BotState.session;
            if (s == null || !s.isLive()) { toast("No run is going"); return; }
            BotState.paused = !BotState.paused;
            s.requestPause(BotState.paused);
            ControlService c = ControlService.instance;
            if (c != null) { if (BotState.paused) c.releaseAll(); c.refreshPanel(); }
            toast(BotState.paused ? "Paused — the AI let go of the controls" : "Resumed");
        });
        button(col, "⚙  AI Settings", 0xFF3A2A4E, v -> go(this::settings, true));
        button(col, "🗂  Run History", 0xFF3A2A4E, v -> go(() -> history("All", ""), true));
        button(col, "📜  View Logs", 0xFF3A2A4E, v -> go(this::liveLog, true));
        button(col, "📈  AI Statistics", 0xFF3A2A4E, v -> go(this::stats, true));

        LinearLayout setup = card(col);
        setup.addView(text("Setup", 16, GOLD));
        LinearLayout r = row(setup);
        small(r, "Choose game app", v -> chooseGame());
        small(r, "Calibrate controls", v -> {
            ControlService c = ControlService.instance;
            if (c == null) { askAccessibility(); return; }
            String game = AISettingsManager.gamePackage(this);
            if (!game.isEmpty()) { Intent l = getPackageManager().getLaunchIntentForPackage(game); if (l != null) startActivity(l); }
            c.showCalibration();
        });
        small(r, "Accessibility settings", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        setup.addView(text("How it works: press Start, allow screen sharing (\"entire screen\" or just the game), and the game opens. Start a dungeon run in the game; the AI takes over when it sees your hearts and your character. Touching the screen yourself interrupts its thumbs for a moment. Use the floating panel to pause or stop.", 12, DIM));
    }

    private String label(String pkg) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)) + " (" + pkg + ")"; }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    private void chooseGame() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(i, 0);
        List<String[]> items = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String p = ri.activityInfo.packageName;
            if (p.equals(getPackageName())) continue;
            items.add(new String[]{ri.loadLabel(getPackageManager()).toString(), p});
        }
        items.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[items.size()];
        for (int k = 0; k < labels.length; k++) labels[k] = items.get(k)[0];
        new AlertDialog.Builder(this).setTitle("Which app is the Nick Rocks game?")
                .setItems(labels, (d, w) -> { AISettingsManager.setGamePackage(this, items.get(w)[1]); toast("Game app: " + labels[w]); go(this::home, false); })
                .setNegativeButton("Cancel", null).show();
    }

    // ===================================================================== start

    private void askAccessibility() {
        new AlertDialog.Builder(this).setTitle("Turn on the control service")
                .setMessage("To move and sing for you, the bot needs Android's accessibility permission for \"Nick Rocks AI Bot\". It only touches the screen during an AI run, inside the game. On the next screen, find Nick Rocks AI Bot (on Samsung: Installed apps) and turn it on.")
                .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Not now", null).show();
    }

    private void startRun() {
        if (BotState.running()) { toast("A run is already going"); return; }
        if (ControlService.instance == null) { askAccessibility(); return; }
        if (CaptureService.instance != null && BotState.captureActive) {
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START_RUN);
            startForegroundService(i);
            afterStart();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) { toast("Screen sharing is needed so the AI can see the game"); return; }
        Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START_RUN)
                .putExtra(CaptureService.EXTRA_CODE, res).putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(i);
        afterStart();
    }

    private void afterStart() {
        if (AISettingsManager.gamePackage(this).isEmpty()) toast("Now open the Nick Rocks game and start a run — the AI takes over when it sees the game");
        else moveTaskToBack(true);
    }

    // ===================================================================== settings

    private void settings() {
        LinearLayout col = page("AI Settings", "Every option here changes how the AI plays or what it records.");
        AISettings s = AISettingsManager.settings(this);

        LinearLayout c1 = card(col);
        c1.addView(text("Difficulty / skill", 16, GOLD));
        c1.addView(text("Beginner: reacts 0.26 s late, aims loosely, no abilities. Intermediate: 0.12 s, leads shots, uses abilities. Advanced: instant, precise, looks further ahead for projectiles. Adaptive: Advanced plus learning — tries strategy variants and settles on what scores best on your game.", 12, DIM));
        choice(c1, new String[]{"BEGINNER", "INTERMEDIATE", "ADVANCED", "ADAPTIVE"}, s.skill.name(), v -> { s.skill = AISettings.Skill.valueOf(v); AISettingsManager.save(this, s); });

        LinearLayout c2 = card(col);
        c2.addView(text("Playstyle", 16, GOLD));
        c2.addView(text("Aggressive fights up close (2.2 tiles) and uses abilities eagerly. Defensive keeps 5.5 tiles away and dodges hardest. Balanced is in between. Exploration-focused walks every room and grabs items.", 12, DIM));
        choice(c2, new String[]{"AGGRESSIVE", "DEFENSIVE", "BALANCED", "EXPLORER"}, s.style.name(), v -> { s.style = AISettings.Style.valueOf(v); AISettingsManager.save(this, s); });

        LinearLayout c3 = card(col);
        c3.addView(text("Logging", 16, GOLD));
        c3.addView(text("Basic: rooms, damage, kills of bosses, items, explained decisions. Detailed: also every enemy seen, attacks and dodges. Developer Debug: also a line per AI tick.", 12, DIM));
        choice(c3, new String[]{"BASIC", "DETAILED", "DEBUG"}, s.logLevel.name(), v -> { s.logLevel = AISettings.LogLevel.valueOf(v); AISettingsManager.save(this, s); });

        LinearLayout c4 = card(col);
        c4.addView(text("Performance", 16, GOLD));
        c4.addView(text("Decisions per second (also how often the screen is read). Higher reacts faster but uses more battery.", 12, DIM));
        choice(c4, new String[]{"5", "10", "15", "20", "30"}, Integer.toString(s.decisionHz), v -> { s.decisionHz = Integer.parseInt(v); AISettingsManager.save(this, s); });
        toggle(c4, "Debug overlay (boxes, path, state — drawn in green)", s.debugOverlay, v -> { s.debugOverlay = v; AISettingsManager.save(this, s); if (!v && ControlService.instance != null) ControlService.instance.hideDebug(); });
        c4.addView(text("Maximum log storage (oldest runs are deleted beyond this)", 12, DIM));
        choice(c4, new String[]{"10", "25", "50", "100", "250"}, Integer.toString(s.maxLogMb), v -> { s.maxLogMb = Integer.parseInt(v); AISettingsManager.save(this, s); });
        toggle(c4, "Automatic log export to Downloads after each run", s.autoExport, v -> { s.autoExport = v; AISettingsManager.save(this, s); });
        choice(c4, new String[]{"TXT", "JSON", "CSV"}, s.autoExportFormat, v -> { s.autoExportFormat = v; AISettingsManager.save(this, s); });

        LinearLayout c5 = card(col);
        c5.addView(text("Behaviour", 16, GOLD));
        toggle(c5, "Use abilities (DIVE, SMASH, DROP, SCREAM, ULT)", s.useAbilities, v -> { s.useAbilities = v; AISettingsManager.save(this, s); });
        toggle(c5, "Learn from runs (saves experience after each run)", s.learning, v -> { s.learning = v; AISettingsManager.save(this, s); });
        LinearLayout r = row(c5);
        small(r, "Re-run button test next run", v -> {
            io.execute(() -> { Learner l = repo.loadLearner(); l.buttonsTested = false; l.diveButton = -1; repo.saveLearner(l); });
            toast("The bot will test the 4 buttons again at the start of the next calm moment");
        });
        small(r, "Forget everything learned", v -> new AlertDialog.Builder(this).setTitle("Forget what the AI learned?")
                .setMessage("Strategy scores, enemy danger, button values, measured speeds and mistake counts are erased. Run logs are kept.")
                .setPositiveButton("Forget", (d, w) -> io.execute(() -> { repo.resetLearner(); main.post(() -> toast("Experience reset")); }))
                .setNegativeButton("Cancel", null).show());
    }

    private interface Pick { void on(String v); }
    private interface Flip { void on(boolean v); }

    private void choice(LinearLayout parent, String[] opts, String cur, Pick p) {
        LinearLayout r = row(parent);
        List<Button> bs = new ArrayList<>();
        for (String o : opts) {
            Button b = small(r, o.charAt(0) + o.substring(1).toLowerCase(Locale.ROOT), null);
            bs.add(b);
        }
        Runnable paint = () -> { };
        for (int i = 0; i < opts.length; i++) {
            final int k = i;
            bs.get(i).setOnClickListener(v -> {
                p.on(opts[k]);
                for (int j = 0; j < bs.size(); j++) ((GradientDrawable) bs.get(j).getBackground()).setColor(j == k ? ACCENT : 0xFF2C1C3C);
            });
            ((GradientDrawable) bs.get(i).getBackground()).setColor(opts[i].equals(cur) ? ACCENT : 0xFF2C1C3C);
        }
    }

    private void toggle(LinearLayout parent, String label, boolean on, Flip f) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(TEXT);
        sw.setChecked(on);
        sw.setPadding(0, dp(8), 0, dp(8));
        sw.setOnCheckedChangeListener((b, v) -> f.on(v));
        parent.addView(sw);
    }

    // ===================================================================== history

    private final Set<String> selected = new HashSet<>();

    private void history(String filter, String query) {
        LinearLayout col = page("Run History", "Tap a run to open it. Long-press two runs to compare them.");
        LinearLayout chart = card(col);
        chart.addView(text("Improvement over time", 16, GOLD));
        TrendView tv = new TrendView(this);
        chart.addView(tv);
        TextView imp = text("…", 12, DIM);
        chart.addView(imp);

        LinearLayout f = row(col);
        for (String o : new String[]{"All", "Victory", "Death", "Stopped", "Error", "Interrupted"}) {
            Button b = small(f, o, v -> go(() -> history(o, query), false));
            ((GradientDrawable) b.getBackground()).setColor(o.equals(filter) ? ACCENT : 0xFF2C1C3C);
        }
        EditText search = new EditText(this);
        search.setHint("Search events in all runs (e.g. boss, stuck, DIVE)");
        search.setHintTextColor(DIM);
        search.setTextColor(TEXT);
        search.setText(query);
        search.setSingleLine(true);
        search.setOnEditorActionListener((v, a, e) -> { go(() -> history(filter, search.getText().toString().trim()), false); return true; });
        col.addView(search);
        Button cmp = button(col, "Compare selected runs (" + selected.size() + "/2)", 0xFF5A4A6E, v -> {
            if (selected.size() != 2) { toast("Long-press exactly two runs"); return; }
            List<String> ids = new ArrayList<>(selected);
            go(() -> compare(ids.get(0), ids.get(1)), true);
        });
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        list.addView(text("Loading…", 13, DIM));

        io.execute(() -> {
            List<Map<String, Object>> all = repo.list();
            List<double[]> trend = PerformanceAnalyzer.trend(all);
            String improvement = PerformanceAnalyzer.improvement(all);
            List<Map<String, Object>> shown = new ArrayList<>();
            Map<String, Integer> hits = new java.util.HashMap<>();
            for (Map<String, Object> m : all) {
                String o = Json.str(m, "outcome", "");
                if (!filter.equals("All") && !o.equalsIgnoreCase(filter)) continue;
                if (!query.isEmpty()) {
                    int n = repo.search(Json.str(m, "runId", ""), query).size();
                    if (n == 0) continue;
                    hits.put(Json.str(m, "runId", ""), n);
                }
                shown.add(m);
            }
            main.post(() -> {
                tv.setData(trend);
                imp.setText(improvement);
                list.removeAllViews();
                if (shown.isEmpty()) list.addView(text("No runs" + (query.isEmpty() ? "" : " with \"" + query + "\""), 14, DIM));
                for (Map<String, Object> m : shown) {
                    String id = Json.str(m, "runId", "");
                    LinearLayout c = card(list);
                    Map<String, Object> st = PerformanceAnalyzer.stats(m);
                    TextView t1 = text(id + "   " + Json.str(m, "outcome", Json.str(m, "status", "")), 15, outcomeColor(Json.str(m, "outcome", "")));
                    t1.setTypeface(Typeface.DEFAULT_BOLD);
                    c.addView(t1);
                    c.addView(text(String.format(Locale.US, "%s · %s · score %.0f · rooms %d · kills %d · %s/%s%s",
                            RunHistoryRepository.wallTime(Json.num(m, "startWall", 0)), PerformanceAnalyzer.duration(Json.num(m, "durationMs", 0)),
                            Json.num(m, "fitness", 0), (int) Json.num(st, "roomsEntered", 0), (int) Json.num(st, "enemiesDefeated", 0),
                            Json.str(m, "skill", ""), Json.str(m, "style", ""), hits.containsKey(id) ? " · " + hits.get(id) + " matching events" : ""), 12, DIM));
                    if (selected.contains(id)) c.setBackgroundColor(0xFF3A1F48);
                    c.setOnClickListener(v -> go(() -> runDetail(id), true));
                    c.setOnLongClickListener(v -> {
                        if (selected.contains(id)) selected.remove(id);
                        else { if (selected.size() >= 2) selected.clear(); selected.add(id); }
                        cmp.setText("Compare selected runs (" + selected.size() + "/2)");
                        c.setBackgroundColor(selected.contains(id) ? 0xFF3A1F48 : CARD);
                        return true;
                    });
                }
            });
        });
    }

    private int outcomeColor(String o) {
        switch (o) {
            case "Victory": return GOLD;
            case "Death": return 0xFFFF6B6B;
            case "Error": return 0xFFFF9F43;
            case "Interrupted": return 0xFF9AA0FF;
            default: return TEXT;
        }
    }

    // ===================================================================== run detail

    private void runDetail(String id) {
        LinearLayout col = page("AI RUN RESULTS", id);
        LinearLayout c = card(col);
        TextView sum = mono("Loading…");
        c.addView(sum);
        LinearLayout r = row(col);
        small(r, "Full log", v -> go(() -> fullLog(id, ""), true));
        small(r, "Export TXT", v -> export(id, "TXT"));
        small(r, "Export JSON", v -> export(id, "JSON"));
        small(r, "Export CSV", v -> export(id, "CSV"));
        small(r, "Compare…", v -> { selected.clear(); selected.add(id); toast("Long-press one more run in the history"); go(() -> history("All", ""), true); });
        small(r, "Delete", v -> new AlertDialog.Builder(this).setTitle("Delete " + id + "?").setMessage("Its log is removed from this phone.")
                .setPositiveButton("Delete", (d, w) -> io.execute(() -> { repo.delete(id); main.post(() -> { toast(id + " deleted"); onBackPressed(); }); }))
                .setNegativeButton("Cancel", null).show());
        io.execute(() -> {
            Map<String, Object> m = repo.meta(id);
            String txt = m == null ? "Run not found" : PerformanceAnalyzer.summaryText(m);
            main.post(() -> sum.setText(txt));
        });
        // a live run keeps updating
        RunSessionManager s = BotState.session;
        if (s != null && s.isLive() && s.runId.equals(id)) startTicker(() -> io.execute(() -> {
            Map<String, Object> m = repo.meta(id);
            if (m != null) main.post(() -> sum.setText(PerformanceAnalyzer.summaryText(m)));
        }), 2000);
    }

    private void export(String id, String fmt) {
        io.execute(() -> { String msg = Exporter.export(this, repo, id, fmt); main.post(() -> toast(msg)); });
    }

    private void fullLog(String id, String query) {
        LinearLayout col = page("Log " + id, "In-game time — event. Decisions show situation, decision, reason, result and assessment.");
        EditText search = new EditText(this);
        search.setHint("Search this log");
        search.setHintTextColor(DIM);
        search.setTextColor(TEXT);
        search.setSingleLine(true);
        search.setText(query);
        search.setOnEditorActionListener((v, a, e) -> { go(() -> fullLog(id, search.getText().toString().trim()), false); return true; });
        col.addView(search);
        TextView body = mono("Loading…");
        card(col).addView(body);
        io.execute(() -> {
            List<Map<String, Object>> ev = query.isEmpty() ? repo.events(id) : repo.search(id, query);
            String t = formatEvents(ev, 3000);
            main.post(() -> body.setText(t));
        });
    }

    @SuppressWarnings("unchecked")
    static String formatEvents(List<Map<String, Object>> ev, int max) {
        StringBuilder b = new StringBuilder();
        int start = Math.max(0, ev.size() - max);
        if (start > 0) b.append("… ").append(start).append(" earlier events (export the log to see all) …\n");
        for (int i = start; i < ev.size(); i++) {
            Map<String, Object> e = ev.get(i);
            b.append(Json.str(e, "clock", "")).append(" — ").append(Json.str(e, "type", "")).append(": ").append(Json.str(e, "msg", "")).append('\n');
            if ("DECISION".equals(Json.str(e, "type", "")) && e.get("data") instanceof Map) {
                Map<String, Object> d = (Map<String, Object>) e.get("data");
                for (String k : new String[]{"situation", "decision", "reason", "result", "assessment"})
                    b.append("      ").append(Character.toUpperCase(k.charAt(0))).append(k.substring(1)).append(": ").append(Json.str(d, k, "")).append('\n');
            }
        }
        if (ev.isEmpty()) b.append("(no events)");
        return b.toString();
    }

    private void liveLog() {
        RunSessionManager s = BotState.session;
        String id = s != null && s.isLive() ? s.runId : BotState.lastFinishedRunId;
        if ((id == null || id.isEmpty())) {
            List<Map<String, Object>> all = repo.list();
            if (!all.isEmpty()) id = Json.str(all.get(0), "runId", "");
        }
        final String rid = id;
        LinearLayout col = page("View Logs", rid == null || rid.isEmpty() ? "No runs yet" : rid + (s != null && s.isLive() && s.runId.equals(rid) ? " — live, refreshes every second" : ""));
        if (rid == null || rid.isEmpty()) return;
        TextView body = mono("Loading…");
        card(col).addView(body);
        Runnable load = () -> io.execute(() -> {
            String t = formatEvents(repo.events(rid), 400);
            main.post(() -> body.setText(t));
        });
        load.run();
        if (s != null && s.isLive() && s.runId.equals(rid)) startTicker(load, 1000);
        LinearLayout r = row(col);
        small(r, "Open run", v -> go(() -> runDetail(rid), true));
    }

    // ===================================================================== compare

    private void compare(String a, String b) {
        LinearLayout col = page("Compare runs", a + "  vs  " + b);
        LinearLayout c = card(col);
        TextView t = mono("Loading…");
        c.addView(t);
        io.execute(() -> {
            List<String[]> rows = PerformanceAnalyzer.compare(repo.meta(a), repo.meta(b));
            StringBuilder s = new StringBuilder(String.format(Locale.US, "%-28s %12s %12s %8s%n", "", a, b, "change"));
            for (String[] r : rows) s.append(String.format(Locale.US, "%-28s %12s %12s %8s%n", r[0], r[1], r[2], r[3]));
            main.post(() -> t.setText(s.toString()));
        });
    }

    // ===================================================================== statistics

    private void stats() {
        LinearLayout col = page("AI Statistics", "Totals over all stored runs, and what the AI has learned.");
        LinearLayout c1 = card(col);
        c1.addView(text("Improvement over time", 16, GOLD));
        TrendView tv = new TrendView(this);
        c1.addView(tv);
        TextView imp = text("…", 13, TEXT);
        c1.addView(imp);
        LinearLayout c2 = card(col);
        c2.addView(text("Totals", 16, GOLD));
        TextView tot = mono("…");
        c2.addView(tot);
        LinearLayout c3 = card(col);
        c3.addView(text("What the AI has learned", 16, GOLD));
        TextView learned = mono("…");
        c3.addView(learned);
        io.execute(() -> {
            List<Map<String, Object>> all = repo.list();
            List<double[]> trend = PerformanceAnalyzer.trend(all);
            String improvement = PerformanceAnalyzer.improvement(all);
            Map<String, Object> t = PerformanceAnalyzer.totals(all);
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.US, "Runs %d · victories %d · deaths %d · play time %s · best score %.0f%n",
                    (int) Json.num(t, "runs", 0), (int) Json.num(t, "victories", 0), (int) Json.num(t, "deaths", 0),
                    PerformanceAnalyzer.duration(Json.num(t, "timeMs", 0)), Json.num(t, "bestScore", 0)));
            int n = Math.max(1, (int) Json.num(t, "runs", 0));
            for (String[] r : PerformanceAnalyzer.SUMMARY_ROWS)
                s.append(String.format(Locale.US, "%-30s %8.0f  (%.1f per run)%n", r[1], Json.num(t, r[0], 0), Json.num(t, r[0], 0) / n));
            Learner l = repo.loadLearner();
            StringBuilder ls = new StringBuilder();
            ls.append("Strategy variants (Adaptive skill):\n");
            for (int i = 0; i < Learner.ARMS.length; i++)
                ls.append(String.format(Locale.US, "  %-20s %3d runs  %s%s%n", Learner.ARM_NAMES[i], l.armRuns[i],
                        l.armRuns[i] == 0 ? "" : String.format(Locale.US, "avg %.0f", l.armSum[i] / l.armRuns[i]), i == l.bestArm() && l.armRuns[i] >= 3 ? "  ← best" : ""));
            ls.append(String.format(Locale.US, "%nMeasured walking speed %.1f tiles/s, note speed %.1f tiles/s%n", l.walkTilesPerS, l.shotTilesPerS));
            ls.append("DIVE button: ").append(l.buttonsTested ? (l.diveButton >= 0 ? "button " + (l.diveButton + 1) : "not found by the test") : "not tested yet").append('\n');
            ls.append("\nEnemy types (by look) — danger = how often they hurt the character:\n");
            List<Map.Entry<String, Learner.EnemyStats>> es = new ArrayList<>(l.enemies.entrySet());
            es.sort((x, y) -> Float.compare(y.getValue().danger(), x.getValue().danger()));
            for (Map.Entry<String, Learner.EnemyStats> e : es)
                ls.append(String.format(Locale.US, "  %-18s met %4d  killed %4d  hurt us %3d  danger %.2f%n", e.getKey(), e.getValue().encounters, e.getValue().kills, e.getValue().hurtNear, e.getValue().danger()));
            ls.append("\nButtons — value = kills after a press vs getting hurt:\n");
            for (int i = 0; i < 4; i++) ls.append(String.format(Locale.US, "  button %d: used %d, kills after %d, hurt after %d, value %.2f%n", i + 1, l.btnUses[i], l.btnKills[i], l.btnHurt[i], l.buttonValue(i)));
            ls.append("\nRepeated mistakes:\n");
            List<Map.Entry<String, Integer>> ms = new ArrayList<>(l.mistakes.entrySet());
            ms.sort((x, y) -> y.getValue() - x.getValue());
            for (Map.Entry<String, Integer> e : ms) ls.append(String.format(Locale.US, "  %4d × %s%n", e.getValue(), e.getKey()));
            if (ms.isEmpty()) ls.append("  none yet\n");
            main.post(() -> { tv.setData(trend); imp.setText(improvement); tot.setText(s.toString()); learned.setText(ls.toString()); });
        });
    }
}
