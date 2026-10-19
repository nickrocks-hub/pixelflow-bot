package com.nickrocks.pixelflowbot;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nickrocks.pixelflowbot.core.GameAction;
import com.nickrocks.pixelflowbot.core.flow.FlowBoard;
import com.nickrocks.pixelflowbot.core.flow.FlowSolver;
import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating-panel player for Flow Free (com.bigduckgames.flow): read the grid and dots, solve the
 * whole puzzle exactly, then draw each pipe with one drag from dot to dot through the cell centres;
 * on a solved level, the taught next-level button. No AI model needed.
 */
final class FlowMode {
    static final String GAME = "flow";
    private static final long SOLVE_LIMIT_MS = 20_000;

    private final TapService svc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final GestureExecutor exec;
    private final LinearLayout box;
    private final TextView info;
    private final Button startBtn, pauseBtn;
    private boolean running, dead;
    private int levels, readTries;

    FlowMode(TapService svc) {
        this.svc = svc;
        this.exec = new GestureExecutor(svc, handler);
        box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(svc);
        startBtn = svc.button("▶ Solve", Ui.ACCENT, v -> start());
        pauseBtn = svc.button("⏸ Pause", Ui.MUTED, v -> stop("paused by you"));
        r1.addView(startBtn);
        r1.addView(pauseBtn);
        r1.addView(svc.button("■ STOP", Ui.DANGER, v -> { stop("stopped by you"); TapService.stopEverything(); }));
        box.addView(r1);
        LinearLayout r2 = new LinearLayout(svc);
        r2.addView(svc.button("🔍 Read board", Ui.INFO, v -> readOnce()));
        r2.addView(svc.button("📸 Save screen", Ui.MUTED, v -> {
            if (CaptureService.isRunning()) CaptureService.requestSaveFrame(); else svc.toast("Screen capture is off.");
        }));
        box.addView(r2);
        info = new TextView(svc);
        info.setTextColor(Ui.TEXT_DIM);
        info.setTextSize(11);
        info.setMaxWidth(svc.dp(300));
        info.setPadding(svc.dp(4), svc.dp(4), 0, 0);
        box.addView(info);
        note("Flow Free player: reads the dots, solves the puzzle exactly and draws every pipe. Press ▶ Solve on a level.");
        refresh();
    }

    View box() { return box; }
    boolean busy() { return running; }
    void note(String s) { info.setText(s); }

    private void refresh() {
        startBtn.setText(running ? "▶ Solving" : "▶ Solve");
        pauseBtn.setEnabled(running);
        pauseBtn.setAlpha(running ? 1f : 0.4f);
    }

    private boolean ready() {
        if (!CaptureService.isRunning()) { note("Screen capture is off — start it in the app (Launch & play does it for you)."); return false; }
        if (!TapService.isRunning()) { note("The tap service is off."); return false; }
        return true;
    }

    private void readOnce() {
        if (running || !ready()) return;
        note("Reading…");
        grab(f -> {
            FlowBoard b = FlowBoard.read(f.px, f.w, f.h);
            String s = "Board: " + b.summary();
            if (b.problem == null) {
                FlowSolver.Result r = FlowSolver.solve(b.rows, b.cols, b.pairs, SOLVE_LIMIT_MS);
                s += r.solved ? String.format(Locale.US, "\nSolvable — found the solution in %.1f s.", r.ms / 1000.0) : "\nSolver: " + r.problem;
            }
            final String m = s;
            handler.post(() -> note(m));
        });
    }

    private void start() {
        if (running || !ready()) return;
        running = true;
        readTries = 0;
        svc.setCompact(true);
        refresh();
        play();
    }

    void stop(String why) {
        boolean was = running;
        running = false;
        svc.setCompact(false);
        refresh();
        if (was) note(why);
    }

