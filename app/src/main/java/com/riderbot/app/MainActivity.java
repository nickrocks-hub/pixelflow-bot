package com.riderbot.app;

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

/** Setup screen: permissions, settings, and Start (which opens Rider). */
public class MainActivity extends Activity {

    public static final String ACTION_START_BOT = "com.riderbot.app.START_BOT";
    private static final int REQ_CAPTURE = 41;
    private static final int REQ_NOTIF = 42;

    private TextView accessStatus;
    private Button accessButton;
    private boolean startAfterPermission;

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
        title.setText("Rider Bot");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(body("Plays Rider for you: holds on the ground to accelerate, backflips in the air "
                + "when there is time, and lets go so the car lands upright."));

        // step 1
        root.addView(header("1. Allow touch control"));
        accessStatus = body("");
        root.addView(accessStatus);
        accessButton = button("Open Accessibility settings", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessButton);

        // step 2
        root.addView(header("2. Start"));
        root.addView(body("You'll be asked to share your screen — choose the entire screen. "
                + "Rider then opens with a floating START/STOP button at the top right."));
        root.addView(button("Start bot and open Rider", v -> requestStart()));
        root.addView(button("Stop bot", v -> {
            BotState.running = false;
            startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP));
        }));

        // settings
        root.addView(header("Tuning"));
        Switch flips = new Switch(this);
        flips.setText("Do backflips for points");
        flips.setChecked(prefs.getBoolean(BotState.K_FLIPS, true));
        flips.setOnCheckedChangeListener((CompoundButton c, boolean on) ->
                prefs.edit().putBoolean(BotState.K_FLIPS, on).apply());
        flips.setPadding(0, dp(8), 0, dp(8));
        root.addView(flips);

        root.addView(slider(prefs, BotState.K_MAXFLIPS, "Max flips per jump", 0, 5, 0, "",
                "0 = as many as fit"));
        root.addView(slider(prefs, BotState.K_LATENCY, "Reaction latency", 20, 250, 90, " ms",
                "Raise if the car keeps over-rotating past upright; lower if it lets go too early."));
        root.addView(slider(prefs, BotState.K_MARGIN, "Safety margin before landing", 0, 500, 160, " ms",
                "Raise if it crashes by starting flips it can't finish."));
        root.addView(slider(prefs, BotState.K_TOL, "Upright tolerance", 10, 45, 28, "°",
                "How tilted a landing still counts as safe."));
        root.addView(slider(prefs, BotState.K_TOUCH_Y, "Touch position (height)", 40, 90, 75, "%",
                "Where on the screen the bot presses."));

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
        ComponentName me = new ComponentName(this, BotAccessibilityService.class);
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
                ? "✓ Rider Bot can touch the screen."
                : "Turn on \"Rider Bot\" under Accessibility → Installed apps (or Downloaded apps). "
                  + "If Android says it's a restricted setting: App info → ⋮ → Allow restricted settings.");
        accessButton.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    // --------------------------------------------------------------- start

    private void requestStart() {
        if (!accessibilityOn()) {
            Toast.makeText(this, "Turn on the Rider Bot accessibility service first.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            startAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            return;
        }
        if (BotState.captureActive) {
            BotState.resetRequested = true;
            BotState.running = true;
            BotAccessibilityService svc = BotAccessibilityService.instance;
            if (svc != null) svc.refreshToggle();
            openRider();
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
            // the notification is optional for us; continue either way
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen sharing is needed so the bot can see the car.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .putExtra(CaptureService.EXTRA_CODE, result)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        openRider();
    }

    private void openRider() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(BotState.RIDER_PACKAGE);
        if (launch == null) {
            Toast.makeText(this, "Rider isn't installed (package " + BotState.RIDER_PACKAGE
                    + "). Open the game yourself — the bot is running.", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }
}
