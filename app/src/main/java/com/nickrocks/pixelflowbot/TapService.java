package com.nickrocks.pixelflowbot;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Accessibility service for AI Game Bot: the floating panel and the taps/swipes for the
 * Nonogram.com solver ({@link NonogramMode}), plus automatic "next level" ({@link AutoNext}).
 *
 * Safety: it only acts after you press Solve, only while the game that was in front at that
 * moment is still in front, and STOP turns everything off (panel, capture, this service).
 */
public class TapService extends AccessibilityService {
    private static final String TAG = "AIGameBot.Tap";
    public static final String PREFS = "bot";
    private static final String GAME = "nonogram";

    private static volatile TapService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private TextView status;
    private View nextRow;
    private Button nextToggle;
    private NonogramMode nono;
    private AutoNext next;
    private boolean stopped;
    private int levelsDone;

    public static boolean isRunning() { return instance != null; }

    /** Full kill switch: callable from the app screen too. */
    public static void stopEverything() {
        TapService s = instance;
        if (s != null) s.handler.post(s::stopAll);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        stopped = false;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showPanel();
        handler.post(statusTick);
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (status != null) {
                status.setText("Capture: " + (CaptureService.isRunning() ? "ON · " + CaptureService.getFrameCount() + " frames" : "off")
                        + "\nApp in front: " + currentPackage() + "   (drag here)");
            }
            if (!stopped) handler.postDelayed(this, 500);
        }
    };

    // ------------------------------------------------------------------ panel

    private void showPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.PANEL_TOP, Ui.PANEL_BOTTOM});
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Ui.PANEL_STROKE);
        panel.setBackground(bg);
        panel.setElevation(dp(8));

        // Header: brand + status, also the drag handle.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("⬢ AI GAME BOT · NONOGRAM");
        brand.setTextColor(Ui.NEON);
        brand.setTextSize(10);
        brand.setTypeface(Ui.BLACK);
        brand.setLetterSpacing(0.18f);
        head.addView(brand);
        status = new TextView(this);
        status.setTextColor(Ui.TEXT);
        status.setTextSize(10.5f);
        status.setTypeface(Ui.MONO);
        status.setPadding(0, dp(2), 0, dp(2));
        head.addView(status);
        panel.addView(head);

        next = new AutoNext(this, this);
        LinearLayout nr = new LinearLayout(this);
        nextToggle = button("", Ui.MUTED, v -> { next.setEnabled(GAME, !next.enabled(GAME)); refreshNext(); });
        nr.addView(nextToggle);
        panel.addView(nr);
        nextRow = nr;
        refreshNext();

        try {
            nono = new NonogramMode(this, this);
            panel.addView(nono.box());
        } catch (Throwable t) {
            Log.e(TAG, "Nonogram mode could not start", t);
            TextView err = new TextView(this);
            err.setTextColor(Ui.DANGER);
            err.setText("Nonogram solver failed to start: " + t);
            panel.addView(err);
        }

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = dp(4);
        panelParams.y = dp(4);

        head.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = panelParams.x; startY = panelParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelParams.x = startX + (int) (e.getRawX() - downX);
                        panelParams.y = startY + (int) (e.getRawY() - downY);
                        if (panel != null) wm.updateViewLayout(panel, panelParams);
                        return true;
                }
                return false;
            }
        });
        wm.addView(panel, panelParams);
    }

    private void refreshNext() {
        if (nextToggle == null) return;
        boolean on = next.enabled(GAME);
        nextToggle.setText(on ? "⟳ Next level: ON" : "⟳ Next level: off");
        Ui.tint(nextToggle, on ? Ui.ACCENT : Ui.MUTED, dp(14));
    }

    // ------------------------------------------------------------------ host API for NonogramMode / AutoNext

    Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTypeface(Ui.MEDIUM);
        b.setBackground(Ui.button(color, dp(14)));
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(4), dp(3), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    View panelView() { return panel; }

    /** Panel bounds on screen {left, top, right, bottom}, or null when hidden. */
    int[] panelRect() {
        if (panel == null || panel.getVisibility() != View.VISIBLE || !panel.isAttachedToWindow()) return null;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        return new int[]{loc[0], loc[1], loc[0] + panel.getWidth(), loc[1] + panel.getHeight()};
    }

    /** Moves the panel so its top edge is at screen y (keeps it off the board). */
    void movePanel(int y) {
        if (panel == null || panelParams == null) return;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        panelParams.y += y - loc[1];
        try { wm.updateViewLayout(panel, panelParams); } catch (Exception ignored) { }
    }

    /** Hides the next-level row while the solver works, so the panel stays small. */
    void setCompact(boolean on) {
        if (nextRow != null) nextRow.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    String foregroundPackage() { return currentPackage(); }

    String gameName(String g) { return "Nonogram"; }

    void cancelNext() { if (next != null) next.cancel(); }

    void say(String msg) {
        refreshNext();
        if (nono != null) nono.note(msg);
        else toast(msg);
    }

    /** The solver finished a puzzle: press Next Level, wait for the new board, solve it. */
    void nonogramSolved() {
        if (nono == null || !next.enabled(GAME)) return;
        levelsDone++;
        nono.parkForNextLevel();
        final com.nickrocks.pixelflowbot.nono.NonoVision v = new com.nickrocks.pixelflowbot.nono.NonoVision();
        next.run(GAME, com.nickrocks.pixelflowbot.nono.NextButton::find,
                f -> {
                    com.nickrocks.pixelflowbot.nono.NonoVision.Reading r = v.readBoard(f);
                    return r.grid != null && r.toggle >= 0;
                },
                90_000, this::say,
                () -> nono.autoStart(),
                why -> {
                    nono.setCompact(false);
                    say("Auto-next stopped: " + why);
                });
    }

    private String currentPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) return root.getPackageName().toString();
        } catch (Exception ignored) { }
        return "?";
    }

    // ------------------------------------------------------------------ stop

    private void stopAll() {
        cancelNext();
        if (nono != null) nono.shutdown();
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        CaptureService.requestFrame(null);
        CaptureService.stop(this);
        removePanel();
        toast("AI Game Bot stopped");
        disableSelf();
    }

    private void removePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        stopped = true;
        if (nono != null) nono.shutdown();
        if (next != null) next.shutdown();
        handler.removeCallbacksAndMessages(null);
        CaptureService.requestFrame(null);
        removePanel();
        instance = null;
    }
}
