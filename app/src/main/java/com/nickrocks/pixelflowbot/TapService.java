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
 * Accessibility service for AI Game Bot: the floating panel and the taps/swipes for the games in
 * your library that have a player — Nonogram.com ({@link NonogramMode}) and Candy Crush Saga
 * ({@link CandyMode}) — plus automatic "next level" ({@link AutoNext}).
 *
 * The panel follows the game in front: open a library game (from the app or yourself) and the
 * panel switches to it. Games without a player get an honest "can't play this yet" panel.
 *
 * Safety: it only acts after you press Start/Solve, only while that game is still in front, and
 * STOP turns everything off (panel, capture, this service).
 */
public class TapService extends AccessibilityService {
    private static final String TAG = "AIGameBot.Tap";
    public static final String PREFS = "bot";
    /** Which player the panel shows: "nonogram", "candy", "none" (library game without a player) or "idle". */
    private String active = "idle";
    private String activePkg, activeName = "";

    private static volatile TapService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelParams;
    private TextView status;
    private View nextRow;
    private Button nextToggle;
    private NonogramMode nono;
    private CandyMode candy;
    private InstructionMode instr;
    private LinearLayout noneBox;
    private TextView noneText, brand;
    private Button teachBtn, forgetBtn;
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
        brand = new TextView(this);
        brand.setText("⬢ AI GAME BOT");
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
        nextToggle = button("", Ui.MUTED, v -> { next.setEnabled(active, !next.enabled(active)); refreshNext(); });
        nr.addView(nextToggle);
        // Candy Crush's next-level button is taught by you once (it changes between screens).
        teachBtn = button("Teach next", Ui.MUTED, v -> next.teach(active, this::say));
        forgetBtn = button("Forget", Ui.MUTED, v -> { next.clearSteps(active); refreshNext(); say("Forgot the taught next-level buttons."); });
        nr.addView(teachBtn);
        nr.addView(forgetBtn);
        panel.addView(nr);
        nextRow = nr;

        try {
            nono = new NonogramMode(this, this);
            panel.addView(nono.box());
        } catch (Throwable t) {
            Log.e(TAG, "Nonogram mode could not start", t);
        }
        try {
            candy = new CandyMode(this);
            panel.addView(candy.box());
        } catch (Throwable t) {
            Log.e(TAG, "Candy Crush mode could not start", t);
        }
        try {
            instr = new InstructionMode(this);
            panel.addView(instr.box());
        } catch (Throwable t) {
            Log.e(TAG, "AI vision agent could not start", t);
        }
        noneBox = new LinearLayout(this);
        noneBox.setOrientation(LinearLayout.VERTICAL);
        noneText = new TextView(this);
        noneText.setTextColor(Ui.TEXT_DIM);
        noneText.setTextSize(11);
        noneText.setMaxWidth(dp(300));
        noneBox.addView(noneText);
        LinearLayout nb = new LinearLayout(this);
        nb.addView(button("📸 Save screen", Ui.MUTED, v -> {
            if (!CaptureService.isRunning()) { toast("Screen capture is off — start it in the app first."); return; }
            CaptureService.requestSaveFrame();
        }));
        nb.addView(button("■ STOP", Ui.DANGER, v -> stopAll()));
        noneBox.addView(nb);
        panel.addView(noneBox);
        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString("active_pkg", null);
        setActive(last);

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
        boolean playable = active.equals("nonogram") || active.equals("candy");
        nextRow.setVisibility(playable ? View.VISIBLE : View.GONE);
        if (!playable) return;
        boolean on = next.enabled(active);
        nextToggle.setText(on ? "⟳ Next level: ON" : "⟳ Next level: off");
        Ui.tint(nextToggle, on ? Ui.ACCENT : Ui.MUTED, dp(14));
        boolean taught = active.equals("candy");
        teachBtn.setVisibility(taught ? View.VISIBLE : View.GONE);
        forgetBtn.setVisibility(taught ? View.VISIBLE : View.GONE);
        int n = next.steps(active).size();
        teachBtn.setText(n > 0 ? "Teach (" + n + " step" + (n > 1 ? "s" : "") + ")" : "Teach next ⚠");
    }

    /** Which library game this package is, and which player handles it. */
    private void setActive(String pkg) {
        com.nickrocks.pixelflowbot.core.GameProfile prof = pkg == null ? null : AgentPlatform.get(this).profileForPackage(pkg);
        String key = prof == null ? "idle" : com.nickrocks.pixelflowbot.core.Adapters.panelKey(prof.adapterId);
        active = key;
        activePkg = pkg;
        activeName = prof == null ? "" : prof.name;
        if (prof != null) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("active_pkg", pkg).apply();
        if (nono != null) nono.box().setVisibility(key.equals("nonogram") ? View.VISIBLE : View.GONE);
        if (candy != null) candy.box().setVisibility(key.equals("candy") ? View.VISIBLE : View.GONE);
        boolean ai = key.equals("none") && instr != null;
        if (instr != null) {
            instr.box().setVisibility(ai ? View.VISIBLE : View.GONE);
            if (ai) instr.setProfile(prof);
        }
        noneBox.setVisibility(key.equals("idle") || (key.equals("none") && !ai) ? View.VISIBLE : View.GONE);
        noneText.setText(key.equals("idle") ? "Open a game from your library — this panel switches to it."
                : activeName + ": no player for this game, and the AI vision agent couldn't start.");
        brand.setText("⬢ AI GAME BOT" + (activeName.isEmpty() ? "" : " · " + activeName.toUpperCase(java.util.Locale.US)));
        refreshNext();
    }

    /** A game's player is mid-move: don't switch the panel under it. */
    private boolean busy() {
        return (nono != null && nono.busy()) || (candy != null && candy.busy()) || (instr != null && instr.busy()) || (next != null && next.busy());
    }

    AutoNext autoNext() { return next; }

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

    String gameName(String g) { return "candy".equals(g) ? "Candy Crush" : "nonogram".equals(g) ? "Nonogram" : g; }

    void cancelNext() { if (next != null) next.cancel(); }

    void say(String msg) {
        refreshNext();
        if (active.equals("candy") && candy != null) candy.note(msg);
        else if (active.equals("nonogram") && nono != null) nono.note(msg);
        else if (active.equals("none") && instr != null) instr.note(msg);
        else toast(msg);
    }

    /** The solver finished a puzzle: press Next Level, wait for the new board, solve it. */
    void nonogramSolved() {
        if (nono == null || !next.enabled("nonogram") || !active.equals("nonogram")) return;
        levelsDone++;
        nono.parkForNextLevel();
        final com.nickrocks.pixelflowbot.nono.NonoVision v = new com.nickrocks.pixelflowbot.nono.NonoVision();
        next.run("nonogram", com.nickrocks.pixelflowbot.nono.NextButton::find,
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
        if (candy != null) candy.shutdown();
        if (instr != null) instr.shutdown();
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        String p = event.getPackageName().toString();
        if (p.equals(getPackageName()) || p.equals("com.android.systemui") || p.equals(activePkg) || panel == null) return;
        if (AgentPlatform.get(this).profileForPackage(p) == null) return;   // not a library game: keep the panel as is
        if (busy()) return;                                                 // never switch mid-game
        setActive(p);
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
        stopped = true;
        if (nono != null) nono.shutdown();
        if (candy != null) candy.shutdown();
        if (instr != null) instr.shutdown();
        if (next != null) next.shutdown();
        handler.removeCallbacksAndMessages(null);
        CaptureService.requestFrame(null);
        removePanel();
        instance = null;
    }
}
