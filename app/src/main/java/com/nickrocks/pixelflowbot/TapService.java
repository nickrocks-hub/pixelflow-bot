package com.nickrocks.pixelflowbot;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
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
 * Accessibility service for AI MINECRAFT BOT: the floating panel and the touches for the Minecraft
 * player ({@link MinecraftMode}). It only acts after you press ▶ Start, only while Minecraft is in
 * front, and ■ STOP turns everything off (panel, capture, this service).
 */
public class TapService extends AccessibilityService {
    private static final String TAG = "AIMinecraftBot.Tap";
    public static final String PREFS = "bot";

    private static volatile TapService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private TextView status;
    private MinecraftMode mc;
    private boolean stopped;

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

    private void showPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.PANEL_TOP, Ui.PANEL_BOTTOM});
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Ui.PANEL_STROKE);
        panel.setBackground(bg);
        panel.setElevation(dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("⛏ AI MINECRAFT BOT");
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

        try {
            mc = new MinecraftMode(this);
            panel.addView(mc.box());
        } catch (Throwable t) {
            Log.e(TAG, "Minecraft player could not start", t);
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

    // ------------------------------------------------------------------ host API for MinecraftMode

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

    /** Nothing to shrink in this app's panel; kept for the player's calls. */
    void setCompact(boolean on) { }

    String foregroundPackage() { return currentPackage(); }

    private String currentPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) return root.getPackageName().toString();
        } catch (Exception ignored) { }
        return "?";
    }

    // ------------------------------------------------------------------ stop

    private void stopAll() {
        if (mc != null) mc.shutdown();
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        CaptureService.requestFrame(null);
        CaptureService.stop(this);
        removePanel();
        toast("AI Minecraft Bot stopped");
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
        if (mc != null) mc.shutdown();
        handler.removeCallbacksAndMessages(null);
        CaptureService.requestFrame(null);
        removePanel();
        instance = null;
    }
}
