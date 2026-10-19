package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** AI MINECRAFT BOT home screen: the two permissions, then open Minecraft. */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIF = 2;
    private static final int GRASS = 0xFF5FA03B, DIRT = 0xFF8B5A2B;

    private TextView tapChip, capChip;
    private Button tapButton, capButton;
    private boolean launchAfterCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        root.addView(hero());

        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(14), 0, dp(4));
        tapChip = chip();
        capChip = chip();
        chips.addView(tapChip);
        chips.addView(capChip);
        root.addView(chips);

        root.addView(section("SET UP ONCE"));
        tapButton = action("Turn on the tap service", Ui.ACCENT, v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(step("1", "Tap service", "Lets the bot touch the screen in Minecraft, only after you press ▶ Start. Find AI MINECRAFT BOT under Accessibility → Installed apps.", tapButton));
        capButton = action("Start screen capture", Ui.INFO, v -> requestCapture(false));
        root.addView(step("2", "Screen capture", "Lets the bot see the game. Choose \"Entire screen\". Needed again after every update or restart.", capButton));

        root.addView(section("PLAY"));
        Button play = action("▶  Open Minecraft", GRASS, v -> launch());
        root.addView(step("3", "In Minecraft", "Survival world, landscape, normal touch controls (not split controls), auto-jump on. Stand near trees with "
                + "empty hands and no menus open. On the floating panel press 🔍 Look to check what it sees "
                + "(it works out joystick or D-pad itself), then ▶ Start.", play));
        root.addView(step("✓", "What it does", "Finds a tree, walks to it and holds to mine about 4 logs, then crafts planks, a crafting table and sticks, "
                + "places the table and makes a wooden pickaxe (plus an axe and sword if there's wood), and takes the pickaxe in hand. "
                + "It stops if it takes damage, sees a screen it doesn't know, or you touch the screen.", null));

        Button stop = action("■  STOP everything", Ui.DANGER, v -> {
            CaptureService.stop(this);
            TapService.stopEverything();
            refresh();
        });
        ((LinearLayout.LayoutParams) stop.getLayoutParams()).topMargin = dp(22);
        root.addView(stop);

        TextView foot = text("Works offline. Logs: Download/AIGameBot · stop screenshots: Pictures/AIGameBot", 12, Ui.TEXT_FAINT);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(16), 0, 0);
        root.addView(foot);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.BG, Ui.BG_2}));
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
    }

    private View hero() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GRASS, 0xFF3E7A25, DIRT});
        bg.setCornerRadius(dp(26));
        h.setBackground(bg);
        h.setElevation(dp(6));
        TextView t = text("AI MINECRAFT BOT", 28, Color.WHITE);
        t.setTypeface(Ui.BLACK);
        h.addView(t);
        TextView v = text("v1.3 · first minutes of Survival · offline", 12, 0xDDFFFFFF);
        v.setTypeface(Ui.MONO);
        h.addView(v);
        TextView sub = text("Plays Minecraft for you from the screen: wood → crafting table → wooden pickaxe.", 14, 0xEEFFFFFF);
        sub.setPadding(0, dp(12), 0, 0);
        h.addView(sub);
        return h;
    }

    private TextView chip() {
        TextView c = text("", 12, Ui.TEXT);
        c.setTypeface(Ui.MEDIUM);
        c.setPadding(dp(12), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(8), 0);
        c.setLayoutParams(lp);
        c.setGravity(Gravity.CENTER);
        return c;
    }

    private void setChip(TextView c, String label, boolean on) {
        c.setText((on ? "●  " : "○  ") + label + (on ? " ON" : " off"));
        c.setTextColor(on ? 0xFF5EEAD4 : 0xFFFDA4AF);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(40));
        d.setColor(on ? 0x2214B8A6 : 0x22F43F5E);
        d.setStroke(dp(1), on ? 0x6614B8A6 : 0x66F43F5E);
        c.setBackground(d);
    }

    private TextView section(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setTypeface(Ui.BLACK);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(22), 0, dp(8));
        return t;
    }

    private View step(String num, String title, String body, Button button) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(Ui.card(dp(18), dp(1), Ui.CARD_STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(num, 14, Color.WHITE);
        badge.setTypeface(Ui.BLACK);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.pill(GRASS, dp(14)));
        row.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView t = text(title, 16, Ui.TEXT);
        t.setTypeface(Ui.MEDIUM);
        t.setPadding(dp(12), 0, 0, 0);
        row.addView(t);
        c.addView(row);
        TextView b = text(body, 13, Ui.TEXT_DIM);
        b.setPadding(0, dp(8), 0, button != null ? dp(10) : 0);
        c.addView(b);
        if (button != null) c.addView(button);
        return c;
    }

    private Button action(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setTypeface(Ui.MEDIUM);
        b.setBackground(Ui.button(color, dp(16)));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean tap = TapService.isRunning(), cap = CaptureService.isRunning();
        setChip(tapChip, "Taps", tap);
        setChip(capChip, "Capture", cap);
        tapButton.setText(tap ? "✓ Tap service is on" : "Turn on the tap service");
        tapButton.setAlpha(tap ? 0.6f : 1f);
        capButton.setText(cap ? "✓ Capturing the screen" : "Start screen capture");
        capButton.setAlpha(cap ? 0.6f : 1f);
    }

    /** Checks both permissions, then opens Minecraft. */
    private void launch() {
        if (!TapService.isRunning()) {
            Toast.makeText(this, "Turn on the tap service first: AI MINECRAFT BOT under Accessibility → Installed apps.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!CaptureService.isRunning()) { requestCapture(true); return; }
        openMinecraft();
    }

    private void openMinecraft() {
        Intent i = getPackageManager().getLaunchIntentForPackage(MinecraftMode.MINECRAFT_PKG);
        if (i == null) { Toast.makeText(this, "Minecraft isn't installed on this phone.", Toast.LENGTH_LONG).show(); return; }
        startActivity(i);
    }

    private void requestCapture(boolean thenLaunch) {
        if (CaptureService.isRunning()) {
            if (thenLaunch) openMinecraft(); else Toast.makeText(this, "Capture already running", Toast.LENGTH_SHORT).show();
            return;
        }
        launchAfterCapture = thenLaunch;
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture not allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent svc = new Intent(this, CaptureService.class)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        capChip.postDelayed(this::refresh, 800);
        if (launchAfterCapture) capChip.postDelayed(this::openMinecraft, 900);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
