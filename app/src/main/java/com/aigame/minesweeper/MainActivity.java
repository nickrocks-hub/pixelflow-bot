package com.aigame.minesweeper;

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

/** Setup screen: permission, settings, stats, and Start (then open your Minesweeper game). */
public class MainActivity extends Activity {

    public static final String ACTION_START_BOT = "com.aigame.minesweeper.START_BOT";
    private static final int REQ_CAPTURE = 41;
    private static final int REQ_PERM = 42;

    private TextView accessStatus, statsView;
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
        refreshStats();
    }

    private void refreshStats() {
        if (statsView == null) return;
        SharedPreferences p = BotState.prefs(this);
        int w = p.getInt(BotState.K_WINS, 0), l = p.getInt(BotState.K_LOSSES, 0);
        long best = p.getLong(BotState.K_BEST, -1);
        String s = w + " won, " + l + " lost";
        if (w + l > 0) s += String.format(java.util.Locale.US, " (%.0f%% wins)", 100.0 * w / (w + l));
        if (best >= 0) s += String.format(java.util.Locale.US, "\nFastest win: %.2f s", best / 1000.0);
        statsView.setText(s);
    }

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
        title.setText("AI GAME MINESWEEPER");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(body("Plays classic Minesweeper for you, as fast as it can. It reads the board, works out "
                + "with exact logic which tiles are safe, and taps them all in quick bursts. It only guesses "
                + "when no tile is certain, and then picks the least risky one. Everything runs on your phone — no internet."));

        root.addView(header("1. Allow tapping"));
        accessStatus = body("");
        root.addView(accessStatus);
        accessButton = button("Open Accessibility settings", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessButton);

        root.addView(header("2. Start"));
        root.addView(body("Tap Start and allow screen sharing (choose entire screen). Then open your Minesweeper "
                + "game on a new board — the bot finds the board and starts tapping. Keep the game in dig mode "
                + "(not flag mode)."));
        root.addView(button("Start bot", v -> requestStart()));
        root.addView(button("Stop bot", v -> {
            BotState.running = false;
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        }));
        root.addView(button("Stop bot and hide the floating button", v -> {
            BotState.running = false;
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
            TapService svc = TapService.instance;
            if (svc != null) svc.setOverlayVisible(false);
            else prefs.edit().putBoolean(BotState.K_OVERLAY_HIDDEN, true).apply();
            Toast.makeText(this, "Bot stopped. The floating button comes back when you press Start.", Toast.LENGTH_LONG).show();
        }));

        root.addView(header("Results"));
        statsView = body("");
        root.addView(statsView);
        root.addView(button("Reset results", v -> {
            prefs.edit().remove(BotState.K_WINS).remove(BotState.K_LOSSES).remove(BotState.K_BEST).apply();
            refreshStats();
        }));

        root.addView(header("Settings"));
        root.addView(toggle(prefs, BotState.K_RESTART, "Start a new game after hitting a mine", true));
        root.addView(hint("Taps the smiley and keeps going until it wins."));
        root.addView(toggle(prefs, BotState.K_AGAIN, "Keep playing after a win", false));
        root.addView(hint("Off: it stops on the first win, so you can see the time."));
        root.addView(toggle(prefs, BotState.K_GUESS, "Guess when no tile is certain", true));
        root.addView(hint("Off: it stops and lets you choose. Some boards can't be finished without a guess."));
        root.addView(toggle(prefs, BotState.K_FIRST_CENTRE, "First tap in the centre (off = corner)", false));
        root.addView(hint("A corner opens an area more often. If your game always opens an area on the first tap, the centre opens a bigger one."));
        root.addView(slider(prefs, BotState.K_TAP_GAP, "Time between taps", 20, 150, 45, " ms",
                "Lower is faster. Raise it if the game misses some taps."));
        root.addView(toggle(prefs, BotState.K_LOGS, "Save a log to Downloads after each game", true));
        root.addView(hint("Saved to Download/" + SessionLog.FOLDER + " as a .zip when a game ends, when it gets stuck, and when you press STOP. "
                + "It holds what the bot read and tapped plus the last few seconds of the screen — send it to Claude to fix problems."));

        root.addView(header("Good to know"));
        root.addView(body("• Works with classic-looking Minesweeper: grey raised tiles, coloured numbers, red mine counter and a smiley.\n"
                + "• It never places flags and never taps anything outside the board except the smiley for a new game.\n"
                + "• Before each burst it checks the picture has settled (every tapped tile opened, no half-drawn area), so it never acts on a stale screen.\n"
                + "• Drag the floating panel by its ✥ handle; it moves itself off the board if it's in the way.\n"
                + "• On a 9×9 board with 10 mines the logic wins about 92% of games; the rest are lost to forced guesses no player can avoid."));

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

    private View slider(SharedPreferences prefs, String key, String label, int min, int max, int def, String unit, String hint) {
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
        box.addView(hint(hint));
        return box;
    }

    private boolean accessibilityOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, TapService.class);
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
        accessStatus.setText(on ? "✓ The bot can tap."
                : "Turn on \"AI GAME MINESWEEPER\" under Accessibility → Installed apps (or Downloaded apps). "
                  + "If Android says it's a restricted setting: App info → ⋮ → Allow restricted settings, then try again.");
        accessButton.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void requestStart() {
        TapService shown = TapService.instance;
        if (shown != null) shown.setOverlayVisible(true);
        else BotState.prefs(this).edit().putBoolean(BotState.K_OVERLAY_HIDDEN, false).apply();
        if (!accessibilityOn()) {
            Toast.makeText(this, "Turn on the AI GAME MINESWEEPER accessibility service first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true;
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && BotState.prefs(this).getBoolean(BotState.K_LOGS, true)
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                && !askedStorage) {
            askedStorage = true;
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        if (BotState.captureActive) {
            BotState.resetRequested = true;
            BotState.running = true;
            TapService svc = TapService.instance;
            if (svc != null) svc.refreshToggle();
            goToGame();
            return;
        }
        startActivityForResult(captureIntent(), REQ_CAPTURE);
    }

    /** Android 14+: ask for the entire screen directly, so there's no app list to get stuck in. */
    private Intent captureIntent() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                return mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay());
            } catch (RuntimeException ignored) { }
        }
        return mpm.createScreenCaptureIntent();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PERM && startAfterPermission) {
            startAfterPermission = false;
            requestStart();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen sharing is needed so the bot can see the board.", Toast.LENGTH_LONG).show();
            return;
        }
        startForegroundService(new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_CODE, result)
                .putExtra(CaptureService.EXTRA_DATA, data));
        goToGame();
    }

    /** Opens the Minesweeper game used last time, or leaves for the home screen so you can open it. */
    private void goToGame() {
        String last = BotState.prefs(this).getString(BotState.K_LAST_GAME, "");
        Intent launch = last.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(last);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return;
        }
        Toast.makeText(this, "Now open your Minesweeper game.", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }
}
