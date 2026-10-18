package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1;
    private static final int REQ_NOTIF = 2;

    private TextView tapChip, capChip;
    private LinearLayout libraryBox;
    private Button tapButton, capButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));

        root.addView(hero());

        // Live status chips
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, dp(14), 0, dp(4));
        tapChip = chip();
        capChip = chip();
        chips.addView(tapChip);
        chips.addView(capChip);
        root.addView(chips);

        root.addView(section("GAME LIBRARY"));
        libraryBox = new LinearLayout(this);
        libraryBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(libraryBox);
        root.addView(action("＋  Add a game", Ui.MUTED, v -> AddGame.start(this)));
        root.addView(action("🧠  On-device AI (for games without a player)", Ui.INFO, v -> startActivity(new Intent(this, ModelActivity.class))));

        root.addView(section("PERMISSIONS FOR GAMES ON YOUR SCREEN"));
        tapButton = action("Turn on the tap service", Ui.ACCENT,
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(step("1", "Tap service", "Lets the bot tap and swipe for you, only after you press Solve/Start. Find AI Game Bot under Accessibility → Installed apps.", tapButton));
        capButton = action("Start screen capture", Ui.INFO, v -> requestCapture());
        root.addView(step("2", "Screen capture", "Lets the bot see the game. Choose \"Entire screen\" when asked. Needed again after every app update.", capButton));
        root.addView(step("3", "Play", "Tap a game in the library and press ▶ Launch & play. The floating panel switches to that game. Built-in players: Nonogram.com and Candy Crush Saga. Any other game you add is played by the on-device AI from the instructions you write in its profile (import a model once; it runs on the phone, offline).", null));

        Button stop = action("■  STOP everything", Ui.DANGER, v -> {
            CaptureService.stop(this);
            TapService.stopEverything();
            refresh();
        });
        ((LinearLayout.LayoutParams) stop.getLayoutParams()).topMargin = dp(22);
        root.addView(stop);

        TextView foot = text("Never taps ads, coins, boosters or purchases. ⏸ Pause holds, ■ STOP ends everything.\nLogs: Download/AIGameBot", 12, Ui.TEXT_FAINT);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(16), 0, 0);
        root.addView(foot);

        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.BG, Ui.BG_2}));
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
    }

    // ------------------------------------------------------------------ pieces

    private View hero() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF7C3AED, 0xFF2563EB, 0xFF0891B2});
        bg.setCornerRadius(dp(26));
        h.setBackground(bg);
        h.setElevation(dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(new PixelMark(), new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        TextView t = text("AI GAME BOT", 28, Color.WHITE);
        t.setTypeface(Ui.BLACK);
        titles.addView(t);
        TextView v = text("v1.7 · plays the games you launch · offline", 12, 0xCCFFFFFF);
        v.setTypeface(Ui.MONO);
        titles.addView(v);
        top.addView(titles);
        h.addView(top);

        TextView sub = text("Launch a game from your library and let the bot play it. Nonogram.com and Candy Crush have built-in players; any other game can be played by the on-device AI from your instructions. Nothing goes over the internet.", 14, 0xE6FFFFFF);
        sub.setPadding(0, dp(14), 0, 0);
        h.addView(sub);
        return h;
    }

    /** Little 3x3 nonogram-style logo drawn in code. */
    private final class PixelMark extends View {
        private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG), off = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean[] cells = {true, false, true, true, true, false, false, true, true};
        PixelMark() {
            super(MainActivity.this);
            on.setColor(Color.WHITE);
            off.setColor(0x33FFFFFF);
            bg.setColor(0x33000000);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), pad = w * 0.12f, cell = (w - pad * 2) / 3f, gap = cell * 0.14f;
            c.drawRoundRect(new RectF(0, 0, w, getHeight()), w * 0.26f, w * 0.26f, bg);
            for (int i = 0; i < 9; i++) {
                float x = pad + (i % 3) * cell, y = pad + (i / 3) * cell;
                c.drawRoundRect(new RectF(x + gap, y + gap, x + cell - gap, y + cell - gap), cell * 0.22f, cell * 0.22f, cells[i] ? on : off);
            }
        }
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

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(Ui.card(dp(18), dp(1), Ui.CARD_STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private View step(String num, String title, String body, Button button) {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(num, 14, Color.WHITE);
        badge.setTypeface(Ui.BLACK);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.pill(Ui.ACCENT, dp(14)));
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

    private View game(int color, String name, String tag, String body) {
        LinearLayout c = card();
        c.setBackground(Ui.card(dp(18), dp(1), (color & 0x00FFFFFF) | 0x55000000));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View swatch = new View(this);
        swatch.setBackground(Ui.pill(color, dp(6)));
        row.addView(swatch, new LinearLayout.LayoutParams(dp(14), dp(14)));
        TextView t = text(name, 17, Ui.TEXT);
        t.setTypeface(Ui.BLACK);
        t.setPadding(dp(10), 0, dp(8), 0);
        row.addView(t);
        if (tag != null) {
            TextView badge = text(tag, 10, Color.WHITE);
            badge.setTypeface(Ui.BLACK);
            badge.setLetterSpacing(0.1f);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            badge.setBackground(Ui.pill(color, dp(10)));
            row.addView(badge);
        }
        c.addView(row);
        TextView b = text(body, 13, Ui.TEXT_DIM);
        b.setPadding(0, dp(8), 0, 0);
        c.addView(b);
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

    private EditText numberField(String hint) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setHintTextColor(Ui.TEXT_FAINT);
        f.setTextColor(Ui.TEXT);
        f.setInputType(InputType.TYPE_CLASS_NUMBER);
        f.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF0D1229);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), 0x33FFFFFF);
        f.setBackground(d);
        f.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return f;
    }

    // ------------------------------------------------------------------ behaviour

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        refreshLibrary();
        boolean tap = TapService.isRunning(), cap = CaptureService.isRunning();
        setChip(tapChip, "Taps", tap);
        setChip(capChip, "Capture", cap);
        tapButton.setText(tap ? "✓ Tap service is on" : "Turn on the tap service");
        tapButton.setAlpha(tap ? 0.6f : 1f);
        capButton.setText(cap ? "✓ Capturing the screen" : "Start screen capture");
        capButton.setAlpha(cap ? 0.6f : 1f);
    }

    private void requestCapture() {
        if (CaptureService.isRunning()) {
            Toast.makeText(this, "Capture already running", Toast.LENGTH_SHORT).show();
            return;
        }
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
    }

    /** One card per game profile, with its honest status. */
    private void refreshLibrary() {
        if (libraryBox == null) return;
        libraryBox.removeAllViews();
        for (com.nickrocks.pixelflowbot.core.GameProfile p : AgentPlatform.get(this).profiles.list()) {
            int color = p.support == com.nickrocks.pixelflowbot.core.GameProfile.Support.SUPPORTED ? Ui.GAME_NONO : Ui.GAME_AMAZE;
            String tag = p.kind == com.nickrocks.pixelflowbot.core.GameProfile.Kind.BUILT_IN ? "BUILT-IN"
                    : p.support == com.nickrocks.pixelflowbot.core.GameProfile.Support.SUPPORTED ? "ADAPTER" : "CALIBRATING";
            View card = game(color, p.name, tag, p.statusLine() + (p.notes.isEmpty() ? "" : "\n" + p.notes) + "\nProfile v" + p.version + " · tap for details");
            card.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class).putExtra(ProfileActivity.EXTRA_ID, p.id)));
            libraryBox.addView(card);
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
