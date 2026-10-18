package com.nickrocks.pixelflowbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nickrocks.pixelflowbot.nono.NonoSolver;
import com.nickrocks.pixelflowbot.nono.NonoVision;
import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Nonogram.com auto-solver: reads the clues off the screen, solves the puzzle exactly, proves the
 * answer is unique, then fills it in with taps (or quick row swipes), checking the board as it goes.
 *
 * Safety: it only ever fills cells the solver has proven, never guesses, never taps outside the
 * grid except the fill/cross switch, and stops if the game shows anything it didn't expect.
 */
final class NonogramMode {
    static final String TAG = "PFBot.Nono";
    private static final String PREF_SWIPE = "nono_swipe";
    private static final String PREF_SPEED = "nono_speed";

    private static final int IDLE = 0, PLAYING = 1, PAUSED = 2;

    private final TapService svc;
    private final CandyMode host;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final NonoVision vision = new NonoVision();
    private final WindowManager wm;

    private final LinearLayout box;
    private final TextView info;
    private final Button startButton, pauseButton, swipeButton, speedButton;
    private View pinger, overlay;
    private boolean pingFlip;

    private int state = IDLE;
    private boolean dead, readBusy;
    private int readId;
    private String gamePkg;
    private Recorder rec;

    // The current game.
    private NonoVision.Grid grid;
    private int[] solution;
    private final List<int[]> plan = new ArrayList<>();   // {row, c0, c1} cells to fill, left to right
    private int planPos, filled, sinceCheck, checks, swipeFailures;
    private long startedAt;
    private int baseRed;
    private int autoRetries;

    NonogramMode(TapService svc, CandyMode host) {
        this.svc = svc;
        this.host = host;
        this.prefs = svc.getSharedPreferences(TapService.PREFS, 0);
        this.wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);

        box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row1 = new LinearLayout(svc);
        startButton = host.button("▶ Solve", Ui.ACCENT, v -> start());
        pauseButton = host.button("⏸ Pause", Ui.MUTED, v -> { if (state == PLAYING) pause("Paused by you"); });
        row1.addView(startButton);
        row1.addView(pauseButton);
        row1.addView(host.button("■ STOP", Ui.DANGER, v -> { host.cancelNext(); end("stopped by you"); TapService.stopEverything(); }));
        box.addView(row1);

        info = new TextView(svc);
        info.setTextColor(Ui.TEXT_DIM);
        info.setTextSize(11f);
        info.setPadding(host.dp(4), host.dp(4), 0, host.dp(2));
        info.setMaxWidth(host.dp(300));
        info.setText("Nonogram.com: open a puzzle, then Solve. Read = check what the bot sees.");
        box.addView(info);

