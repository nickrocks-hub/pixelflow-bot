package com.aigame.subwaysurfers;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Setup screen: permissions, settings, and Start (which opens Subway Surfers). */
public class MainActivity extends Activity {

    public static final String ACTION_START_BOT = "com.aigame.subwaysurfers.START_BOT";
    private static final int REQ_CAPTURE = 41;
    private static final int REQ_NOTIF = 42;

    private TextView accessStatus, learnView;
    private Button accessButton;
    private boolean startAfterPermission, askedStorage, askedNotif;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        if (ACTION_START_BOT.equals(getIntent().getAction())) requestStart();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (ACTION_START_BOT.equals(intent.getAction())) requestStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccess();
        refreshLearn();
    }

    private void refreshLearn() {
        if (learnView == null) return;
        SharedPreferences prefs = BotState.prefs(this);
        String st = prefs.getString(BotState.K_LEARN_STATE, null);
        if (st == null) { learnView.setText("Nothing learned yet — it starts learning from the first crash."); return; }
        com.aigame.subwaysurfers.core.SurferBrain.Params base = new com.aigame.subwaysurfers.core.SurferBrain.Params();
        BotState.applyTo(prefs, base);
        learnView.setText(com.aigame.subwaysurfers.core.Learner.load(st, base).summary());
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        SharedPreferences prefs = BotState.prefs(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AI GAME SUBWAY SURFERS");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(body("Plays Subway Surfers for you. It watches the three tracks, spots trains and "
                + "barriers coming down your lane, and swipes to dodge, jump or roll. "
                + "Everything runs on your phone — no internet."));

        // step 1
        root.addView(header("1. Allow swiping"));
        accessStatus = body("");
        root.addView(accessStatus);
        accessButton = button("Open Accessibility settings", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessButton);

        // step 2
        root.addView(header("2. Start"));
        root.addView(body("You'll be asked to share your screen — choose the entire screen. "
                + "Subway Surfers then opens with a floating START/STOP button. Start a run in the game "
                + "(the bot doesn't press menu buttons), and the bot takes over the swiping."));
        root.addView(button("Start bot and open Subway Surfers", v -> requestStart()));
        root.addView(button("Stop bot", v -> {
            BotState.running = false;
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        }));
        root.addView(button("Stop bot and hide the floating button", v -> {
            BotState.running = false;
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            SwipeService svc = SwipeService.instance;
            if (svc != null) svc.setOverlayVisible(false);
            else BotState.prefs(this).edit().putBoolean(BotState.K_OVERLAY_HIDDEN, true).apply();
            Toast.makeText(this, "Bot stopped. The floating button comes back when you press Start.", Toast.LENGTH_LONG).show();
        }));

        // learning
        root.addView(header("Learning"));
        root.addView(toggle(prefs, BotState.K_LEARN, "Get better after every crash", true));
        root.addView(hint("After each crash the bot works out what went wrong (changed lane into danger, jumped too "
                + "early or late, got boxed in, hesitated, didn't react) and adjusts itself. If changes make it "
                + "worse, it goes back to the settings of its best run. The sliders below are where it starts from."));
        learnView = body("");
        root.addView(learnView);
        root.addView(button("Reset learning", v -> {
            prefs.edit().remove(BotState.K_LEARN_STATE).apply();
            BotState.learnerResetRequested = true;
            refreshLearn();
            Toast.makeText(this, "Learning reset.", Toast.LENGTH_SHORT).show();
        }));

        // settings
        root.addView(header("Tuning"));
        root.addView(toggle(prefs, BotState.K_COINS, "Go for coins and power-ups", true));
        root.addView(toggle(prefs, BotState.K_JUMP_LOW, "Jump over barriers (off = roll under)", true));
        root.addView(toggle(prefs, BotState.K_MIDDLE, "Move back to the middle lane when it's clear", true));
        root.addView(slider(prefs, BotState.K_REACT, "Reaction distance", 300, 1300, 650, " ms",
                "How early it dodges. Raise if it swipes too late; lower if it dodges too early and "
                        + "runs into the next obstacle."));
        root.addView(slider(prefs, BotState.K_LATENCY, "Phone delay", 0, 400, 120, " ms",
                "Time between the screen changing and the swipe landing on your phone. Raise on slower phones."));
        root.addView(slider(prefs, BotState.K_SAFE, "Safety margin for lane changes", 100, 900, 350, " ms",
                "A side lane must be clear this much longer than yours before it moves there."));
        root.addView(slider(prefs, BotState.K_LANE_CD, "Pause between lane changes", 120, 500, 230, " ms",
                "Raise if it swipes twice and overshoots a lane."));
        root.addView(slider(prefs, BotState.K_OFFSET, "Runner position (left/right)", -20, 20, 0, " %",
                "Where your runner usually sits across the screen, from the centre. Leave at 0 unless the "
                        + "bot keeps thinking you're in the wrong lane."));
        root.addView(slider(prefs, BotState.K_SWIPE_Y, "Swipe height on screen", 35, 80, 60, " %",
                "Where on the screen the swipes start."));

        root.addView(toggle(prefs, BotState.K_ONLY_GAME, "Only swipe while Subway Surfers is open", true));
        root.addView(hint("Turn off if the panel keeps saying \"Waiting for Subway Surfers\" while you're playing."));
        root.addView(toggle(prefs, BotState.K_LOGS, "Save a log to Downloads after each run", true));
        root.addView(hint("Saved to Download/" + SessionLog.FOLDER + " as a .zip when a run ends and when you press STOP. "
                + "It holds what the bot saw and did plus the last few seconds of the screen — upload it to Claude to tune the bot."));

        root.addView(header("Good to know"));
        root.addView(body("• Drag the floating panel by its ✥ handle to move it anywhere.\n"
                + "• Barriers in your lane: it jumps (or rolls, if you switch that). Trains: it moves to a "
                + "side lane only if that lane is clear well ahead and there's no train right beside you.\n"
                + "• When your lane is clear it moves over to lines of coins and power-ups if that lane is safe.\n"
                + "• Standing on train roofs and flying with the jetpack, it stays still.\n"
                + "• It only swipes while Subway Surfers is the app in front, and it never presses buttons, "
                + "ads or purchases. Revive, hoverboards and menus are up to you."));

        setContentView(scroll);
    }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(24), 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView hint(String s) {
        TextView h = body(s);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        h.setAlpha(0.7f);
        return h;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        btn.setLayoutParams(lp);
        return btn;
    }

    private View toggle(SharedPreferences prefs, String key, String label, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((CompoundButton c, boolean on) -> prefs.edit().putBoolean(key, on).apply());
        sw.setPadding(0, dp(8), 0, dp(8));
        return sw;
    }

    private View slider(SharedPreferences prefs, String key, String label, int min, int max, int def,
                        String unit, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(4));
        TextView t = body("");
        box.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        int cur = prefs.getInt(key, def);
        sb.setProgress(cur - min);
        t.setText(label + ": " + cur + unit);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                t.setText(label + ": " + (p + min) + unit);
                if (user) prefs.edit().putInt(key, p + min).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb);
        TextView h = body(hint);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        h.setAlpha(0.7f);
        box.addView(h);
        return box;
    }

    private boolean accessibilityOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, SwipeService.class);
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(enabled);
        for (String s : sp) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    private void refreshAccess() {
        if (accessStatus == null) return;
        boolean on = accessibilityOn();
        accessStatus.setText(on
                ? "✓ The bot can swipe."
                : "Turn on \"AI GAME SUBWAY SURFERS\" under Accessibility → Installed apps (or Downloaded apps). "
                  + "If Android says it's a restricted setting: App info → ⋮ → Allow restricted settings, then try again.");
        accessButton.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    // --------------------------------------------------------------- start

    private void requestStart() {
        SwipeService shown = SwipeService.instance;
        if (shown != null) shown.setOverlayVisible(true);
        else BotState.prefs(this).edit().putBoolean(BotState.K_OVERLAY_HIDDEN, false).apply();
        if (!accessibilityOn()) {
            Toast.makeText(this, "Turn on the AI GAME SUBWAY SURFERS accessibility service first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true;
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && BotState.prefs(this).getBoolean(BotState.K_LOGS, true)
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                && !askedStorage) {
            askedStorage = true;
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_NOTIF);
            return;
        }
        if (BotState.captureActive) {
            BotState.resetRequested = true;
            BotState.running = true;
            SwipeService svc = SwipeService.instance;
            if (svc != null) svc.refreshToggle();
            openGame();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_NOTIF && startAfterPermission) {
            startAfterPermission = false;
            // notifications / storage are optional for us; continue either way
            requestStart();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen sharing is needed so the bot can see the tracks.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_CODE, result)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        openGame();
    }

    private void openGame() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(BotState.GAME_PACKAGE);
        if (launch == null) {
            Toast.makeText(this, "Subway Surfers isn't installed (package " + BotState.GAME_PACKAGE
                    + "). Open the game yourself — the bot is running.", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }
}
