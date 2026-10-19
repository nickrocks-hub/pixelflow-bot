package com.aigame.subwaysurfers;

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

import com.aigame.subwaysurfers.core.SurferBrain;

/**
 * Sends swipes to the game (left, right, up = jump, down = roll) and shows a
 * small floating START/STOP panel over it.
 *
 * Only one gesture is in flight at a time; if a new move arrives meanwhile it
 * replaces any move still waiting, so the bot never lags behind with a queue
 * of stale swipes.
 */
public class SwipeService extends AccessibilityService {

    public static volatile SwipeService instance;

    private static final long SWIPE_MS = 45;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean inFlight = false;
    private SurferBrain.Move pending = null;

    // overlay
    private WindowManager wm;
    private LinearLayout panel;
    private TextView statusView, toggleView;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        // our own overlay and the system UI (notification shade, etc.) don't count as "the app in front"
        if (p.equals(getPackageName()) || p.equals("com.android.systemui")) return;
        BotState.foregroundPackage = p;
    }

    @Override
    public void onInterrupt() { }

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

    // ------------------------------------------------------------------ swipes

    /** Thread-safe: perform one move as a swipe. */
    public void perform(SurferBrain.Move m) {
        if (m == null || m == SurferBrain.Move.NONE) return;
        main.post(() -> {
            if (!BotState.running || !BotState.gameInFront()) return;
            if (inFlight) { pending = m; return; }
            dispatch(m);
        });
    }

    private int[] screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private void dispatch(SurferBrain.Move m) {
        int[] sz = screenSize();
        int w = sz[0], h = sz[1];
        float cx = w * 0.5f;
        float cy = h * BotState.prefs(this).getInt(BotState.K_SWIPE_Y, 60) / 100f;
        float dx = 0, dy = 0;
        float lenX = w * 0.18f, lenY = h * 0.09f;
        switch (m) {
            case LEFT: dx = -lenX; break;
            case RIGHT: dx = lenX; break;
            case JUMP: dy = -lenY; break;
            case ROLL: dy = lenY; break;
            default: return;
        }
        Path p = new Path();
        p.moveTo(cx - dx / 2, cy - dy / 2);
        p.lineTo(cx + dx / 2, cy + dy / 2);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, SWIPE_MS))
                .build();
        inFlight = true;
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) { done(); }
            @Override public void onCancelled(GestureDescription gd) { done(); }
        }, main);
        if (!ok) inFlight = false;
    }

    private void done() {
        inFlight = false;
        SurferBrain.Move next = pending;
        pending = null;
        if (next != null && BotState.running && BotState.gameInFront()) dispatch(next);
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
        statusView.setMaxWidth(dp(180));
        statusView.setSingleLine(false);
        statusView.setText("AI GAME SUBWAY SURFERS");
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
        // Upper-left area below the pause button: the bot ignores the top of the screen.
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(8);
        p.y = dp(110);
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
            b.setColor(on ? 0xFFD64545 : 0xFF2E9E5B);
            toggleView.setBackground(b);
            if (!on && statusView != null) statusView.setText(BotState.captureActive ? "Paused" : "AI GAME SUBWAY SURFERS");
        });
    }

    /** Thread-safe status line. */
    public void postStatus(String s) {
        main.post(() -> { if (statusView != null && BotState.running) statusView.setText(s); });
    }
}
