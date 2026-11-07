package com.nickrocks.aibot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nickrocks.aibot.core.Action;
import com.nickrocks.aibot.core.Observation;
import com.nickrocks.aibot.core.RunSessionManager;
import com.nickrocks.aibot.core.ScreenLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The bot's hands and its on-screen controls.
 *
 * Hands: the MOVE and SING joysticks are held with continued gesture strokes (one short segment per
 * decision, each continuing the previous one, so the thumb never lifts while a stick is held), and
 * the action buttons are tapped as an extra stroke in the same gesture.
 *
 * Screen: a floating panel (Start/Stop, Pause, status), the optional debug overlay (drawn in pure
 * green, which the screen reader ignores) and the control calibration screen.
 */
public class ControlService extends AccessibilityService {

    public static volatile ControlService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private Set<String> launchers;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showPanel();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(getPackageName()) || p.equals("com.android.systemui")) return;
        BotState.foregroundPackage = p;
    }

    public boolean isHome(String pkg) {
        if (launchers == null) {
            Set<String> s = new HashSet<>();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(home, 0))
                if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
            launchers = s;
        }
        return launchers.contains(pkg);
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
        super.onDestroy();
    }

    private void cleanup() {
        removeView(panel); panel = null;
        removeView(debugView); debugView = null;
        removeView(calib); calib = null;
        instance = null;
    }

    private void removeView(View v) {
        if (v != null && wm != null) try { wm.removeView(v); } catch (RuntimeException ignored) { }
    }

    // =========================================================================== screen size

    public int[] screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            return new int[]{b.width(), b.height()};
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // =========================================================================== hands

    private ScreenLayout layout;
    private int segMs = 66;
    private Action want = Action.idle();
    private GestureDescription.StrokeDescription moveStroke, aimStroke;
    private float[] moveEnd, aimEnd;
    private boolean inFlight;
    private int pendingButton = -1;
    private boolean pendingUlt;
    public volatile String lastGestureProblem = "";
    public volatile int gesturesSent, gesturesCancelled;

    /** Thread-safe: the controls to hold from now on. Buttons are tapped once. */
    public void apply(Action a, ScreenLayout l, int decisionHz) {
        main.post(() -> {
            layout = l;
            segMs = Math.max(40, 1000 / Math.max(5, decisionHz));
            want = a;
            if (a.button >= 0) pendingButton = a.button;
            if (a.ult) pendingUlt = true;
            if (!inFlight) dispatch();
        });
    }

    /** Thread-safe: let go of everything. */
    public void releaseAll() {
        main.post(() -> {
            want = Action.idle();
            pendingButton = -1;
            pendingUlt = false;
            if (!inFlight) dispatch();
        });
    }

    private float[] stickPoint(float cx, float cy, float dx, float dy, int[] sz) {
        float r = layout.stickR * sz[1] * 0.9f;
        float l = (float) Math.hypot(dx, dy);
        if (l > 1) { dx /= l; dy /= l; }
        return new float[]{clamp(cx + dx * r, 1, sz[0] - 2), clamp(cy + dy * r, 1, sz[1] - 2)};
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static Path line(float[] a, float[] b) {
        Path p = new Path();
        p.moveTo(a[0], a[1]);
        // a path with no length is refused on some phones
        if (Math.abs(a[0] - b[0]) < 0.5f && Math.abs(a[1] - b[1]) < 0.5f) p.lineTo(b[0] + 0.6f, b[1]);
        else p.lineTo(b[0], b[1]);
        return p;
    }

    private void dispatch() {
        if (layout == null) return;
        int[] sz = screenSize();
        boolean live = BotState.running() && !BotState.paused;
        Action a = live ? want : Action.idle();
        GestureDescription.Builder b = new GestureDescription.Builder();
        int strokes = 0;
        GestureDescription.StrokeDescription newMove = null, newAim = null;
        // MOVE stick
        float mcx = layout.moveX * sz[0], mcy = layout.moveY * sz[1];
        if (a.moving()) {
            float[] t = stickPoint(mcx, mcy, a.moveX, a.moveY, sz);
            if (moveStroke == null) newMove = new GestureDescription.StrokeDescription(line(new float[]{mcx, mcy}, t), 0, segMs, true);
            else newMove = moveStroke.continueStroke(line(moveEnd, t), 0, segMs, true);
            b.addStroke(newMove); strokes++;
            moveEnd = t;
        } else if (moveStroke != null) {
            b.addStroke(moveStroke.continueStroke(line(moveEnd, moveEnd), 0, 20, false)); strokes++;   // lift the thumb
            moveEnd = null;
        }
        // SING stick
        float scx = layout.singX * sz[0], scy = layout.singY * sz[1];
        if (a.sing) {
            float[] t = stickPoint(scx, scy, a.aimX, a.aimY, sz);
            if (aimStroke == null) newAim = new GestureDescription.StrokeDescription(line(new float[]{scx, scy}, t), 0, segMs, true);
            else newAim = aimStroke.continueStroke(line(aimEnd, t), 0, segMs, true);
            b.addStroke(newAim); strokes++;
            aimEnd = t;
        } else if (aimStroke != null) {
            b.addStroke(aimStroke.continueStroke(line(aimEnd, aimEnd), 0, 20, false)); strokes++;
            aimEnd = null;
        }
        // one tap per gesture
        if (live && pendingButton >= 0 && pendingButton < 4) {
            float x = layout.btnX[pendingButton] * sz[0], y = layout.btnY[pendingButton] * sz[1];
            b.addStroke(new GestureDescription.StrokeDescription(line(new float[]{x, y}, new float[]{x, y}), 0, 50)); strokes++;
            pendingButton = -1;
        } else if (live && pendingUlt) {
            float x = layout.ultX * sz[0], y = layout.ultY * sz[1];
            b.addStroke(new GestureDescription.StrokeDescription(line(new float[]{x, y}, new float[]{x, y}), 0, 50)); strokes++;
            pendingUlt = false;
        } else if (!live) { pendingButton = -1; pendingUlt = false; }
        moveStroke = newMove;
        aimStroke = newAim;
        if (strokes == 0) return;
        GestureDescription g;
        try { g = b.build(); } catch (RuntimeException e) { lastGestureProblem = "gesture refused: " + e.getMessage(); moveStroke = aimStroke = null; return; }
        inFlight = true;
        boolean ok;
        try {
            ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) {
                    inFlight = false;
                    gesturesSent++;
                    // keep holding: send the next segment right away
                    if (moveStroke != null || aimStroke != null || pendingButton >= 0 || pendingUlt) dispatch();
                }
                @Override public void onCancelled(GestureDescription d) {
                    inFlight = false;
                    gesturesCancelled++;
                    lastGestureProblem = "a held stick was interrupted (screen touched or system gesture)";
                    moveStroke = aimStroke = null;
                    moveEnd = aimEnd = null;
                    main.postDelayed(() -> { if (!inFlight) dispatch(); }, 30);
                }
            }, main);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) {
            inFlight = false;
            moveStroke = aimStroke = null;
            lastGestureProblem = "Android refused the gesture";
        }
    }

    // =========================================================================== panel

    private LinearLayout panel;
    private TextView startStop, pauseBtn, line1, line2;
    private WindowManager.LayoutParams panelLp;

    private TextView chip(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        bg.setColor(color);
        t.setBackground(bg);
        return t;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPanel() {
        if (panel != null) return;
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0140A1E);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFFE8457A);
        panel.setBackground(bg);
        panel.setPadding(dp(5), dp(4), dp(8), dp(4));
        startStop = chip("START", 0xFF2E9E5B);
        startStop.setOnClickListener(v -> toggleRun());
        panel.addView(startStop);
        pauseBtn = chip("II", 0xFF5A4A6E);
        pauseBtn.setOnClickListener(v -> togglePause());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(4);
        panel.addView(pauseBtn, lp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(8), 0, 0, 0);
        line1 = new TextView(this); line1.setTextColor(0xFFF2E6F7); line1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11); line1.setMaxLines(1);
        line2 = new TextView(this); line2.setTextColor(0xFFB9A8C9); line2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10); line2.setMaxLines(1);
        line1.setText("Nick Rocks AI Bot");
        texts.addView(line1); texts.addView(line2);
        panel.addView(texts, new LinearLayout.LayoutParams(dp(200), -2));

        int[] sz = screenSize();
        panelLp = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        // default: the empty strip at the top between the hearts and the minimap
        int[] pos = AISettingsManager.panelPos(this, (int) (sz[0] * 0.41f), dp(6));
        panelLp.x = pos[0]; panelLp.y = pos[1];
        View.OnTouchListener drag = new View.OnTouchListener() {
            float sx, sy; int ox, oy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); ox = panelLp.x; oy = panelLp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        int[] s = screenSize();
                        panelLp.x = Math.max(0, Math.min(s[0] - panel.getWidth(), ox + Math.round(e.getRawX() - sx)));
                        panelLp.y = Math.max(0, Math.min(s[1] - panel.getHeight(), oy + Math.round(e.getRawY() - sy)));
                        try { wm.updateViewLayout(panel, panelLp); } catch (RuntimeException ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                        AISettingsManager.savePanelPos(ControlService.this, panelLp.x, panelLp.y);
                        return true;
                }
                return false;
            }
        };
        texts.setOnTouchListener(drag);
        try { wm.addView(panel, panelLp); } catch (RuntimeException e) { panel = null; }
        refreshPanel();
    }

    private void toggleRun() {
        if (BotState.running()) {
            RunSessionManager s = BotState.session;
            if (s != null) s.requestStop("Stopped by you");
        } else {
            startActivity(new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_START_RUN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        refreshPanel();
    }

    private void togglePause() {
        if (!BotState.running()) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        BotState.paused = !BotState.paused;
        RunSessionManager s = BotState.session;
        if (s != null) s.requestPause(BotState.paused);
        if (BotState.paused) releaseAll();
        refreshPanel();
    }

    /** Thread-safe. */
    public void refreshPanel() {
        main.post(() -> {
            if (panel == null) return;
            boolean on = BotState.running();
            startStop.setText(on ? "STOP" : "START");
            ((GradientDrawable) startStop.getBackground()).setColor(on ? 0xFFD64545 : 0xFF2E9E5B);
            pauseBtn.setText(!on ? "☰" : BotState.paused ? "▶" : "II");
            if (!on) { line1.setText("AI BOT — " + (BotState.captureActive ? "ready" : "press START")); line2.setText(BotState.status); }
        });
    }

    /** Thread-safe status lines while a run is going. */
    public void status(String a, String b) {
        main.post(() -> { if (line1 != null) { line1.setText(a); line2.setText(b); } });
    }

    public void setPanelVisible(boolean v) {
        main.post(() -> { if (panel != null) panel.setVisibility(v ? View.VISIBLE : View.GONE); });
    }

    // =========================================================================== debug overlay

    /** What the overlay draws; filled by the capture thread, read by the UI thread. */
    public static final class DebugFrame {
        public Observation obs;
        public float sx = 1, sy = 1;                 // capture -> screen
        public List<float[]> path = new ArrayList<>();
        public float aimX, aimY;
        public String[] lines = new String[0];
    }

    private DebugView debugView;
    private volatile DebugFrame debug;

    public void showDebug(DebugFrame f) {
        debug = f;
        main.post(() -> {
            if (debugView == null) {
                debugView = new DebugView(this);
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                p.gravity = Gravity.TOP | Gravity.START;
                try { wm.addView(debugView, p); } catch (RuntimeException e) { debugView = null; return; }
            }
            debugView.invalidate();
        });
    }

    public void hideDebug() {
        main.post(() -> { removeView(debugView); debugView = null; });
    }

    /** Pure green, no anti-aliasing: the screen reader skips these exact pixels. */
    private final class DebugView extends View {
        final Paint p = new Paint();
        DebugView(Context c) { super(c); p.setAntiAlias(false); p.setColor(Color.rgb(0, 255, 0)); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); }

        @Override protected void onDraw(Canvas c) {
            DebugFrame f = debug;
            if (f == null || f.obs == null) return;
            Observation o = f.obs;
            p.setStyle(Paint.Style.STROKE);
            if (o.player != null) {
                box(c, o.player, f);
                if (f.aimX != 0 || f.aimY != 0)
                    c.drawLine(o.player.x * f.sx, o.player.y * f.sy, (o.player.x + f.aimX * 60) * f.sx, (o.player.y + f.aimY * 60) * f.sy, p);
            }
            p.setTextSize(dp(10));
            for (Observation.Thing e : o.enemies) {
                box(c, e, f);
                p.setStyle(Paint.Style.FILL);
                c.drawText(String.format(java.util.Locale.US, "%s %.2f", e.kind == Observation.Kind.BOSS ? "BOSS" : "E", e.danger), (e.x - e.w / 2) * f.sx, (e.y - e.h / 2) * f.sy - 3, p);
                p.setStyle(Paint.Style.STROKE);
            }
            for (Observation.Thing s : o.shots) {
                c.drawCircle(s.x * f.sx, s.y * f.sy, Math.max(4, s.r * f.sx), p);
                c.drawLine(s.x * f.sx, s.y * f.sy, (s.x + s.vx * 0.3f) * f.sx, (s.y + s.vy * 0.3f) * f.sy, p);
            }
            for (int i = 1; i < f.path.size(); i++) {
                float[] a = f.path.get(i - 1), b = f.path.get(i);
                c.drawLine(a[0] * f.sx, a[1] * f.sy, b[0] * f.sx, b[1] * f.sy, p);
            }
            // text block in the empty top strip, right of the hearts
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(dp(9));
            float x = getWidth() * 0.42f, y = getHeight() * 0.03f + dp(44);
            for (String s : f.lines) { c.drawText(s, x, y, p); y += dp(10); }
        }

        void box(Canvas c, Observation.Thing t, DebugFrame f) {
            c.drawRect((t.x - t.w / 2) * f.sx, (t.y - t.h / 2) * f.sy, (t.x + t.w / 2) * f.sx, (t.y + t.h / 2) * f.sy, p);
        }
    }

    // =========================================================================== calibration

    private FrameLayout calib;

    /** Shows draggable markers over the game for the two sticks, 4 buttons and the ULT star. */
    @SuppressLint("ClickableViewAccessibility")
    public void showCalibration() {
        main.post(() -> {
            if (calib != null) return;
            ScreenLayout l = AISettingsManager.layout(this);
            int[] sz = screenSize();
            calib = new FrameLayout(this);
            calib.setBackgroundColor(0x33000000);
            String[] names = {"MOVE", "SING", "B1", "B2", "B3", "B4", "ULT"};
            float[][] pos = {{l.moveX, l.moveY}, {l.singX, l.singY}, {l.btnX[0], l.btnY[0]}, {l.btnX[1], l.btnY[1]},
                    {l.btnX[2], l.btnY[2]}, {l.btnX[3], l.btnY[3]}, {l.ultX, l.ultY}};
            TextView[] marks = new TextView[names.length];
            for (int i = 0; i < names.length; i++) {
                final int k = i;
                TextView m = new TextView(this);
                m.setText(i >= 2 && i <= 5 ? names[i] + "\n" + l.btnRole[i - 2] : names[i]);
                m.setGravity(Gravity.CENTER);
                m.setTextColor(Color.WHITE);
                m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(0x99E8457A);
                d.setStroke(dp(2), Color.WHITE);
                m.setBackground(d);
                int size = i < 2 ? dp(64) : dp(48);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
                lp.leftMargin = Math.round(pos[i][0] * sz[0] - size / 2f);
                lp.topMargin = Math.round(pos[i][1] * sz[1] - size / 2f);
                calib.addView(m, lp);
                marks[i] = m;
                m.setOnTouchListener(new View.OnTouchListener() {
                    float sx, sy; int ox, oy; boolean moved;
                    @Override public boolean onTouch(View v, MotionEvent e) {
                        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
                        switch (e.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); ox = p.leftMargin; oy = p.topMargin; moved = false; return true;
                            case MotionEvent.ACTION_MOVE:
                                if (Math.abs(e.getRawX() - sx) + Math.abs(e.getRawY() - sy) > dp(6)) moved = true;
                                p.leftMargin = ox + Math.round(e.getRawX() - sx); p.topMargin = oy + Math.round(e.getRawY() - sy);
                                v.setLayoutParams(p);
                                return true;
                            case MotionEvent.ACTION_UP:
                                if (!moved && k >= 2 && k <= 5) {
                                    // tap a button marker to change what it does
                                    String cur = l.btnRole[k - 2];
                                    int idx = 0;
                                    for (int r = 0; r < ScreenLayout.ROLES.length; r++) if (ScreenLayout.ROLES[r].equals(cur)) idx = r;
                                    l.btnRole[k - 2] = ScreenLayout.ROLES[(idx + 1) % ScreenLayout.ROLES.length];
                                    ((TextView) v).setText(names[k] + "\n" + l.btnRole[k - 2]);
                                }
                                return true;
                        }
                        return false;
                    }
                });
            }
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER);
            TextView hint = new TextView(this);
            hint.setText("Drag each circle onto its control. Tap B1–B4 to change their job. ");
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            TextView save = chip("SAVE", 0xFF2E9E5B), reset = chip("DEFAULTS", 0xFF5A4A6E), cancel = chip("CANCEL", 0xFFD64545);
            bar.addView(hint);
            bar.addView(save); bar.addView(reset); bar.addView(cancel);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
            blp.topMargin = (int) (sz[1] * 0.35f);
            calib.addView(bar, blp);
            save.setOnClickListener(v -> {
                float[][] np = new float[names.length][2];
                for (int i = 0; i < names.length; i++) {
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) marks[i].getLayoutParams();
                    np[i][0] = (p.leftMargin + p.width / 2f) / sz[0];
                    np[i][1] = (p.topMargin + p.height / 2f) / sz[1];
                }
                l.moveX = np[0][0]; l.moveY = np[0][1]; l.singX = np[1][0]; l.singY = np[1][1];
                for (int i = 0; i < 4; i++) { l.btnX[i] = np[2 + i][0]; l.btnY[i] = np[2 + i][1]; }
                l.ultX = np[6][0]; l.ultY = np[6][1];
                AISettingsManager.save(this, l);
                hideCalibration();
            });
            reset.setOnClickListener(v -> { AISettingsManager.resetLayout(this); hideCalibration(); showCalibration(); });
            cancel.setOnClickListener(v -> hideCalibration());
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            try { wm.addView(calib, p); } catch (RuntimeException e) { calib = null; }
        });
    }

    public void hideCalibration() {
        main.post(() -> { removeView(calib); calib = null; });
    }
}
