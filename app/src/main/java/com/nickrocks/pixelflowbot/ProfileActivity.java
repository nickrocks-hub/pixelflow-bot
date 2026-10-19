package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.pixelflowbot.core.Adapters;
import com.nickrocks.pixelflowbot.core.Experience;
import com.nickrocks.pixelflowbot.core.ExperienceStore;
import com.nickrocks.pixelflowbot.core.GameProfile;
import com.nickrocks.pixelflowbot.core.ProfileStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One game profile: what the platform can and can't do for it (from real data, nothing assumed),
 * the user's stated objective and pace, stored experience, and profile version history.
 */
public class ProfileActivity extends Activity {
    static final String EXTRA_ID = "profile";

    private AgentPlatform pl;
    private String id;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pl = AgentPlatform.get(this);
        id = getIntent().getStringExtra(EXTRA_ID);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.BG, Ui.BG_2}));
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        root.removeAllViews();
        GameProfile p = pl.profiles.get(id);
        if (p == null) { finish(); return; }
        Adapters.Info ad = Adapters.get(p.adapterId);

        TextView title = text(p.name, 26, Color.WHITE);
        title.setTypeface(Ui.BLACK);
        root.addView(title);
        TextView sub = text((p.packageName != null ? p.packageName + " · " : "built into this app · ") + "profile v" + p.version, 12, Ui.TEXT_FAINT);
        sub.setTypeface(Ui.MONO);
        root.addView(sub);

        // ---- Status
        root.addView(section("STATUS"));
        LinearLayout st = card();
        LocalSettings cs = new LocalSettings(this);
        boolean calibrated = "yes".equals(p.settings.get("calibration_confirmed"));
        TextView chip = text(p.support == GameProfile.Support.SUPPORTED ? "● Built-in player"
                        : !cs.ready() ? "● On-device AI — needs a model" : calibrated ? "● On-device AI — calibrated" : "● On-device AI — needs calibration",
                14, p.support == GameProfile.Support.SUPPORTED || (cs.ready() && calibrated) ? 0xFF5EEAD4 : 0xFFFCD34D);
        chip.setTypeface(Ui.MEDIUM);
        st.addView(chip);
        if (ad != null) {
            st.addView(body("Adapter: " + ad.title));
            st.addView(body("Works: " + ad.works));
            st.addView(body("Limits: " + ad.limits));
        } else {
            st.addView(body("No built-in player, so this game is played by the on-device AI: it looks at the screen, reads your "
                    + "instructions below, works out the board and controls (🔍 Calibrate), then plays step by step and keeps notes on what it learns."));
            st.addView(body("The model runs on this phone; nothing goes over the internet. Each step takes several seconds or more, "
                    + "so it suits puzzles, turn-based games and menus, not reflex games. It never taps ads, purchases or sign-ins."));
            st.addView(small(cs.ready() ? "On-device AI settings (" + cs.modelName() + ")" : "Import an on-device model", cs.ready() ? Ui.MUTED : Ui.INFO,
                    v -> startActivity(new Intent(this, ModelActivity.class))));
        }
        root.addView(st);
        if (p.packageName != null) {
            boolean playable = ad != null || cs.ready();
            root.addView(action(playable ? "▶  Launch & play" : "Open " + p.name, playable ? Ui.ACCENT : Ui.INFO, v -> launch(p, playable)));
            root.addView(note(ad != null ? howTo(p.adapterId) : cs.ready()
                    ? "On a game screen press 🔍 Calibrate on the floating panel, confirm the result below, then ▶ Start."
                    : "Import an on-device model first; until then the panel can only save screenshots."));
        }

        // ---- Instructions (the owner's words; the on-device AI follows them)
        root.addView(section("YOUR INSTRUCTIONS FOR THE AI"));
        LinearLayout ob = card();
        String objective = p.settings.get("objective");
        ob.addView(body(objective == null || objective.isEmpty()
                ? "Not written yet. Describe the goal, how you play (tap / swipe / drag what), what to avoid, and how to start or continue a level."
                : objective));
        ob.addView(body("Pace: " + paceText(p.pace)));
        LinearLayout row = new LinearLayout(this);
        row.addView(small("Edit instructions", Ui.MUTED, v -> editObjective(p)), weight());
        row.addView(small("Change pace", Ui.MUTED, v -> editPace(p)), weight());
        ob.addView(row);
        root.addView(ob);

        // ---- Calibration and learned rules (on-device AI)
        if (ad == null) {
            root.addView(section("CALIBRATION (FROM THE SCREEN)"));
            LinearLayout cc = card();
            String cal = p.settings.get("calibration");
            if (cal == null || cal.isEmpty()) {
                cc.addView(body("Not calibrated yet. Open the game, then press 🔍 Calibrate on the floating panel: the agent describes the screen, "
                        + "the board, the controls and the goal, and asks about anything it can't work out."));
            } else {
                cc.addView(body(cal));
                String q = p.settings.get("calibration_questions");
                if (q != null && !q.isEmpty()) cc.addView(body("Questions for you — answer them in your instructions:\n• " + q.replace("\n", "\n• ")));
                cc.addView(note(calibrated ? "✓ Confirmed by you. The agent treats this as known." : "Proposed by the model (confidence "
                        + p.settings.get("calibration_confidence") + "). Not used as fact until you confirm it."));
                LinearLayout cr = new LinearLayout(this);
                if (!calibrated) cr.addView(small("✓ Confirm", Ui.ACCENT, v -> { p.settings.put("calibration_confirmed", "yes"); save(p); }), weight());
                cr.addView(small("Edit", Ui.MUTED, v -> editSetting(p, "calibration", "Correct the calibration", true)), weight());
                cr.addView(small("Clear", Ui.DANGER, v -> { p.settings.remove("calibration"); p.settings.remove("calibration_confirmed");
                    p.settings.remove("calibration_questions"); p.settings.remove("board"); save(p); }), weight());
                cc.addView(cr);
            }
            root.addView(cc);

            root.addView(section("WHAT IT HAS LEARNED (HYPOTHESES)"));
            LinearLayout lc = card();
            String learned = p.settings.get("learned");
            lc.addView(body(learned == null || learned.isEmpty() ? "Nothing yet. After 25 steps and at the end of each session the agent writes down "
                    + "the rules it believes, each with the steps that support it." : learned));
            lc.addView(note("Rule learning: notes kept by the model and fed into later sessions. It is not a trained policy — no weights are learned. "
                    + "Edit or clear anything that's wrong."));
            LinearLayout lr = new LinearLayout(this);
            lr.addView(small("Edit", Ui.MUTED, v -> editSetting(p, "learned", "Edit what it learned", false)), weight());
            lr.addView(small("Clear", Ui.DANGER, v -> { p.settings.remove("learned"); save(p); }), weight());
            lc.addView(lr);
            root.addView(lc);
        }

        // ---- Experience
        root.addView(section("EXPERIENCE ON THIS PHONE"));
        LinearLayout ex = card();
        ExperienceStore.Summary s = pl.experience.summary(p.id);
        ex.addView(body(s.episodes == 0 && s.steps == 0 ? "Nothing recorded yet."
                : String.format(Locale.US, "%d episodes · %d steps · total reward %.0f · %.1f KB · last %s",
                s.episodes, s.steps, s.totalReward, s.bytes / 1024f, s.lastTime == 0 ? "—" : date(s.lastTime))));
        List<Experience> rec = pl.experience.recent(p.id, 10);
        if (!rec.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Experience e : rec) {
                sb.append(String.format(Locale.US, "ep%d #%d %-5s ", e.episode, e.step, e.boundary.name()));
                if (e.boundary == Experience.Boundary.END) sb.append(e.endReason);
                else if (e.boundary == Experience.Boundary.START) sb.append(e.note);
                else sb.append(String.format(Locale.US, "%+.0f  %s  (%d ms)", e.reward, e.rewardSource, e.actionLatencyMs));
                sb.append('\n');
            }
            TextView t = text(sb.toString().trim(), 10.5f, Ui.TEXT);
            t.setTypeface(Ui.MONO);
            t.setPadding(0, dp(8), 0, 0);
            ex.addView(t);
        }
        LinearLayout er = new LinearLayout(this);
        er.addView(small("Export", Ui.MUTED, v -> export(p)), weight());
        er.addView(small("Delete…", Ui.DANGER, v -> confirm("Delete all experience for " + p.name + "?", () -> { pl.experience.clear(p.id); build(); })), weight());
        ex.addView(er);
        ex.addView(note("Stored only in this app's private storage. Nothing is sent anywhere; Export copies it to Download/AIGameBot."));
        root.addView(ex);

        // ---- Versions
        root.addView(section("PROFILE VERSIONS"));
        LinearLayout vc = card();
        List<Integer> vs = pl.profiles.versions(p.id);
        for (int v : vs.subList(0, Math.min(8, vs.size()))) {
            GameProfile old = pl.profiles.version(p.id, v);
            if (old == null) continue;
            String line = "v" + v + " · " + date(old.updated) + (v == p.version ? " · current" : "");
            if (v == p.version) vc.addView(body(line));
            else vc.addView(small(line + " · restore", Ui.MUTED, x -> confirm("Restore v" + v + "? It becomes a new version.", () -> {
                try { pl.profiles.save(old); } catch (Exception e) { toast("Restore failed: " + e.getMessage()); }
                build();
            })));
        }
        vc.addView(note("Every stored experience records the profile version that produced it."));
        root.addView(vc);

        if (!ProfileStore.shipped(p.id)) {
            Button del = action("Remove from library", Ui.DANGER, v -> confirm("Remove " + p.name + " and its history? (Experience is kept until you delete it.)", () -> {
                pl.profiles.delete(p.id);
                finish();
            }));
            ((LinearLayout.LayoutParams) del.getLayoutParams()).topMargin = dp(22);
            root.addView(del);
        }
    }

    private static final int REQ_CAPTURE = 41;
    private GameProfile pendingLaunch;

    private static String howTo(String adapterId) {
        if (Adapters.CANDY_CRUSH.id.equals(adapterId)) {
            return "Open a level, then press ▶ Start on the floating panel. It pauses on popups and level ends. For ⟳ Next level, press "
                    + "Teach next once on a level-complete screen and tap the button yourself.";
        }
        if (Adapters.FLOW.id.equals(adapterId)) {
            return "Open a level, then press ▶ Solve on the floating panel (🔍 Read board checks the reading first). For ⟳ Next level, press "
                    + "Teach next once on a level-complete screen and tap the button yourself.";
        }
        if (Adapters.MINECRAFT.id.equals(adapterId)) {
            return "Survival world, touch controls (not split controls), auto-jump on, landscape. Stand near trees with your hands free and "
                    + "close all menus, choose 🕹 Joystick or ✚ D-pad on the panel to match your layout, then press ▶ Start. It gathers about 4 logs, "
                    + "crafts, places a crafting table and makes wooden tools. ⏸ Pause or touching the screen stops it.";
        }
        if (Adapters.ARROWS.id.equals(adapterId)) {
            return "Open a level, then press ▶ Solve on the floating panel (🔍 Read board shows what it sees first). For ⟳ Next level, press "
                    + "Teach next once on a level-complete screen and tap the button yourself.";
        }
        return "Open a puzzle, then press ▶ Solve on the floating panel. ⟳ Next level moves on by itself.";
    }

    /** Checks the tap service and screen capture, then opens the game; the panel switches to it. */
    private void launch(GameProfile p, boolean playable) {
        if (playable && !TapService.isRunning()) {
            toast("Turn on the tap service first: find AI Game Bot under Accessibility → Installed apps.");
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (playable && !CaptureService.isRunning()) {
            pendingLaunch = p;
            toast("Allow screen capture (choose \"Entire screen\") so the bot can see the game.");
            android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
            return;
        }
        openApp(p);
    }

    private void openApp(GameProfile p) {
        getSharedPreferences(TapService.PREFS, MODE_PRIVATE).edit().putString("active_pkg", p.packageName).apply();
        Intent i = getPackageManager().getLaunchIntentForPackage(p.packageName);
        if (i == null) { toast(p.name + " isn't installed."); return; }
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) { toast("Screen capture not allowed — the bot can't see the game."); return; }
        startForegroundService(new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, res)
                .putExtra(CaptureService.EXTRA_DATA, data));
        GameProfile p = pendingLaunch;
        pendingLaunch = null;
        if (p != null) root.postDelayed(() -> openApp(p), 900);
    }

    private static String paceText(GameProfile.Pace p) {
        switch (p) {
            case TURN_BASED: return "turn-based (waits for you)";
            case REAL_TIME: return "real-time (moves on its own) — needs fast capture and taps";
            default: return "not sure yet";
        }
    }

    private void editSetting(GameProfile p, String key, String title, boolean resetsConfirm) {
        EditText et = new EditText(this);
        et.setText(p.settings.get(key) == null ? "" : p.settings.get(key));
        et.setMinLines(4);
        new AlertDialog.Builder(this).setTitle(title).setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    p.settings.put(key, et.getText().toString().trim());
                    if (resetsConfirm) p.settings.put("calibration_confirmed", "yes");   // your correction is the truth
                    save(p);
                }).setNegativeButton("Cancel", null).show();
    }

    private void editObjective(GameProfile p) {
        EditText et = new EditText(this);
        et.setText(p.settings.get("objective") == null ? "" : p.settings.get("objective"));
        et.setHint("e.g. Swap two neighbouring candies to make 3 in a row. Finish the level's goal (shown at the top) before moves run out. "
                + "Tap Play / Continue between levels. Never use boosters or buy anything.");
        et.setMinLines(5);
        new AlertDialog.Builder(this).setTitle("Instructions for the AI").setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    p.settings.put("objective", et.getText().toString().trim());
                    save(p);
                }).setNegativeButton("Cancel", null).show();
    }

    private void editPace(GameProfile p) {
        String[] o = {"Turn-based", "Real-time", "Not sure"};
        new AlertDialog.Builder(this).setTitle("Pace").setItems(o, (d, w) -> {
            p.pace = w == 0 ? GameProfile.Pace.TURN_BASED : w == 1 ? GameProfile.Pace.REAL_TIME : GameProfile.Pace.UNKNOWN;
            save(p);
        }).show();
    }

    private void save(GameProfile p) {
        try { pl.profiles.save(p); } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
        build();
    }

    private void export(GameProfile p) {
        File f = pl.experience.file(p.id);
        if (!f.exists()) { toast("Nothing recorded yet."); return; }
        String name = "experience_" + p.id + "_" + System.currentTimeMillis() + ".jsonl";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/AIGameBot");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("couldn't create the file");
                try (InputStream in = new FileInputStream(f); OutputStream os = getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                toast("Exported to Download/AIGameBot/" + name);
            } else {
                File out = new File(getExternalFilesDir("export"), name);
                java.nio.file.Files.copy(f.toPath(), out.toPath());
                toast("Exported to " + out.getPath());
            }
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Yes", (d, w) -> yes.run()).setNegativeButton("Cancel", null).show();
    }

    private static String date(long t) { return new SimpleDateFormat("MMM d HH:mm", Locale.US).format(new Date(t)); }

    // ------------------------------------------------------------------ widgets

    private TextView section(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setTypeface(Ui.BLACK);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(22), 0, dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(Ui.card(dp(18), dp(1), Ui.CARD_STROKE));
        return c;
    }

    private TextView body(String s) {
        TextView t = text(s, 13, Ui.TEXT_DIM);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private Button action(String label, int color, View.OnClickListener l) {
        Button b = small(label, color, l);
        b.setTextSize(15);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
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
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(6), dp(3), 0);
        return lp;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