    private void play() {
        if (!running || dead) return;
        note("Reading the board…");
        grab(f -> {
            FlowBoard b = FlowBoard.read(f.px, f.w, f.h);
            if (b.problem != null) { handler.post(() -> retryOrStop("can't read the board: " + b.summary())); return; }
            handler.post(() -> note("Board: " + b.summary() + "\nSolving…"));
            FlowSolver.Result r = FlowSolver.solve(b.rows, b.cols, b.pairs, SOLVE_LIMIT_MS);
            handler.post(() -> {
                if (!running) return;
                if (!r.solved) { retryOrStop("solver: " + r.problem + " (" + b.summary() + ")"); return; }
                readTries = 0;
                String fg = svc.foregroundPackage();
                String pkg = com.nickrocks.pixelflowbot.core.Adapters.FLOW.packageName;
                if (fg != null && !fg.equals("?") && !fg.equals(pkg) && !fg.equals(svc.getPackageName())) { stop("the game isn't in front (" + fg + ")"); return; }
                keepPanelOff(b);
                handler.postDelayed(() -> draw(b, r.paths, 0), 250);
            });
        });
    }

    private void retryOrStop(String why) {
        if (!running) return;
        if (++readTries >= 3) { stop("Stopped: " + why); return; }
        note(why + "\nLooking again…");
        handler.postDelayed(this::play, 900);
    }

    /** Draws pipe i, then the next. */
    private void draw(FlowBoard b, List<int[]> paths, int i) {
        if (!running) return;
        if (i >= paths.size()) {
            note(String.format(Locale.US, "Drew all %d pipes on the %d×%d board.", paths.size(), b.cols, b.rows));
            handler.postDelayed(this::levelDone, 1200);
            return;
        }
        int[] p = paths.get(i);
        float[] xy = new float[p.length * 2];
        for (int k = 0; k < p.length; k++) { xy[2 * k] = b.cx(p[k]); xy[2 * k + 1] = b.cy(p[k]); }
        long ms = Math.max(250, Math.min(8000, 70L * p.length));
        note(String.format(Locale.US, "Board: %s\nDrawing pipe %d of %d (%d cells)", b.summary(), i + 1, paths.size(), p.length));
        GameAction a = GameAction.drag(xy, ms, "pipe " + (i + 1));
        a.source = "flow solver";
        exec.execute(a, (ok, latency, n) -> handler.post(() -> {
            if (!ok) { stop("Stopped: a drag failed (" + n + ")"); return; }
            handler.postDelayed(() -> draw(b, paths, i + 1), 120);
        }));
    }

    private void levelDone() {
        if (!running) return;
        levels++;
        AutoNext next = svc.autoNext();
        if (next == null || !next.enabled(GAME)) { stop("Solved 🎉 (" + levels + " level" + (levels == 1 ? "" : "s") + " this session)"); return; }
        next.run(GAME, null, fr -> FlowBoard.read(fr.px, fr.w, fr.h).problem == null, 20_000, this::note,
                () -> handler.postDelayed(this::play, 700),
                why -> stop("Solved 🎉 — " + why));
    }

    /** The panel must not cover the board, or the drags would land on it. */
    private void keepPanelOff(FlowBoard b) {
        int[] pr = svc.panelRect();
        if (pr == null) return;
        int top = Math.round(b.top()), bottom = Math.round(b.bottom());
        if (pr[3] <= top || pr[1] >= bottom) return;
        int h = pr[3] - pr[1];
        int screenH = svc.getResources().getDisplayMetrics().heightPixels;
        if (top - h - 10 > 0) svc.movePanel(top - h - 10);
        else if (bottom + h + 10 < screenH) svc.movePanel(bottom + 10);
    }

    // ------------------------------------------------------------------ screen

    private interface Got { void frame(Frame f); }

    /** A fresh frame with the panel hidden; the callback runs on the worker thread. */
    private void grab(Got cb) {
        final View panel = svc.panelView();
        if (panel != null) panel.setVisibility(View.INVISIBLE);
        final boolean[] done = {false};
        handler.postDelayed(() -> CaptureService.requestFrame(f -> handler.post(() -> {
            if (done[0]) return;
            done[0] = true;
            if (panel != null) panel.setVisibility(View.VISIBLE);
            worker.execute(() -> cb.frame(f));
        })), 150);
        handler.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            CaptureService.requestFrame(null);
            if (panel != null) panel.setVisibility(View.VISIBLE);
            stop("Stopped: no new frame from the screen capture (is it still on?)");
        }, 3000);
    }

    void shutdown() {
        dead = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }
}
