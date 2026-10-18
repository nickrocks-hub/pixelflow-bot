package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.ModelStore;
import com.nickrocks.pixelflowbot.learn.Stats;
import com.nickrocks.pixelflowbot.learn.Transition;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * AI Lab: train the agent in the simulator, evaluate it, and manage models and logs.
 * Playing the real game happens from the floating panel (◆ Nonogram AI), using the same model.
 */
public class LearnActivity extends Activity {
    private static final int REQ_IMPORT = 7;

    private AiHub hub;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button[] modeButtons;
    private Button simStart, simPause;
    private final boolean[] sizeOn = {true, true, false};
    private Button[] sizeButtons;
    private TextView live, statsText, learnedText, logText, modelText;
    private Curve curve;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hub = AiHub.get(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));

        TextView title = text("🧠 AI Lab", 28, Color.WHITE);
        title.setTypeface(Ui.BLACK);
        root.addView(title);
        TextView sub = text("A learning agent for Nonogram: it observes, acts, gets rewards, stores every experience and "
                + "improves its policy. Train it here in the simulator (safe — no hearts lost), then let it play the real "
                + "game from the floating panel: ◆ Nonogram AI.", 13, Ui.TEXT_DIM);
        sub.setPadding(0, dp(6), 0, dp(4));
        root.addView(sub);
        TextView loaded = text(hub.loadNote, 12, Ui.NEON);
        root.addView(loaded);

        // Mode
        root.addView(section("MODE"));
        LinearLayout modes = new LinearLayout(this);
        String[] labels = {"Training", "Evaluation", "Play"};
        modeButtons = new Button[3];
        for (int i = 0; i < 3; i++) {
            final Agent.Mode m = Agent.Mode.values()[i];
            modeButtons[i] = small(labels[i], Ui.MUTED, v -> { synchronized (hub.lock) { hub.agent.mode = m; } refresh(); });
            modes.addView(modeButtons[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(modes);
        root.addView(note("Training explores (ε-greedy) and updates the model. Evaluation plays greedily and never learns — "
                + "use it to measure progress honestly. Play uses the saved model with no exploration and no learning."));

        // Simulator
        root.addView(section("SIMULATOR"));
        LinearLayout simCard = card();
        LinearLayout sizes = new LinearLayout(this);
        String[] sl = {"5×5", "10×10", "15×15"};
        sizeButtons = new Button[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            sizeButtons[i] = small(sl[i], Ui.MUTED, v -> { sizeOn[k] = !sizeOn[k]; if (!sizeOn[0] && !sizeOn[1] && !sizeOn[2]) sizeOn[k] = true; refresh(); });
            sizes.addView(sizeButtons[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        simCard.addView(sizes);
        LinearLayout ctl = new LinearLayout(this);
        simStart = small("▶ Start", Ui.ACCENT, v -> hub.startSim(sizes()));
        simPause = small("⏸ Pause", Ui.MUTED, v -> hub.simPaused = !hub.simPaused);
        ctl.addView(simStart, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ctl.addView(simPause, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ctl.addView(small("■ Stop", Ui.DANGER, v -> hub.stopSim()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        simCard.addView(ctl);
        live = text("", 12, Ui.TEXT);
        live.setTypeface(Ui.MONO);
        live.setPadding(0, dp(8), 0, 0);
        simCard.addView(live);
        curve = new Curve();
        simCard.addView(curve, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));
        root.addView(simCard);

        // Stats
        root.addView(section("RESULTS BY MODE"));
        LinearLayout statsCard = card();
        statsText = text("", 12, Ui.TEXT);
        statsText.setTypeface(Ui.MONO);
        statsCard.addView(statsText);
        root.addView(statsCard);

        // What it learned
        root.addView(section("WHAT IT HAS LEARNED"));
        LinearLayout lc = card();
        learnedText = text("", 12, Ui.TEXT);
        learnedText.setTypeface(Ui.MONO);
        lc.addView(learnedText);
        lc.addView(note("Each number is how much the agent trusts that clue about a cell (+ = fill it, − = avoid it). They start at 0 and are learned from rewards."));
        root.addView(lc);

        // Models
        root.addView(section("MODEL"));
        LinearLayout mc = card();
        modelText = text("", 12, Ui.TEXT_DIM);
        mc.addView(modelText);
        LinearLayout m1 = new LinearLayout(this);
        m1.addView(small("Save version", Ui.ACCENT, v -> saveDialog()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        m1.addView(small("Load…", Ui.INFO, v -> loadDialog()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mc.addView(m1);
        LinearLayout m2 = new LinearLayout(this);
        m2.addView(small("Export", Ui.MUTED, v -> exportModel()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        m2.addView(small("Import…", Ui.MUTED, v -> importModel()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        m2.addView(small("Reset…", Ui.DANGER, v -> resetDialog()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mc.addView(m2);
        root.addView(mc);

        // Logs
        root.addView(section("RECENT EXPERIENCES (REAL GAME)"));
        LinearLayout logCard = card();
        logText = text("", 11, Ui.TEXT_DIM);
        logText.setTypeface(Ui.MONO);
        logCard.addView(logText);
        logCard.addView(small("Export experiences", Ui.MUTED, v -> exportLog()));
        root.addView(logCard);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.BG, Ui.BG_2}));
        sv.addView(root);
        setContentView(sv);
        refresh();
        refreshLog();
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(tick); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    private int[] sizes() {
        int n = 0;
        for (boolean b : sizeOn) if (b) n++;
        int[] out = new int[n];
        int k = 0;
        int[] all = {5, 10, 15};
        for (int i = 0; i < 3; i++) if (sizeOn[i]) out[k++] = all[i];
        return out;
    }

    private void refresh() {
        Agent a = hub.agent;
        for (int i = 0; i < 3; i++) Ui.tint(modeButtons[i], a.mode.ordinal() == i ? Ui.GAME_NONO : Ui.MUTED, dp(12));
        for (int i = 0; i < 3; i++) Ui.tint(sizeButtons[i], sizeOn[i] ? Ui.INFO : Ui.MUTED, dp(12));
        simStart.setText(hub.simRunning ? "● Running" : "▶ Start");
        simPause.setText(hub.simRunning && hub.simPaused ? "▶ Resume" : "⏸ Pause");
        String l;
        synchronized (hub.lock) {
            l = String.format(Locale.US, "mode %s · ε %.2f · %s%nmodel updates %d · replay %d · %.0f steps/s",
                    a.mode, a.epsilon(), hub.simRunning ? (hub.simPaused ? "paused" : "running") : "stopped",
                    a.model.updates, a.replay.size(), hub.simRunning ? hub.stepsPerSecond : 0f);
            if (!hub.simLine.isEmpty()) l += "\n" + hub.simLine;
            StringBuilder sb = new StringBuilder();
            for (Agent.Mode m : Agent.Mode.values()) {
                Stats s = a.stats.get(m);
                sb.append(String.format(Locale.US, "%-10s %s%n", m.name().toLowerCase(Locale.US), s.episodes == 0 ? "—" : s.line()));
            }
            statsText.setText(sb.toString().trim());
            learnedText.setText(a.model.updates == 0 ? "Nothing yet — start training." : a.model.explain(10).trim());
        }
        live.setText(l);
        List<ModelStore.Info> versions = hub.store.list();
        modelText.setText(versions.isEmpty() ? "No saved versions yet. Training autosaves every 200 episodes."
                : "Latest saved: " + versions.get(0) + "\nFolder: " + hub.store.dir.getPath());
        curve.invalidate();
    }

    private void refreshLog() {
        List<String> lines = hub.experiences.tail(14);
        if (lines.isEmpty()) { logText.setText("No real-game experiences yet."); return; }
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            try {
                Transition t = Transition.fromJson(s);
                sb.append(String.format(Locale.US, "ep%d #%d %s %s → %+.0f %s%s%n", t.episode, t.step, t.mode.substring(0, 1),
                        t.action, t.reward, t.note, t.explored ? " (explore)" : ""));
            } catch (Exception e) {
                sb.append(s.length() > 80 ? s.substring(0, 80) : s).append('\n');
            }
        }
        logText.setText(sb.toString().trim());
    }

    // ------------------------------------------------------------------ model actions

    private void saveDialog() {
        EditText et = new EditText(this);
        et.setHint("Note (optional)");
        new AlertDialog.Builder(this).setTitle("Save a new model version").setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        ModelStore.Info i = hub.saveVersion(et.getText().toString().trim());
                        toast("Saved v" + i.version);
                    } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
                    refresh();
                }).setNegativeButton("Cancel", null).show();
    }

    private void loadDialog() {
        List<ModelStore.Info> vs = hub.store.list();
        if (vs.isEmpty()) { toast("No saved versions yet."); return; }
        String[] items = new String[vs.size()];
        for (int i = 0; i < items.length; i++) items[i] = vs.get(i).toString();
        new AlertDialog.Builder(this).setTitle("Load a model version").setItems(items, (d, which) -> {
            if (hub.simRunning) { toast("Stop the simulator first."); return; }
            try {
                hub.load(vs.get(which).file);
                toast("Loaded v" + vs.get(which).version);
            } catch (Exception e) { toast("Load failed: " + e.getMessage()); }
            refresh();
        }).show();
    }

    private void exportModel() {
        try {
            hub.autosave();
            String where = hub.exportToDownloads(new File(hub.store.dir, "model_latest.json"),
                    "nonogram_model_" + System.currentTimeMillis() + ".json", "application/json");
            toast("Exported to " + where);
        } catch (Exception e) { toast("Export failed: " + e.getMessage()); }
    }

    private void exportLog() {
        try {
            hub.experiences.flush();
            File f = hub.experiences.file();
            if (!f.exists()) { toast("No experiences yet."); return; }
            toast("Exported to " + hub.exportToDownloads(f, "nonogram_experiences_" + System.currentTimeMillis() + ".jsonl", "text/plain"));
        } catch (Exception e) { toast("Export failed: " + e.getMessage()); }
    }

    private void importModel() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        if (hub.simRunning) { toast("Stop the simulator first."); return; }
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            ModelStore.Info info = hub.importModel(in);
            toast("Imported as v" + info.version);
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
        refresh();
    }

    private void resetDialog() {
        new AlertDialog.Builder(this).setTitle("Reset all learned data?")
                .setMessage("This deletes the model, every saved version, all statistics and the experience log. It can't be undone. (Export first if you want a copy.)")
                .setPositiveButton("Delete everything", (d, w) -> {
                    hub.resetAll();
                    toast("Learned data reset.");
                    refresh();
                    refreshLog();
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ chart

    /** Recent (last 50) win rate as training goes on. */
    private final class Curve extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), label = new Paint(Paint.ANTI_ALIAS_FLAG);
        Curve() {
            super(LearnActivity.this);
            line.setColor(Ui.NEON);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            grid.setColor(0x22FFFFFF);
            label.setColor(Ui.TEXT_FAINT);
            label.setTextSize(dp(10));
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), top = dp(14), bot = h - dp(4);
            for (int k = 0; k <= 4; k++) { float y = bot - (bot - top) * k / 4f; c.drawLine(0, y, w, y, grid); }
            c.drawText("win rate (last 50) — 100% top", 0, dp(10), label);
            List<float[]> pts;
            synchronized (hub.lock) { pts = new java.util.ArrayList<>(hub.curve); }
            if (pts.size() < 2) return;
            float e0 = pts.get(0)[0], e1 = pts.get(pts.size() - 1)[0];
            Path p = new Path();
            for (int i = 0; i < pts.size(); i++) {
                float x = e1 > e0 ? (pts.get(i)[0] - e0) / (e1 - e0) * w : 0;
                float y = bot - (bot - top) * pts.get(i)[1];
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            c.drawPath(p, line);
        }
    }

    // ------------------------------------------------------------------ widgets

    private TextView section(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setTypeface(Ui.BLACK);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(20), 0, dp(8));
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 12, Ui.TEXT_FAINT);
        t.setPadding(dp(2), dp(6), 0, 0);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(Ui.card(dp(18), dp(1), Ui.CARD_STROKE));
        return c;
    }

    private Button small(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(Ui.MEDIUM);
        b.setBackground(Ui.button(color, dp(12)));
        b.setStateListAnimator(null);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(4), dp(3), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        t.setGravity(Gravity.START);
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
