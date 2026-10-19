package com.riderbot.app;

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
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Presses and releases the screen for the game, and shows a small floating
 * Start/Stop panel over it.
 *
 * Holding uses "continued" gesture strokes (API 26+): a stroke dispatched with
 * willContinue=true leaves the finger down when it completes, and a final
 * continuation with willContinue=false lifts it. Only one gesture is in flight
 * at a time; requests that arrive meanwhile are applied when it finishes.
 */
public class BotAccessibilityService extends AccessibilityService {

    public static volatile BotAccessibilityService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private static final long STROKE_MS = 16;

    // gesture state (main thread only)
    private boolean desired = false;     // what the brain wants
    private boolean fingerDown = false;  // what we have told the system
    private boolean inFlight = false;
    private GestureDescription.StrokeDescription stroke;
    private float touchX, touchY;
    private boolean atAlt = false;       // alternate between two adjacent points

    // overlay
    private WindowManager wm;
    private LinearLayout panel;
    private TextView statusView, toggleView;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        computeTouchPoint();
        showOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() {
        setHolding(false);
    }

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

    // ------------------------------------------------------------------ touch

    /** Thread-safe: request the finger to be down (true) or up (false). */
    public void setHolding(boolean hold) {
        main.post(() -> {
            if (desired == hold) return;
            desired = hold;
            pump();
        });
    }

    private void computeTouchPoint() {
        int w, h;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            w = b.width(); h = b.height();
        } else {
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
            w = m.widthPixels; h = m.heightPixels;
        }
        int pct = BotState.prefs(this).getInt(BotState.K_TOUCH_Y, 75);
        touchX = w * 0.5f;
        touchY = h * pct / 100f;
    }

    private Path segment() {
        // A 1-px move so the path is never empty; the next stroke starts where this one ends.
        float from = atAlt ? touchX + 1 : touchX;
        float to = atAlt ? touchX : touchX + 1;
        atAlt = !atAlt;
        Path p = new Path();
        p.moveTo(from, touchY);
        p.lineTo(to, touchY);
        return p;
    }

    private void pump() {
        if (inFlight) return;
        if (desired == fingerDown) return;
        try {
            if (desired) {
                computeTouchPoint();
                atAlt = false;
                stroke = new GestureDescription.StrokeDescription(segment(), 0, STROKE_MS, true);
            } else {
                if (stroke == null) { fingerDown = false; return; }
                stroke = stroke.continueStroke(segment(), 0, STROKE_MS, false);
            }
        } catch (RuntimeException e) {
            stroke = null; fingerDown = false;
            return;
        }
        final boolean goingDown = desired;
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        inFlight = true;
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) {
                inFlight = false;
                fingerDown = goingDown;
                if (!goingDown) stroke = null;
                pump();
            }
            @Override public void onCancelled(GestureDescription gd) {
                // e.g. the user touched the screen: the finger is up now
                inFlight = false;
                fingerDown = false;
                stroke = null;
                pump();
            }
        }, main);
        if (!ok) { inFlight = false; fingerDown = false; stroke = null; }
    }

    // ---------------------------------------------------------------- overlay

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void showOverlay() {
        if (panel != null) return;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC10121A);
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);
        panel.setPadding(dp(10), dp(6), dp(6), dp(6));

        statusView = new TextView(this);
        statusView.setTextColor(0xFFB8BCC8);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setMaxWidth(dp(190));
        statusView.setSingleLine(false);
        statusView.setText("Rider Bot");
        panel.addView(statusView);

        toggleView = new TextView(this);
        toggleView.setTextColor(Color.WHITE);
        toggleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toggleView.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        panel.addView(toggleView, lp);
        toggleView.setOnClickListener(v -> toggle());
        refreshToggle();

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        // Top band of the screen: the bot ignores this area when it looks for the car.
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = dp(8);
        p.y = dp(34);
        try {
            wm.addView(panel, p);
        } catch (RuntimeException e) {
            panel = null;
        }
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
            setHolding(false);
        } else if (BotState.captureActive) {
            BotState.resetRequested = true;
            BotState.running = true;
        } else {
            // Screen-capture consent has to come from an activity.
            Intent i = new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_START_BOT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
        refreshToggle();
    }

    public void refreshToggle() {
        main.post(() -> {
            if (toggleView == null) return;
            boolean on = BotState.running;
            toggleView.setText(on ? "STOP" : "START");
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(dp(10));
            b.setColor(on ? 0xFFD64545 : 0xFF3A6FF0);
            toggleView.setBackground(b);
            if (!on && statusView != null) statusView.setText(BotState.captureActive ? "Paused" : "Rider Bot");
        });
    }

    /** Thread-safe status line. */
    public void postStatus(String s) {
        main.post(() -> { if (statusView != null && BotState.running) statusView.setText(s); });
    }
}
