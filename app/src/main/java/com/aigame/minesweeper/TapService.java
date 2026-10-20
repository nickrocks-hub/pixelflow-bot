package com.aigame.minesweeper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Taps the tiles (short single-finger taps, sent back to back in one gesture so a burst of safe
 * tiles opens in a fraction of a second) and shows the floating START/STOP panel.
 */
public class TapService extends AccessibilityService {

    public static volatile TapService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout panel;
    private TextView statusView, statsView, toggleView;
    private WindowManager.LayoutParams panelLp;
    private java.util.Set<String> launchers;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (!BotState.prefs(this).getBoolean(BotState.K_OVERLAY_HIDDEN, false)) showOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(getPackageName())) return;                     // our own panel
        if (isHome(p) || (cls != null && isActivity(p, cls.toString()))) BotState.foregroundPackage = p;
    }

    private final java.util.HashMap<String, Boolean> activityCache = new java.util.HashMap<>();

    private boolean isActivity(String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean b = activityCache.get(key);
        if (b == null) {
            try {
                getPackageManager().getActivityInfo(new android.content.ComponentName(pkg, cls), 0);
                b = true;
            } catch (Exception e) {
                b = false;
            }
            activityCache.put(key, b);
        }
        return b;
    }

    public boolean isHome(String pkg) {
        if (launchers == null) {
            java.util.Set<String> s = new java.util.HashSet<>();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
            }
            launchers = s;
        }
        return launchers.contains(pkg);
    }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        removeOverlay();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ taps

    public interface Done { void done(boolean ok, String note); }

    /**
     * Thread-safe: taps every point (screen pixels), one after another, {@code gapMs} apart.
     * Calls back once, on the main thread, when all are delivered or one fails.
     */
    public void tapAll(float[] xy, int gapMs, Done cb) {
        main.post(() -> {
            if (!BotState.running || !BotState.gameInFront(getPackageName())) { cb.done(false, "paused or game not in front"); return; }
            sendBatch(xy, 0, Math.max(15, gapMs), cb);
        });
    }

    private void sendBatch(float[] xy, int from, int gap, Done cb) {
        int n = xy.length / 2;
        if (from >= n) { cb.done(true, ""); return; }
        if (!BotState.running) { cb.done(false, "stopped"); return; }
        int max = 10;
        try { max = Math.max(1, Math.min(20, GestureDescription.getMaxStrokeCount())); } catch (Throwable ignored) { }
        int to = Math.min(n, from + max);
        long down = Math.max(8, Math.min(40, gap / 2));
        GestureDescription.Builder b = new GestureDescription.Builder();
        int[] sz = screenSize();
        for (int i = from; i < to; i++) {
            Path p = new Path();
            float x = clamp(xy[2 * i], 0, sz[0] - 1), y = clamp(xy[2 * i + 1], 0, sz[1] - 1);
            p.moveTo(x, y);
            p.lineTo(x + 0.5f, y);           // a zero-length path is refused on some phones
            b.addStroke(new GestureDescription.StrokeDescription(p, (long) (i - from) * gap, down));
        }
        GestureDescription g;
        try { g = b.build(); } catch (RuntimeException e) { cb.done(false, "gesture refused: " + e.getMessage()); return; }
        final boolean[] finished = {false};
        final int next = to;
        long expect = (long) (to - from) * gap + 1500;
        boolean ok;
        try {
            ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) {
                    if (finished[0]) return;
                    finished[0] = true;
                    main.postDelayed(() -> sendBatch(xy, next, gap, cb), gap);
                }
                @Override public void onCancelled(GestureDescription d) {
                    if (finished[0]) return;
                    finished[0] = true;
                    cb.done(false, "cancelled by the system (screen touched?)");
                }
            }, main);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) { finished[0] = true; cb.done(false, "refused by Android"); return; }
        main.postDelayed(() -> {
            if (finished[0]) return;
            finished[0] = true;
            cb.done(false, "no result from Android");
        }, expect);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    public int[] screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    // ---------------------------------------------------------------- overlay

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    public void setOverlayVisible(boolean visible) {
        BotState.prefs(this).edit().putBoolean(BotState.K_OVERLAY_HIDDEN, !visible).apply();
        main.post(() -> { if (visible) showOverlay(); else removeOverlay(); });
    }

    private void showOverlay() {
        if (panel != null) return;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE010121A);
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);
        panel.setPadding(dp(6), dp(6), dp(10), dp(6));

        toggleView = new TextView(this);
        toggleView.setTextColor(Color.WHITE);
        toggleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        toggleView.setPadding(dp(14), dp(8), dp(14), dp(8));
        toggleView.setOnClickListener(v -> toggle());
        panel.addView(toggleView);

        TextView grip = new TextView(this);
        grip.setText("✥");
        grip.setTextColor(0xFFB8BCC8);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        grip.setPadding(dp(10), 0, dp(6), 0);
        panel.addView(grip);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        statusView = new TextView(this);
        statusView.setTextColor(0xFFE6E8EF);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusView.setMaxLines(2);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setText("AI GAME MINESWEEPER");
        texts.addView(statusView);
        statsView = new TextView(this);
        statsView.setTextColor(0xFF9BA1B3);
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        statsView.setMaxLines(1);
        texts.addView(statsView);
        panel.addView(texts, new LinearLayout.LayoutParams(dp(190), LinearLayout.LayoutParams.WRAP_CONTENT));
        refreshToggle();
        showStats(savedStats());

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        int[] sz = screenSize();
        // default: low on the screen, where the board usually isn't
        p.x = BotState.prefs(this).getInt(BotState.K_PANEL_X, dp(12));
        p.y = BotState.prefs(this).getInt(BotState.K_PANEL_Y, (int) (sz[1] * 0.80f));
        p.x = Math.max(0, Math.min(sz[0] - dp(280), p.x));
        p.y = Math.max(0, Math.min(sz[1] - dp(70), p.y));
        panelLp = p;
        View.OnTouchListener drag = new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = panelLp.x; oy = panelLp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int nx = ox + Math.round(e.getRawX() - sx), ny = oy + Math.round(e.getRawY() - sy);
                        if (Math.abs(nx - ox) + Math.abs(ny - oy) > dp(4)) moved = true;
                        int[] s2 = screenSize();
                        panelLp.x = Math.max(0, Math.min(s2[0] - panel.getWidth(), nx));
                        panelLp.y = Math.max(0, Math.min(s2[1] - panel.getHeight(), ny));
                        try { wm.updateViewLayout(panel, panelLp); } catch (RuntimeException ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) savePanelPos();
                        return true;
                }
                return false;
            }
        };
        grip.setOnTouchListener(drag);
        texts.setOnTouchListener(drag);
        try {
            wm.addView(panel, p);
        } catch (RuntimeException e) {
            panel = null;
        }
    }

    private void savePanelPos() {
        BotState.prefs(this).edit().putInt(BotState.K_PANEL_X, panelLp.x).putInt(BotState.K_PANEL_Y, panelLp.y).apply();
    }

    /**
     * Thread-safe: if the panel sits on the board (screen pixels), move it just below the board,
     * or above it if there's no room below. Its pixels would hide tiles, and taps there would hit it.
     */
    public void keepOffBoard(int left, int top, int right, int bottom) {
        main.post(() -> {
            if (panel == null || panelLp == null) return;
            int ph = panel.getHeight() > 0 ? panel.getHeight() : dp(56);
            int pw = panel.getWidth() > 0 ? panel.getWidth() : dp(280);
            int y = panelLp.y, x = panelLp.x;
            boolean overlaps = x < right && x + pw > left && y < bottom && y + ph > top;
            if (!overlaps) return;
            int[] sz = screenSize();
            if (bottom + dp(8) + ph < sz[1]) panelLp.y = bottom + dp(8);
            else if (top - dp(8) - ph > 0) panelLp.y = top - dp(8) - ph;
            else return;   // board fills the screen: leave it, the reader will say tiles are covered
            try { wm.updateViewLayout(panel, panelLp); } catch (RuntimeException ignored) { }
            savePanelPos();
        });
    }

    private void removeOverlay() {
        if (panel != null && wm != null) {
            try { wm.removeView(panel); } catch (RuntimeException ignored) { }
        }
        panel = null;
    }

    private void toggle() {
        if (BotState.running) {
            BotState.running = false;
        } else if (BotState.captureActive) {
            BotState.resetRequested = true;
            BotState.running = true;
        } else {
            Intent i = new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_START_BOT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
        refreshToggle();
    }

    private long flashUntil;

    public void refreshToggle() {
        main.post(() -> {
            if (toggleView == null) return;
            boolean on = BotState.running;
            toggleView.setText(on ? "STOP" : "START");
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(dp(10));
            b.setColor(on ? 0xFFD64545 : 0xFF2E9E5B);
            toggleView.setBackground(b);
            if (!on && statusView != null && SystemClock.uptimeMillis() >= flashUntil)
                statusView.setText(BotState.captureActive ? "Paused — open a game, press START" : "AI GAME MINESWEEPER");
        });
    }

    /** Thread-safe message that stays up for a while, even when paused. */
    public void flash(String s) {
        main.post(() -> {
            flashUntil = SystemClock.uptimeMillis() + 10000;
            if (statusView != null) statusView.setText(s);
        });
    }

    /** Thread-safe status line (while running). */
    public void postStatus(String s) {
        main.post(() -> {
            if (SystemClock.uptimeMillis() < flashUntil) return;
            if (statusView != null && BotState.running) statusView.setText(s);
        });
    }

    public void showStats(String s) {
        main.post(() -> { if (statsView != null) statsView.setText(s); });
    }

    String savedStats() {
        android.content.SharedPreferences p = BotState.prefs(this);
        int w = p.getInt(BotState.K_WINS, 0), l = p.getInt(BotState.K_LOSSES, 0);
        long best = p.getLong(BotState.K_BEST, -1);
        String s = w + " won · " + l + " lost";
        if (best >= 0) s += String.format(java.util.Locale.US, " · best %.2f s", best / 1000.0);
        return s;
    }
}
