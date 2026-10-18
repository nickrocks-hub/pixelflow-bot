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
        TextView chip = text(p.support == GameProfile.Support.SUPPORTED ? "● Supported" : p.support == GameProfile.Support.CALIBRATING ? "● Calibrating" : "● Not supported",
                14, p.support == GameProfile.Support.SUPPORTED ? 0xFF5EEAD4 : 0xFFFCD34D);
        chip.setTypeface(Ui.MEDIUM);
        st.addView(chip);
        if (ad != null) {
            st.addView(body("Adapter: " + ad.title));
            st.addView(body("Works: " + ad.works));
            st.addView(body("Limits: " + ad.limits));
        } else {
            st.addView(body("Nothing in this app understands this game yet, so the agent won't touch it. "
                    + "What exists for it today: this profile (saved and versioned), your objective and pace below, and an experience store."));
            st.addView(body("Not built yet (later stages): watching the screen to calibrate the board and controls, "
                    + "rule hypotheses with evidence, recording your demonstrations, and a learning or planning backend. "
                    + "Real-time games will also need a check that capture rate and tap latency are fast enough."));
        }
        root.addView(st);
        if ("nonogram-ingame".equals(p.id)) {
            root.addView(action("Open the game", Ui.GAME_NONO, v -> startActivity(new Intent(this, NonogramActivity.class))));
        } else if (p.packageName != null) {
            root.addView(action("Open " + p.name, Ui.INFO, v -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(p.packageName);
                if (i != null) startActivity(i); else toast("That app isn't installed.");
            }));
        }

        // ---- Objective & pace (user-stated; used by later stages, shown so it can be corrected)
        root.addView(section("WHAT YOU SAY THE GAME IS"));
        LinearLayout ob = card();
        String objective = p.settings.get("objective");
        ob.addView(body("Objective (your words): " + (objective == null || objective.isEmpty() ? "not set" : objective)));
        ob.addView(body("Pace: " + paceText(p.pace)));
        LinearLayout row = new LinearLayout(this);
        row.addView(small("Edit objective", Ui.MUTED, v -> editObjective(p)), weight());
        row.addView(small("Change pace", Ui.MUTED, v -> editPace(p)), weight());
        ob.addView(row);
        root.addView(ob);

        // ---- Experience
        root.addView(section("EXPERIENCE ON THIS PHONE"));
        LinearLayout ex = card();
        ExperienceStore.Summary s = pl.experience.summary(p.id);
        ex.addView(body(s.episodes == 0 && s.steps == 0 ? "Nothing recorded yet."
                : String.format(Locale.US, "%d episodes · %d steps · total reward %.0f · %.1f KB · last %s",
                s.episodes, s.steps, s.totalReward, s.bytes / 1024f, s.lastTime == 0 ? "—" : date(s.lastTime))));
        if ("nonogram-ingame".equals(p.id)) {
            ex.addView(body("The built-in game trains in memory (replay buffer) and saves models, not every step — thousands of steps a second "
                    + "would fill the phone. Models and versions are on the game screen."));
        }
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

    private static String paceText(GameProfile.Pace p) {
        switch (p) {
            case TURN_BASED: return "turn-based (waits for you)";
            case REAL_TIME: return "real-time (moves on its own) — needs fast capture and taps";
            default: return "not sure yet";
        }
    }

    private void editObjective(GameProfile p) {
        EditText et = new EditText(this);
        et.setText(p.settings.get("objective") == null ? "" : p.settings.get("objective"));
        et.setHint("e.g. clear all blocks without running out of moves");
        new AlertDialog.Builder(this).setTitle("What is the goal?").setView(et)
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
