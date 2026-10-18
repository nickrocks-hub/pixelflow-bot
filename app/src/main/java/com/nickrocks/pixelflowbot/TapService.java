package com.nickrocks.pixelflowbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.pixelflowbot.vision.GameState;
import com.nickrocks.pixelflowbot.vision.Tracker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accessibility service: floating control panel, taps, and live reading of the game.
 * In this version it never taps on its own: only when you press "Test tap".
 */
public class TapService extends AccessibilityService {
    private static final String TAG = "PFBot.Tap";
    public static final String PREFS = "bot";
    public static final String KEY_X = "tapX";
    public static final String KEY_Y = "tapY";

    private static volatile TapService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Tracker tracker = new Tracker();
    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private TextView status, reading;
    private Button liveButton;
    private int tapsSent = 0, tapsDone = 0;
    private boolean stopped = false;
    private boolean live = false;
    private boolean readBusy = false;
    private int readId = 0;
    private String foregroundApp = "?";

    public static boolean isRunning() { return instance != null; }

    /** Full kill switch: callable from the app screen too. */
    public static void stopEverything() {
        TapService s = instance;
        if (s != null) s.stopAll();
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
                status.setText("Capture: " + (CaptureService.isRunning()
                        ? "ON · " + CaptureService.getFrameCount() + " frames" : "off")
                        + "  ·  Taps " + tapsDone + "/" + tapsSent
                        + "\nApp in front: " + foregroundApp + "   (drag here)");
            }
            if (!stopped) handler.postDelayed(this, 500);
        }
    };

    private void showPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6202124);
        bg.setCornerRadius(dp(12));
        panel.setBackground(bg);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(11);
        panel.addView(status);

        reading = new TextView(this);
        reading.setTextColor(0xFFB6E3FF);
        reading.setTextSize(11);
        reading.setText("Press Read to see what the bot sees.");
        reading.setPadding(0, dp(4), 0, 0);
        panel.addView(reading);

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(makeButton("Read", 0xFF1A73E8, v -> readOnce()));
        liveButton = makeButton("Live: off", 0xFF5F6368, v -> toggleLive());
        row1.addView(liveButton);
        row1.addView(makeButton("Save frame", 0xFF188038, v -> saveFrame()));
        panel.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(makeButton("Test tap", 0xFF1A73E8, v -> testTap()));
        row2.addView(makeButton("Recalibrate", 0xFF5F6368, v -> {
            tracker.reset();
            toast("Board will be re-measured on the next read");
        }));
        row2.addView(makeButton("STOP", 0xFFD93025, v -> stopAll()));
        panel.addView(row2);

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // Bottom of the screen: over the booster bar, which the reader never looks at.
        panelParams.gravity = Gravity.BOTTOM | Gravity.START;
        panelParams.x = dp(4);
        panelParams.y = dp(4);

        status.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int startX, startY;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = panelParams.x; startY = panelParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelParams.x = startX + (int) (e.getRawX() - downX);
                        panelParams.y = startY - (int) (e.getRawY() - downY); // gravity BOTTOM: y grows upward
                        if (panel != null) wm.updateViewLayout(panel, panelParams);
                        return true;
                }
                return false;
            }
        });

        wm.addView(panel, panelParams);
    }

    private Button makeButton(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setAllCaps(false);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        b.setBackground(d);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(6), dp(3), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ---------- Reading ----------

    private void toggleLive() {
        live = !live;
        liveButton.setText(live ? "Live: ON" : "Live: off");
        if (live) readOnce();
    }

    /**
     * Hides the panel (so it isn't in the picture), waits for the screen to redraw,
     * grabs one frame, reads it on a background thread, then shows the result.
     */
    private void readOnce() {
        if (stopped || readBusy) return;
        if (!CaptureService.isRunning()) {
            toast("Capture is off. Start it from the app first.");
            if (live) toggleLive();
            return;
        }
        readBusy = true;
        final int id = ++readId;
        panel.setVisibility(View.INVISIBLE);
        final long[] started = {0};
        handler.postDelayed(() -> {
            started[0] = System.currentTimeMillis();
            CaptureService.requestFrame(frame -> worker.execute(() -> {
                String text;
                try {
                    long t0 = System.currentTimeMillis();
                    GameState s = tracker.update(frame);
                    long ms = System.currentTimeMillis() - t0;
                    text = s.summary() + (tracker.isCalibrating() && s.screen == GameState.Screen.GAMEPLAY
                            ? "\n(measuring board: waits for a still board with an empty belt)" : "")
                            + "\n[" + frame.w + "x" + frame.h + ", read in " + ms + " ms]";
                } catch (Exception e) {
                    Log.e(TAG, "Read failed", e);
                    text = "Read error: " + e;
                }
                final String shown = text;
                handler.post(() -> finishRead(id, shown));
            }));
        }, 150);
        // If the screen never changes, no frame arrives: give up after a second.
        handler.postDelayed(() -> {
            if (readBusy && id == readId && started[0] != 0 && System.currentTimeMillis() - started[0] >= 900) {
                CaptureService.requestFrame(null);
                finishRead(id, "No new frame (screen didn't change). Try again.");
            }
        }, 1100);
    }

    private void finishRead(int id, String text) {
        if (id != readId || !readBusy) return;   // a late or duplicate result
        readBusy = false;
        if (panel == null) return;
        panel.setVisibility(View.VISIBLE);
        reading.setText(text);
        if (live && !stopped) handler.postDelayed(this::readOnce, 1000);
    }

    // ---------- Taps ----------

    /** One tap at the saved point (default: screen centre), 400 ms after pressing the button. */
    private void testTap() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final float x = p.getFloat(KEY_X, dm.widthPixels / 2f);
        final float y = p.getFloat(KEY_Y, dm.heightPixels / 2f);

        handler.postDelayed(() -> {
            if (stopped) return;
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                    .build();
            tapsSent++;
            boolean accepted = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { tapsDone++; }
                @Override public void onCancelled(GestureDescription d) {
                    toast("Tap cancelled by system");
                }
            }, handler);
            if (!accepted) toast("Tap rejected (is the service still enabled?)");
        }, 400);
        toast("Tapping at " + (int) x + ", " + (int) y);
    }

    private void saveFrame() {
        if (!CaptureService.isRunning()) {
            toast("Capture is off. Start it from the app first.");
            return;
        }
        CaptureService.requestSaveFrame();
    }

    // ---------- Stop ----------

    /** Stops capture, cancels pending taps and reads, removes the panel and turns this service off. */
    private void stopAll() {
        stopped = true;
        live = false;
        handler.removeCallbacksAndMessages(null);
        CaptureService.requestFrame(null);
        CaptureService.stop(this);
        removePanel();
        toast("PixelFlow Bot stopped");
        disableSelf();
    }

    private void removePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Tracks which app is in front, so later versions only act while Pixel Flow is open. */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(getPackageName()) || p.equals("com.android.systemui")) return;
        foregroundApp = p;
    }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void cleanup() {
        stopped = true;
        live = false;
        handler.removeCallbacksAndMessages(null);
        CaptureService.requestFrame(null);
        removePanel();
        instance = null;
    }
}