        LinearLayout row2 = new LinearLayout(svc);
        row2.addView(host.button("Read", Ui.INFO, v -> readOnce()));
        swipeButton = host.button("", Ui.MUTED, v -> {
            prefs.edit().putBoolean(PREF_SWIPE, !prefs.getBoolean(PREF_SWIPE, true)).apply();
            refresh();
        });
        speedButton = host.button("", Ui.MUTED, v -> {
            prefs.edit().putInt(PREF_SPEED, (prefs.getInt(PREF_SPEED, 1) + 1) % 3).apply();
            refresh();
        });
        row2.addView(swipeButton);
        row2.addView(speedButton);
        box.addView(row2);
        refresh();
    }

    View box() { return box; }

    boolean busy() { return state != IDLE || readBusy; }

    // ---------------------------------------------------------------- UI

    private void refresh() {
        startButton.setText(state == IDLE ? "▶ Solve" : state == PAUSED ? "▶ Resume" : "▶ Solving");
        pauseButton.setEnabled(state == PLAYING);
        pauseButton.setAlpha(state == PLAYING ? 1f : 0.4f);
        swipeButton.setText(prefs.getBoolean(PREF_SWIPE, true) ? "Fill: swipe rows" : "Fill: tap cells");
        speedButton.setText(new String[]{"Speed: careful", "Speed: normal", "Speed: turbo"}[prefs.getInt(PREF_SPEED, 1)]);
    }

    void note(String s) { setInfo(s); }

    /** Started by auto-next on a fresh puzzle: like Solve, but retries a couple of reads. */
    void autoStart() {
        autoRetries = 3;
        start();
    }

    private void setInfo(String s) {
        String head = "";
        if (state == PLAYING) head = "NONOGRAM · filling " + filled + "/" + totalToFill() + "\n";
        else if (state == PAUSED) head = "NONOGRAM PAUSED · " + filled + "/" + totalToFill() + "\n";
        info.setText(head + s);
    }

    private int totalToFill() {
        int n = 0;
        for (int[] p : plan) n += p[2] - p[1] + 1;
        return n;
    }

    private void toast(String s) { host.toast(s); }

    // ---------------------------------------------------------------- capture

    private interface Sink { void accept(NonoVision.Reading r); }

    /**
     * Grabs a fresh frame and reads it on the worker thread. With hidePanel the floating panel is
     * hidden first so it can't cover the clues. A tiny invisible overlay is flipped to make sure
     * the screen produces a new frame even when nothing else is moving.
     */
    private void capture(boolean hidePanel, boolean cluesToo, Sink sink) {
        final int id = ++readId;
        final boolean[] done = {false};
        final View panel = host.panelView();
        if (hidePanel && panel != null) panel.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            if (id != readId || dead) return;
            CaptureService.requestFrame(frame -> worker.execute(() -> {
                NonoVision.Reading r = cluesToo ? vision.read(frame) : vision.readBoard(frame);
                handler.post(() -> {
                    if (id != readId || done[0]) return;
                    done[0] = true;
                    if (hidePanel && panel != null) panel.setVisibility(View.VISIBLE);
                    sink.accept(r);
                });
            }));
            ping();
        }, hidePanel ? 180 : 60);
        handler.postDelayed(() -> {
            if (id != readId || done[0]) return;
            done[0] = true;
            CaptureService.requestFrame(null);
            if (hidePanel && panel != null) panel.setVisibility(View.VISIBLE);
            NonoVision.Reading r = new NonoVision.Reading();
            r.error = "no new frame from the screen capture";
            sink.accept(r);
        }, 2500);
    }

    /** Changes a 2x2 px nearly-transparent overlay so the screen recorder emits a new frame. */
    private void ping() {
        try {
            if (pinger == null) {
                pinger = new View(svc);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(2, 2,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                wm.addView(pinger, lp);
            }
            pingFlip = !pingFlip;
            pinger.setBackgroundColor(pingFlip ? 0x03000000 : 0x02000000);
        } catch (Throwable t) {
            Log.w(TAG, "ping failed", t);
        }
    }

    // ---------------------------------------------------------------- read / preview

    private void readOnce() {
        if (state == PLAYING || readBusy) return;
        if (!CaptureService.isRunning()) { toast("Capture is off. Start it from the app first."); return; }
        readBusy = true;
        setInfo("Reading…");
        capture(true, true, r -> {
            readBusy = false;
            if (r.grid == null) { setInfo("No puzzle found: " + r.error); return; }
            if (r.error != null) {
                setInfo("Grid " + r.grid + "\nProblem: " + r.error + "\n" + r.cluesText());
                showOverlay(r.grid, null, r, 4000);
                return;
            }
            setInfo("Grid " + r.grid + " · read " + r.ms + " ms · solving…");
            worker.execute(() -> {
                NonoSolver.Result s = new NonoSolver().solve(r.rowClues, r.colClues, null, 6000);
                handler.post(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Grid ").append(r.grid).append(" · ").append(r.ms).append(" ms\n");
                    sb.append("Solver: ").append(s.status).append(" (").append(s.note).append(", ").append(s.ms).append(" ms)\n");
                    sb.append("Mode switch: ").append(r.toggle == 1 ? "fill ✓" : r.toggle == 0 ? "cross (bot will switch it)" : "not found");
                    setInfo(sb.toString());
                    showOverlay(r.grid, s.status == NonoSolver.Status.SOLVED ? s.grid : null, r, 4000);
                });
            });
        });
    }

    /** Draws the bot's view on top of the game: grid outline, and the solution as glowing dots. */
    private void showOverlay(NonoVision.Grid g, int[] sol, NonoVision.Reading r, long ms) {
        removeOverlay();
        final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(host.dp(2));
        frame.setColor(Ui.ACCENT);
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0xCC22D3EE);
        final Paint bad = new Paint(Paint.ANTI_ALIAS_FLAG);
        bad.setColor(0xCCFF4D6D);
        View v = new View(svc) {
            @Override protected void onDraw(Canvas c) {
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                c.drawRoundRect(new RectF(g.left - loc[0] - 4, g.top - loc[1] - 4, g.right - loc[0] + 4, g.bottom - loc[1] + 4), 12, 12, frame);
                if (sol == null) return;
                for (int row = 0; row < g.rows; row++) {
                    for (int col = 0; col < g.cols; col++) {
                        int want = sol[row * g.cols + col];
                        int have = r != null && r.cells != null ? r.cell(row, col) : NonoVision.EMPTY;
                        float x = g.cx(col) - loc[0], y = g.cy(row) - loc[1];
                        if (want == NonoSolver.FILLED && have == NonoVision.EMPTY) c.drawCircle(x, y, g.cell * 0.16f, dot);
                        if (want == NonoSolver.EMPTY && have == NonoVision.FILLED) c.drawCircle(x, y, g.cell * 0.22f, bad);
                    }
                }
            }
        };
        try {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            wm.addView(v, lp);
            overlay = v;
            handler.postDelayed(() -> { if (overlay == v) removeOverlay(); }, ms);
        } catch (Throwable t) {
            Log.w(TAG, "overlay failed", t);
        }
    }

    private void removeOverlay() {
        if (overlay != null) {
            try { wm.removeView(overlay); } catch (Throwable ignored) { }
            overlay = null;
        }
    }

    // ---------------------------------------------------------------- play

    private void start() {
        if (state == PAUSED) { resume(); return; }
        if (state != IDLE || readBusy) return;
        if (!CaptureService.isRunning()) { toast("Capture is off. Start it from the app first."); return; }
        String pkg = host.foregroundPackage();
        if (pkg == null || pkg.equals("?") || pkg.equals(svc.getPackageName())) {
            toast("Open a Nonogram.com puzzle first, then press Solve.");
            return;
        }
        gamePkg = pkg;
        removeOverlay();
        state = PLAYING;
        plan.clear();
        planPos = filled = sinceCheck = checks = swipeFailures = 0;
        startedAt = System.currentTimeMillis();
        try { rec = new Recorder(svc); } catch (Throwable t) { rec = null; }
        log("{\"event\":\"start\",\"pkg\":" + Recorder.q(pkg) + "}");
        refresh();
        setInfo("Reading the puzzle…");
        capture(true, true, this::onFirstRead);
    }

    private void onFirstRead(NonoVision.Reading r) {
        if (state != PLAYING) return;
        if ((r.grid == null || r.error != null) && autoRetries > 0) {
            autoRetries--;
            setInfo("Puzzle still loading… (" + (r.error != null ? r.error : "no grid") + ")");
            handler.postDelayed(() -> { if (state == PLAYING) capture(true, true, this::onFirstRead); }, 1500);
            return;
        }
        autoRetries = 0;
        if (r.grid == null) { end("no puzzle on screen (" + r.error + ")"); return; }
        if (r.error != null) { end(r.error); showOverlay(r.grid, null, r, 5000); return; }
        int[] pr = host.panelRect();
        if (pr != null && pr[0] < r.grid.right && pr[2] > r.grid.left && pr[1] < r.grid.bottom && pr[3] > r.grid.top) {
            end("the panel is covering the puzzle — drag it above or below the grid, then Solve again");
            return;
        }
        grid = r.grid;
        baseRed = r.redCells;
        log("{\"event\":\"read\",\"grid\":" + Recorder.q(grid.toString()) + ",\"clues\":" + Recorder.q(r.cluesText()) + "}");
        setInfo("Grid " + grid + " · solving…");
        worker.execute(() -> {
            NonoSolver.Result s = new NonoSolver().solve(r.rowClues, r.colClues, null, 8000);
            handler.post(() -> onSolved(r, s));
        });
    }

    private void onSolved(NonoVision.Reading r, NonoSolver.Result s) {
        if (state != PLAYING) return;
        log("{\"event\":\"solve\",\"status\":\"" + s.status + "\",\"ms\":" + s.ms + ",\"guesses\":" + s.guesses + "}");
        if (s.status != NonoSolver.Status.SOLVED) {
            end(s.status == NonoSolver.Status.MULTIPLE ? "the clues allow more than one answer — probably a misread, so not risking a heart"
                    : s.status == NonoSolver.Status.TIMEOUT ? "puzzle too hard to prove in time"
                    : "the clues as read have no solution — a digit was probably misread");
            return;
        }
        solution = s.grid;
        // The board must agree with the solution before we touch anything.
        for (int row = 0; row < grid.rows; row++) {
            for (int col = 0; col < grid.cols; col++) {
                int have = r.cell(row, col), want = solution[row * grid.cols + col];
                if (have == NonoVision.FILLED && want == NonoSolver.EMPTY) {
                    showOverlay(grid, solution, r, 6000);
                    end("cell row " + (row + 1) + ", col " + (col + 1) + " is filled but shouldn't be — fix it, then Solve again");
                    return;
                }
                if (have == NonoVision.CROSS && want == NonoSolver.FILLED) {
                    showOverlay(grid, solution, r, 6000);
                    end("cell row " + (row + 1) + ", col " + (col + 1) + " has an X but belongs to the picture — clear it, then Solve again");
                    return;
                }
            }
        }
        buildPlan(r);
        if (plan.isEmpty()) { end("nothing left to fill — puzzle already done"); return; }
        showOverlay(grid, solution, r, 900);
        ensureFillMode(r, 0);
    }

    /** Runs of cells to fill, row by row, skipping cells that are already filled or crossed. */
    private void buildPlan(NonoVision.Reading r) {
        plan.clear();
        filled = 0;
        boolean swipe = prefs.getBoolean(PREF_SWIPE, true) && swipeFailures == 0;
        for (int row = 0; row < grid.rows; row++) {
            int col = 0;
            while (col < grid.cols) {
                if (needs(r, row, col)) {
                    int end = col;
                    if (swipe) while (end + 1 < grid.cols && needs(r, row, end + 1)) end++;
                    plan.add(new int[]{row, col, end});
                    col = end + 1;
                } else col++;
            }
        }
        planPos = 0;
    }

    private boolean needs(NonoVision.Reading r, int row, int col) {
        return solution[row * grid.cols + col] == NonoSolver.FILLED && r.cell(row, col) == NonoVision.EMPTY;
    }

    /** Makes sure the game's switch is on "fill" (square), tapping it once if it's on "cross". */
    private void ensureFillMode(NonoVision.Reading r, int attempt) {
        if (state != PLAYING) return;
        if (r.toggle == 1) { setInfo("Filling " + totalToFill() + " cells…"); handler.postDelayed(this::nextMove, 250); return; }
        if (r.toggle < 0) {
            if (attempt >= 2) { end("can't find the fill/cross switch under the puzzle — make sure it's visible"); return; }
            handler.postDelayed(() -> capture(false, false, r2 -> {
                if (state != PLAYING) return;
                if (r2.grid == null) { pause("lost the puzzle"); return; }
                ensureFillMode(r2, attempt + 1);
            }), 500);
            return;
        }
        if (attempt >= 2) { end("the fill/cross switch won't change to fill"); return; }
        setInfo("Switching the game to fill mode…");
        tap(r.toggleX, r.toggleY, () -> handler.postDelayed(() -> capture(false, false, r2 -> {
            if (state != PLAYING) return;
            if (r2.grid == null) { pause("lost the puzzle after switching modes"); return; }
            ensureFillMode(r2, attempt + 1);
        }), 450));
    }

    private int checkEvery() {
        return new int[]{1, 6, 18}[prefs.getInt(PREF_SPEED, 1)];
    }

    private long gapMs() {
        return new long[]{260, 90, 35}[prefs.getInt(PREF_SPEED, 1)];
    }

    private void nextMove() {
        if (state != PLAYING || dead) return;
        String pkg = host.foregroundPackage();
        if (gamePkg != null && pkg != null && !pkg.equals("?") && !pkg.equals(gamePkg) && !pkg.equals(svc.getPackageName())) {
            pause("the game isn't in front (" + pkg + ")");
            return;
        }
        if (planPos >= plan.size() || sinceCheck >= checkEvery()) {
            verify();
            return;
        }
        int[] m = plan.get(planPos++);
        sinceCheck++;
        float y = grid.cy(m[0]);
        Runnable after = () -> {
            filled += m[2] - m[1] + 1;
            setInfo("Row " + (m[0] + 1) + ": filled " + (m[1] == m[2] ? "col " + (m[1] + 1) : "cols " + (m[1] + 1) + "–" + (m[2] + 1)));
            handler.postDelayed(this::nextMove, gapMs());
        };
        if (m[1] == m[2]) tap(grid.cx(m[1]), y, after);
        else swipe(grid.cx(m[1]), y, grid.cx(m[2]), y, m[2] - m[1] + 1, after);
    }

    /** Re-reads the board and checks every cell we've touched came out right. */
    private void verify() { verify(false); }

    private void verify(boolean second) {
        sinceCheck = 0;
        checks++;
        // Give the game a moment to draw the last fills before looking.
        handler.postDelayed(() -> { if (state == PLAYING) capture(false, false, r -> onVerify(r, second)); }, second ? 700 : 280);
    }

    private void onVerify(NonoVision.Reading r, boolean second) {
        {
            if (state != PLAYING) return;
            if (r.grid == null || r.cells == null) {
                if (planPos >= plan.size()) { done(); return; }
                pause("puzzle disappeared from the screen (" + r.error + ")");
                return;
            }
            if (r.grid.rows != grid.rows || r.grid.cols != grid.cols) { pause("the board changed size — a different puzzle?"); return; }
            int wrong = -1, missing = 0;
            for (int i = 0; i < solution.length; i++) {
                int have = r.cells[i];
                if (solution[i] == NonoSolver.EMPTY && have == NonoVision.FILLED) { wrong = i; break; }
                if (solution[i] == NonoSolver.FILLED && have != NonoVision.FILLED) missing++;
            }
            if (r.redCells > baseRed) {
                showOverlay(grid, solution, r, 6000);
                end("the game marked a mistake (red) — stopped to save your hearts");
                return;
            }
            if (wrong >= 0) {
                showOverlay(grid, solution, r, 6000);
                end("cell row " + (wrong / grid.cols + 1) + ", col " + (wrong % grid.cols + 1) + " got filled by mistake — stopped");
                return;
            }
            // Cells the plan already covered that still aren't filled: swipes may not register in this game.
            int lagging = 0;
            for (int k = 0; k < planPos; k++) {
                int[] m = plan.get(k);
                for (int c = m[1]; c <= m[2]; c++) if (r.cell(m[0], c) != NonoVision.FILLED) lagging++;
            }
            log("{\"event\":\"check\",\"missing\":" + missing + ",\"lagging\":" + lagging + "}");
            if (missing == 0) { done(); return; }
            if (lagging > 0 && !second) { verify(true); return; }   // maybe just slow to draw
            if (lagging > 0) {
                swipeFailures++;
                if (swipeFailures > 3) { pause(lagging + " cells didn't fill after several tries"); return; }
                if (prefs.getBoolean(PREF_SWIPE, true)) setInfo("Some swipes didn't register — switching to taps");
            }
            if (lagging > 0 || planPos >= plan.size()) {
                buildPlan(r);   // re-plan from what is actually on screen
                if (plan.isEmpty()) {
                    if (missing > 0) end(missing + " picture cells are crossed out, so the bot can't fill them");
                    else done();
                    return;
                }
                ensureFillMode(r, 0);
                return;
            }
            handler.postDelayed(this::nextMove, gapMs());
        }
    }

    private void done() {
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        log("{\"event\":\"solved\",\"secs\":" + secs + "}");
        state = IDLE;
        refresh();
        setInfo("✓ Solved in " + secs + " s · " + checks + " checks.");
        toast("Nonogram solved ✓");
        handler.postDelayed(host::nonogramSolved, 1500);
    }

    private void pause(String why) {
        if (state != PLAYING) return;
        state = PAUSED;
        refresh();
        setInfo("Paused: " + why + "\nResume continues from a fresh read.");
        log("{\"event\":\"pause\",\"why\":" + Recorder.q(why) + "}");
    }

    private void resume() {
        state = PLAYING;
        refresh();
        setInfo("Resuming…");
        capture(false, false, r -> {
            if (state != PLAYING) return;
            if (r.grid == null || r.cells == null) { pause("no puzzle on screen"); return; }
            buildPlan(r);
            if (plan.isEmpty()) { done(); return; }
            ensureFillMode(r, 0);
        });
    }

    void end(String why) {
        if (state != IDLE) log("{\"event\":\"end\",\"why\":" + Recorder.q(why) + "}");
        state = IDLE;
        readId++;
        refresh();
        setInfo("Stopped: " + why);
    }

    void shutdown() {
        if (dead) return;
        dead = true;
        state = IDLE;
        handler.removeCallbacksAndMessages(null);
        removeOverlay();
        if (pinger != null) { try { wm.removeView(pinger); } catch (Throwable ignored) { } pinger = null; }
        worker.shutdownNow();
    }

    private void log(String json) {
        if (rec == null) return;
        try {
            rec.line("{\"t\":" + System.currentTimeMillis() + ",\"game\":\"nonogram\"," + json.substring(1));
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- gestures

    private void tap(float x, float y, Runnable after) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatch(new GestureDescription.StrokeDescription(p, 0, 50), after);
    }

    private void swipe(float x0, float y0, float x1, float y1, int cells, Runnable after) {
        Path p = new Path();
        p.moveTo(x0, y0);
        p.lineTo(x1, y1);
        dispatch(new GestureDescription.StrokeDescription(p, 0, 90 + 45L * cells), after);
    }

    private void dispatch(GestureDescription.StrokeDescription stroke, Runnable after) {
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        boolean ok = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { handler.post(after); }
            @Override public void onCancelled(GestureDescription d) {
                handler.post(() -> pause("a tap was cancelled (did you touch the screen?)"));
            }
        }, handler);
        if (!ok) pause("tap rejected — is the tap service still on?");
    }
}
