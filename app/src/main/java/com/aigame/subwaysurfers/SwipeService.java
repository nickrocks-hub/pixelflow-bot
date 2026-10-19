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

    // overlay
    private WindowManager wm;
    private LinearLayout panel;
    private TextView statusView, toggleView;
    private WindowManager.LayoutParams panelLp;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (!BotState.prefs(this).getBoolean(BotState.K_OVERLAY_HIDDEN, false)) showOverlay();
    }

    /** Thread-safe: show or hide the floating panel (remembered across restarts). */
    public void setOverlayVisible(boolean visible) {
        BotState.prefs(this).edit().putBoolean(BotState.K_OVERLAY_HIDDEN, !visible).apply();
        main.post(() -> { if (visible) showOverlay(); else removeOverlay(); });
    }

    private final java.util.HashMap<String, Boolean> activityCache = new java.util.HashMap<>();
    private java.util.Set<String> launchers;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(getPackageName())) return;                   // our own panel / toasts
        // Only a real full app screen changes "the app in front". Pop-ups, game overlays,
        // keyboards and toasts from other apps fire the same event but are not activities.
        if (isHome(p) || (cls != null && isActivity(p, cls.toString()))) {
            BotState.foregroundPackage = p;
        }
    }

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

    private boolean isHome(String pkg) {
        if (launchers == null) {
            launchers = new java.util.HashSet<>();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) launchers.add(ri.activityInfo.packageName);
            }
        }
        return launchers.contains(pkg);
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

    // ---------------------------------------------------------------- swipes
    //
    // The single owner of all game input. Moves are queued (at most 2, oldest first,
    // never silently replaced), sent one at a time with a minimum gap so the game sees
    // distinct swipes, and every move gets exactly one result reported back to the brain:
    // delivered (with timings), cancelled by the system, refused, or dropped as stale.

    /** Receives the result of every requested move. */
    public interface GestureListener { void onGesture(SurferBrain.GestureAck ack); }

    public static volatile GestureListener listener;

    private static final long MIN_GAP_MS = 90;       // between two strokes
    private static final long STALE_MS = 250;        // a move waiting longer than this is dropped
    private static final long TIMEOUT_MS = 600;      // no callback at all: report and move on

    private static final class Req { final SurferBrain.Move m; final long requested; Req(SurferBrain.Move m, long t) { this.m = m; requested = t; } }

    private final java.util.ArrayDeque<Req> queue = new java.util.ArrayDeque<>();
    private Req current;
    private long currentDispatched, lastDone;
    private int seq;

    private void report(Req r, long dispatched, long done, boolean ok, String note) {
        GestureListener l = listener;
        if (l != null) l.onGesture(new SurferBrain.GestureAck(r.m, r.requested, dispatched, done, ok, note));
    }

    /** Thread-safe: request one move. */
    public void perform(SurferBrain.Move m) {
        if (m == null || m == SurferBrain.Move.NONE) return;
        final Req r = new Req(m, SystemClock.uptimeMillis());
        main.post(() -> {
            if (!BotState.running || !BotState.gameInFront()) {
                report(r, 0, SystemClock.uptimeMillis(), false, "not sent: bot paused or game not in front");
                return;
            }
            if (queue.size() >= 2) {
                Req old = queue.pollFirst();
                report(old, 0, SystemClock.uptimeMillis(), false, "dropped: queue full");
            }
            queue.addLast(r);
            pump();
        });
    }

    private void pump() {
        if (current != null) return;
        long now = SystemClock.uptimeMillis();
        Req r;
        while ((r = queue.peekFirst()) != null && now - r.requested > STALE_MS) {
            queue.pollFirst();
            report(r, 0, now, false, "dropped: waited " + (now - r.requested) + " ms (stale)");
        }
        if (r == null) return;
        long wait = lastDone + MIN_GAP_MS - now;
        if (wait > 0) { main.postDelayed(this::pump, wait); return; }
        queue.pollFirst();
        dispatch(r);
    }

    private int[] screenSize() {
        // Gestures use real screen coordinates (whole display, including system bars),
        // the same space the game is drawn in when full screen.
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private void dispatch(Req r) {
        int[] sz = screenSize();
        int w = sz[0], h = sz[1];
        // Straight, single-axis strokes through the middle of the play area (away from the
        // HUD at the top and the power-up bar at the bottom), sized relative to the screen.
        float cx = w * 0.5f;
        float cy = h * BotState.prefs(this).getInt(BotState.K_SWIPE_Y, 60) / 100f;
        float dx = 0, dy = 0;
        float lenX = Math.min(w, h) * 0.20f, lenY = Math.min(w, h) * 0.20f;
        switch (r.m) {
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
        current = r;
        currentDispatched = SystemClock.uptimeMillis();
        final int mySeq = ++seq;
        boolean ok;
        try {
            ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gd) { finish(mySeq, true, ""); }
                @Override public void onCancelled(GestureDescription gd) { finish(mySeq, false, "cancelled by the system (screen touched?)"); }
            }, main);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) { finish(mySeq, false, "refused by Android"); return; }
        main.postDelayed(() -> finish(mySeq, false, "no result within " + TIMEOUT_MS + " ms"), TIMEOUT_MS);
    }

    private void finish(int s, boolean ok, String note) {
        if (current == null || s != seq) return;              // already finished (callback vs timeout)
        Req r = current;
        current = null;
        lastDone = SystemClock.uptimeMillis();
        report(r, currentDispatched, lastDone, ok, note);
        pump();
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
        panel.setPadding(dp(6), dp(6), dp(10), dp(6));

        // START/STOP first, so it can never be pushed off the screen edge
        toggleView = new TextView(this);
        toggleView.setTextColor(Color.WHITE);
        toggleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toggleView.setPadding(dp(12), dp(6), dp(12), dp(6));
        panel.addView(toggleView);
        toggleView.setOnClickListener(v -> toggle());
        refreshToggle();

        // drag handle: hold and move to put the panel anywhere
        TextView grip = new TextView(this);
        grip.setText("✥");
        grip.setTextColor(0xFFB8BCC8);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        grip.setPadding(dp(10), 0, dp(6), 0);
        panel.addView(grip);

        // fixed width so the panel doesn't grow and shrink with the text
        statusView = new TextView(this);
        statusView.setTextColor(0xFFB8BCC8);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusView.setMaxLines(3);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setText("AI GAME SUBWAY SURFERS");
        panel.addView(statusView, new LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        // Default: upper-left, below the pause button. Drag it anywhere; the spot is remembered.
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = BotState.prefs(this).getInt(BotState.K_PANEL_X, dp(8));
        p.y = BotState.prefs(this).getInt(BotState.K_PANEL_Y, dp(110));
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
                        int[] sz = screenSize();
                        panelLp.x = Math.max(0, Math.min(sz[0] - panel.getWidth(), nx));
                        panelLp.y = Math.max(0, Math.min(sz[1] - panel.getHeight(), ny));
                        try { wm.updateViewLayout(panel, panelLp); } catch (RuntimeException ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) BotState.prefs(SwipeService.this).edit()
                                .putInt(BotState.K_PANEL_X, panelLp.x).putInt(BotState.K_PANEL_Y, panelLp.y).apply();
                        return true;
                }
                return false;
            }
        };
        grip.setOnTouchListener(drag);
        statusView.setOnTouchListener(drag);
        int[] sz = screenSize();
        p.x = Math.max(0, Math.min(sz[0] - dp(260), p.x));
        p.y = Math.max(0, Math.min(sz[1] - dp(60), p.y));
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

    /** Thread-safe short message on the panel, shown even while paused. */
    public void flash(String s) {
        main.post(() -> {
            flashUntil = android.os.SystemClock.uptimeMillis() + 5000;   // keep it readable for a while
            if (statusView != null) statusView.setText(s);
        });
    }

    private long flashUntil;

    /** Thread-safe status line. */
    public void postStatus(String s) {
        main.post(() -> {
            if (android.os.SystemClock.uptimeMillis() < flashUntil) return;
            if (statusView != null && BotState.running) statusView.setText(s);
        });
    }
}
