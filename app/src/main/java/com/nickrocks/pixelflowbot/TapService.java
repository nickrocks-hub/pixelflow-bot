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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.pixelflowbot.vision.Frame;
import com.nickrocks.pixelflowbot.vision.GameState;
import com.nickrocks.pixelflowbot.vision.Planner;
import com.nickrocks.pixelflowbot.vision.Tracker;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Accessibility service: floating panel, live reading, and automatic play.
 *
 * Auto-play safety rules:
 *  - Taps ONLY the 4 queue characters and 5 waiting-slot characters. Nothing else, ever.
 *  - Acts only while the app that was in front when you pressed Start is still in front.
 *  - Stops on any non-gameplay screen (win, fail, offers, ads, map, loading).
 *  - Pauses when the state can't be read, when stuck, or when taps don't change anything.
 *  - At most MAX_MOVES taps per Start.
 */
public class TapService extends AccessibilityService {
    private static final String TAG = "PFBot.Tap";
    public static final String PREFS = "bot";
    public static final String KEY_X = "tapX";
    public static final String KEY_Y = "tapY";

    private static final int MAX_MOVES = 400;
    private static final long TAP_GAP_MS = 1200;        // minimum time between taps
    private static final long READ_GAP_MS = 350;        // time between reads
    private static final int MAX_UNREADABLE = 30;       // consecutive unreadable reads before pausing
    private static final int MAX_SAME_STATE_TAPS = 3;   // taps with no visible effect before pausing

    private static volatile TapService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Tracker tracker = new Tracker();
    private final Planner planner = new Planner();
    private WindowManager wm;
    private LinearLayout panel, manualRows;
    private WindowManager.LayoutParams panelParams;
    private TextView status, reading;
    private Button liveButton, startButton, pauseButton;
    private int tapsSent = 0, tapsDone = 0;
    private boolean stopped = false;
    private boolean live = false;
    private boolean readBusy = false;
    private int readId = 0;
    private String foregroundApp = "?";

    // Auto-play state
    private boolean auto = false, autoPaused = false;
    private String gamePkg;
    private Recorder rec;
    private int autoMoves = 0, unreadable = 0, sameStateTaps = 0;
    private long lastTapAt = 0, lastChangeAt = 0;
    private static final long IDLE_PAUSE_MS = 45000;   // nothing changes and nothing to do for this long
    private String prevSig, lastTapSig;
    private GameState lastGameplay;
    private String autoLine = "";

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
                String top;
                if (auto) {
                    top = (autoPaused ? "AUTO PAUSED" : "AUTO PLAYING") + " · moves " + autoMoves + "/" + MAX_MOVES
                            + "\n" + autoLine;
                } else {
                    top = "Capture: " + (CaptureService.isRunning()
                            ? "ON · " + CaptureService.getFrameCount() + " frames" : "off")
                            + "  ·  Taps " + tapsDone + "/" + tapsSent
                            + "\nApp in front: " + currentPackage() + "   (drag here)";
                }
                status.setText(top);
            }
            if (!stopped) handler.postDelayed(this, 400);
        }
    };

    // ---------- Panel ----------

    private void showPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        panel.setPadding(pad, dp(8), pad, pad);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Ui.PANEL_TOP, Ui.PANEL_BOTTOM});
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Ui.PANEL_STROKE);
        panel.setBackground(bg);
        panel.setElevation(dp(8));

        // Child 0 (always visible, also the drag handle): brand line + live status.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        TextView brand = new TextView(this);
        brand.setText("⬢ PIXELFLOW BOT");
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

        LinearLayout controls = new LinearLayout(this);
        startButton = makeButton("▶ Start", Ui.ACCENT, v -> startAuto());
        pauseButton = makeButton("⏸ Pause", Ui.MUTED, v -> togglePause());
        controls.addView(startButton);
        controls.addView(pauseButton);
        controls.addView(makeButton("■ STOP", Ui.DANGER, v -> stopAll()));
        panel.addView(controls);

        manualRows = new LinearLayout(this);
        manualRows.setOrientation(LinearLayout.VERTICAL);
        reading = new TextView(this);
        reading.setTextColor(Ui.TEXT_DIM);
        reading.setTextSize(11);
        reading.setText("Start = auto-play this level. Read/Live = just watch.");
        reading.setPadding(0, dp(4), 0, 0);
        manualRows.addView(reading);
        LinearLayout row1 = new LinearLayout(this);
        row1.addView(makeButton("Read", Ui.INFO, v -> readOnce()));
        liveButton = makeButton("Live: off", Ui.MUTED, v -> toggleLive());
        row1.addView(liveButton);
        row1.addView(makeButton("Save frame", Ui.MUTED, v -> saveFrame()));
        manualRows.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.addView(makeButton("Test tap", Ui.MUTED, v -> testTap()));
        row2.addView(makeButton("Recalibrate", Ui.MUTED, v -> {
            tracker.reset();
            toast("Board will be re-measured on the next read");
        }));
        manualRows.addView(row2);
        panel.addView(manualRows);
        updateButtons();

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        // Top of the screen, over the Pause/Level/coins bar: the bot never reads or taps there.
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = dp(4);
        panelParams.y = dp(4);

        head.setOnTouchListener(new View.OnTouchListener() {
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
                        panelParams.y = startY + (int) (e.getRawY() - downY);
                        if (panel != null) wm.updateViewLayout(panel, panelParams);
                        return true;
                }
                return false;
            }
        });

        wm.addView(panel, panelParams);
        // Extra games (Candy Crush, Nonogram) add themselves to the panel; Pixel Flow is unaffected if they fail.
        CandyMode.attach(this);
    }

    private void updateButtons() {
        if (manualRows == null) return;
        // While auto-playing, the panel shrinks to the status line + controls so it stays in the top bar.
        manualRows.setVisibility(auto ? View.GONE : View.VISIBLE);
        startButton.setText(auto ? (autoPaused ? "▶ Resume" : "▶ Playing") : "▶ Start");
        pauseButton.setEnabled(auto && !autoPaused);
        pauseButton.setAlpha(auto && !autoPaused ? 1f : 0.4f);
    }

    private Button makeButton(String label, int color, View.OnClickListener l) {
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(3), dp(4), dp(3), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ---------- Foreground app ----------

    private String currentPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String p = root.getPackageName().toString();
                if (!p.equals(getPackageName())) return p;
            }
        } catch (Exception ignored) { }
        return foregroundApp;
    }

    // ---------- Auto-play ----------

    private void startAuto() {
        if (auto) {
            if (autoPaused) resumeAuto();
            return;
        }
        if (!CaptureService.isRunning()) { toast("Start screen capture in the app first."); return; }
        String pkg = currentPackage();
        if (pkg == null || pkg.equals("?") || pkg.equals(getPackageName())) {
            toast("Open Pixel Flow first, then press Start.");
            return;
        }
        live = false;
        if (liveButton != null) liveButton.setText("Live: off");
        gamePkg = pkg;
        auto = true;
        autoPaused = false;
        autoMoves = 0; unreadable = 0; sameStateTaps = 0;
        prevSig = null; lastTapSig = null; lastGameplay = null; lastTapAt = 0; lastChangeAt = 0;
        planner.reset();
        rec = new Recorder(this);
        rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"start\",\"app\":" + Recorder.q(gamePkg)
                + ",\"version\":\"0.6\",\"planner\":\"exposed-edge-v2\"}");
        autoLine = "Starting… logging to " + rec.where();
        updateButtons();
        handler.postDelayed(this::autoStep, 300);
    }

    private void togglePause() {
        if (auto && !autoPaused) pauseAuto("Paused by you");
    }

    private void pauseAuto(String why) {
        if (!auto) return;
        autoPaused = true;
        autoLine = why;
        if (rec != null) rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"pause\",\"why\":" + Recorder.q(why) + "}");
        updateButtons();
        toast(why);
    }

    private void resumeAuto() {
        String pkg = currentPackage();
        if (gamePkg != null && !gamePkg.equals(pkg)) { toast("Open Pixel Flow first, then Resume."); return; }
        autoPaused = false;
        unreadable = 0; sameStateTaps = 0; prevSig = null; lastTapSig = null; lastChangeAt = 0;
        autoLine = "Resuming…";
        if (rec != null) rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"resume\"}");
        updateButtons();
        handler.postDelayed(this::autoStep, 300);
    }

    /** Ends auto-play (the panel and capture stay on). */
    private void endAuto(String why) {
        if (!auto) return;
        if (rec != null) rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"end\",\"why\":" + Recorder.q(why)
                + ",\"moves\":" + autoMoves + "}");
        auto = false;
        autoPaused = false;
        if (reading != null) reading.setText("Auto-play ended: " + why + "\nMoves: " + autoMoves
                + (rec != null ? "\nLog: " + rec.where() : ""));
        updateButtons();
        toast("Auto-play ended: " + why);
    }

    private void autoStep() {
        if (!auto || autoPaused || stopped) return;
        if (!CaptureService.isRunning()) { endAuto("screen capture stopped"); return; }
        String pkg = currentPackage();
        if (!gamePkg.equals(pkg)) { pauseAuto("Pixel Flow is not in front (" + pkg + ")"); return; }

        final int id = ++readId;
        final boolean[] answered = {false};
        CaptureService.requestFrame(frame -> worker.execute(() -> {
            GameState s; Planner.Decision d; String err = null;
            try {
                s = tracker.update(frame);
                d = planner.decide(s);
            } catch (Exception e) {
                Log.e(TAG, "auto read failed", e);
                s = null; d = null; err = e.toString();
            }
            final GameState fs = s; final Planner.Decision fd = d; final String ferr = err;
            handler.post(() -> {
                if (id != readId || answered[0]) return;
                answered[0] = true;
                onAutoRead(frame, fs, fd, ferr);
            });
        }));
        // Frames only arrive when the screen changes; a still screen is treated as unreadable once.
        handler.postDelayed(() -> {
            if (id == readId && !answered[0] && auto && !autoPaused) {
                answered[0] = true;
                CaptureService.requestFrame(null);
                onAutoRead(null, null, null, "no new frame");
            }
        }, 1500);
    }

    private void onAutoRead(Frame frame, GameState s, Planner.Decision d, String err) {
        if (!auto || autoPaused || stopped) return;
        if (s == null) {
            if (++unreadable >= MAX_UNREADABLE) { pauseAuto("Can't read the game (" + err + ")"); return; }
            autoLine = "Reading… (" + err + ")";
            handler.postDelayed(this::autoStep, READ_GAP_MS);
            return;
        }

        // Rule: stop on anything that isn't normal gameplay (win, fail, offers, ads, map, loading).
        if (s.screen != GameState.Screen.GAMEPLAY) {
            String outcome = guessOutcome(lastGameplay);
            rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"level_end\",\"screen\":" + Recorder.q(s.screen.name())
                    + ",\"outcome\":" + Recorder.q(outcome) + ",\"moves\":" + autoMoves
                    + (lastGameplay != null ? "," + Recorder.state(lastGameplay, true) : "") + "}");
            endAuto(outcome.equals("win") ? "level completed!" : outcome.equals("fail") ? "level failed (out of space)"
                    : "a popup or other screen appeared");
            if (outcome.equals("win")) CandyMode.pixelFlowWon();
            return;
        }
        if (s.board == null) {
            if (++unreadable >= MAX_UNREADABLE) { pauseAuto("Can't measure the board. Press Recalibrate at a level start."); return; }
            autoLine = "Measuring the board…";
            handler.postDelayed(this::autoStep, READ_GAP_MS);
            return;
        }
        unreadable = 0;
        lastGameplay = s;

        // Act only on a settled screen: the same slots/queue/belt seen twice in a row.
        String sig = Recorder.chars(s.slots) + Recorder.chars(s.queue) + s.beltFree;
        boolean settled = sig.equals(prevSig);
        long now = System.currentTimeMillis();
        if (!settled || lastChangeAt == 0) lastChangeAt = now;
        prevSig = sig;
        if (!settled || now - lastTapAt < TAP_GAP_MS) {
            handler.postDelayed(this::autoStep, READ_GAP_MS);
            return;
        }

        if (d.stuck) {
            rec.line("{\"t\":" + now + ",\"event\":\"stuck\",\"why\":" + Recorder.q(d.reason) + "," + Recorder.state(s, true) + "}");
            pauseAuto(d.reason);
            return;
        }
        if (d.move == null) {
            if (now - lastChangeAt > IDLE_PAUSE_MS) {
                rec.line("{\"t\":" + now + ",\"event\":\"idle\",\"why\":" + Recorder.q(d.reason) + "," + Recorder.state(s, true) + "}");
                pauseAuto("Nothing has changed for 45 s (" + d.reason + ")");
                return;
            }
            autoLine = "Waiting: " + d.reason;
            handler.postDelayed(this::autoStep, READ_GAP_MS);
            return;
        }
        if (autoMoves >= MAX_MOVES) { endAuto("move limit reached (" + MAX_MOVES + ")"); return; }
        if (sig.equals(lastTapSig)) {
            if (++sameStateTaps >= MAX_SAME_STATE_TAPS) { pauseAuto("Taps aren't changing the game"); return; }
        } else {
            sameStateTaps = 0;
        }

        float x = d.move.layoutX() * frame.sx, y = d.move.layoutY() * frame.sy;
        autoMoves++;
        lastTapAt = now;
        lastTapSig = sig;
        autoLine = "Move " + autoMoves + ": " + d.move + " · " + d.reason;
        rec.line("{\"t\":" + now + ",\"event\":\"move\",\"n\":" + autoMoves + ",\"tap\":" + Recorder.q(d.move.toString())
                + ",\"reason\":" + Recorder.q(d.reason) + ",\"inFlight\":" + Recorder.q(planner.inFlightText())
                + "," + Recorder.state(s, autoMoves == 1) + "}");
        planner.committed(d.move);
        tap(x, y);
        handler.postDelayed(this::autoStep, READ_GAP_MS);
    }

    private static String guessOutcome(GameState last) {
        if (last == null || last.board == null) return "unknown";
        if (last.slotsUsed() >= 5) return "fail";
        int cubes = 0;
        for (int c : Planner.remaining(last.board)) cubes += c;
        return cubes <= 40 ? "win" : "unknown";
    }

    // ---------- Reading (manual) ----------

    private void toggleLive() {
        if (auto) return;
        live = !live;
        liveButton.setText(live ? "Live: ON" : "Live: off");
        if (live) readOnce();
    }

    /** Hides the panel, grabs one frame, reads it in the background, then shows the result. */
    private void readOnce() {
        if (stopped || readBusy || auto) return;
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
                    Planner.Decision d = planner.decide(s);
                    long ms = System.currentTimeMillis() - t0;
                    text = s.summary() + (tracker.isCalibrating() && s.screen == GameState.Screen.GAMEPLAY
                            ? "\n(measuring board: waits for a still board with an empty belt)" : "")
                            + "\nWould " + (d.move != null ? "tap " + d.move : d.stuck ? "pause" : "wait") + ": " + d.reason
                            + "\n[" + frame.w + "x" + frame.h + ", read in " + ms + " ms]";
                } catch (Exception e) {
                    Log.e(TAG, "Read failed", e);
                    text = "Read error: " + e;
                }
                final String shown = text;
                handler.post(() -> finishRead(id, shown));
            }));
        }, 150);
        handler.postDelayed(() -> {
            if (readBusy && id == readId && started[0] != 0 && System.currentTimeMillis() - started[0] >= 900) {
                CaptureService.requestFrame(null);
                finishRead(id, "No new frame (screen didn't change). Try again.");
            }
        }, 1100);
    }

    private void finishRead(int id, String text) {
        if (id != readId || !readBusy) return;
        readBusy = false;
        if (panel == null) return;
        panel.setVisibility(View.VISIBLE);
        reading.setText(text);
        if (live && !stopped && !auto) handler.postDelayed(this::readOnce, 1000);
    }

    // ---------- Taps ----------

    private void tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        tapsSent++;
        boolean accepted = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { tapsDone++; }
            @Override public void onCancelled(GestureDescription d) { Log.w(TAG, "tap cancelled"); }
        }, handler);
        if (!accepted) toast("Tap rejected (is the service still enabled?)");
    }

    /** One tap at the saved point (default: screen centre), 400 ms after pressing the button. */
    private void testTap() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        final float x = p.getFloat(KEY_X, dm.widthPixels / 2f);
        final float y = p.getFloat(KEY_Y, dm.heightPixels / 2f);
        handler.postDelayed(() -> { if (!stopped) tap(x, y); }, 400);
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

    /** Stops auto-play and capture, removes the panel and turns this service off. */
    /** Moves the panel so its top edge is at screen y (used to keep it off a game's board). */
    private void movePanelTo(int y) {
        if (panel == null || panelParams == null) return;
        int[] loc = new int[2];
        panel.getLocationOnScreen(loc);
        panelParams.y += y - loc[1];
        try { wm.updateViewLayout(panel, panelParams); } catch (Exception ignored) { }
    }

    /** Message from the panel's auto-next helper, shown in the Pixel Flow info line. */
    private void showNote(String msg) {
        autoLine = msg;
        if (reading != null) reading.setText(msg);
    }

    private void stopAll() {
        CandyMode.cancelAutoNext();
        if (auto) endAuto("stopped by you");
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (p.equals(getPackageName()) || p.equals("com.android.systemui")) return;
        foregroundApp = p;
        // Leaving the game pauses auto-play immediately.
        if (auto && !autoPaused && gamePkg != null && !gamePkg.equals(p)) {
            handler.post(() -> pauseAuto("Pixel Flow is not in front (" + p + ")"));
        }
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
        auto = false;
        handler.removeCallbacksAndMessages(null);
        CaptureService.requestFrame(null);
        removePanel();
        instance = null;
    }
}
