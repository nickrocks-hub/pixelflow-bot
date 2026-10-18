package com.nickrocks.pixelflowbot;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nickrocks.pixelflowbot.core.GameAction;
import com.nickrocks.pixelflowbot.core.arrows.ArrowsBoard;
import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating-panel player for arrows-escape puzzles (com.oakever.arrows): read the board, tap an
 * arrow whose path to the edge is clear, wait for it to leave, read again — until the board is
 * empty, then the taught next-level button. Exact rules, no AI model needed.
 */
final class ArrowsMode {
    static final String GAME = "arrows";
    private static final long SETTLE_MS = 650, RETRY_MS = 450;
    private static final int MAX_RETRIES = 6;

    private final TapService svc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final GestureExecutor exec;
    private final LinearLayout box;
    private final TextView info;
    private final Button startBtn, pauseBtn;

    private boolean running, dead;
    private int taps, retries, emptyReads, sameTarget, levels;
    private String lastTarget = "";
    /** The lattice measured on this level's full board; reused as arrows disappear. */
    private ArrowsBoard lattice;

    ArrowsMode(TapService svc) {
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
        note("Arrows puzzle player: taps arrows that have a clear path off the board, one at a time. Press ▶ Solve on a level.");
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
        grab(f -> {
            ArrowsBoard b = ArrowsBoard.read(f.px, f.w, f.h);
            List<ArrowsBoard.Arrow> plan = b.plan();
            handler.post(() -> note("Board: " + b.summary() + (b.problem == null
                    ? String.format(Locale.US, "\nPlan from this reading clears %d of %d.", plan.size(), b.arrows.size()) : "")));
        });
    }

    private void start() {
        if (running || !ready()) return;
        running = true;
        taps = retries = emptyReads = sameTarget = 0;
        lastTarget = "";
        lattice = null;
        svc.setCompact(true);
        refresh();
        note("Reading the board…");
        step();
    }

    void stop(String why) {
        boolean was = running;
        running = false;
        svc.setCompact(false);
        refresh();
        if (was) note("Stopped: " + why + String.format(Locale.US, "\n%d arrows tapped this run.", taps));
    }

    private void step() {
        if (!running || dead) return;
        grab(f -> {
            ArrowsBoard b = readBoard(f);
            handler.post(() -> decide(b, f));
        });
    }

    private ArrowsBoard readBoard(Frame f) {
        ArrowsBoard b = lattice != null ? ArrowsBoard.read(f.px, f.w, f.h, lattice) : null;
        if (b == null || (!b.consistent() && !"no arrows found".equals(b.problem))) {
            ArrowsBoard fresh = ArrowsBoard.read(f.px, f.w, f.h);
            if (b == null || fresh.consistent()) b = fresh;
        }
        if (b.consistent()) lattice = b;
        return b;
    }

    private void decide(ArrowsBoard b, Frame f) {
        if (!running) return;
        String fg = svc.foregroundPackage();
        if (fg != null && !fg.equals("?") && !fg.equals(Adapters_PKG) && !fg.equals(svc.getPackageName())) { stop("the game isn't in front (" + fg + ")"); return; }
        if ("no arrows found".equals(b.problem) || "no board found".equals(b.problem)) {
            // Empty board: level cleared (twice in a row, so a fade-out doesn't fool it).
            if (++emptyReads < 2 || taps == 0) {
                if (taps == 0 && emptyReads >= MAX_RETRIES) { stop("no arrows on screen — is a level showing?"); return; }
                handler.postDelayed(this::step, 700);
                return;
            }
            levelDone();
            return;
        }
        emptyReads = 0;
        List<ArrowsBoard.Arrow> free = b.free();
        if (!b.consistent()) {
            // Mid-animation or a misread: look again before giving up.
            if (++retries > MAX_RETRIES) { stop("can't read the board cleanly: " + b.summary()); return; }
            note("Board: " + b.summary() + "\nLooking again…");
            handler.postDelayed(this::step, RETRY_MS);
            return;
        }
        retries = 0;
        ArrowsBoard.Arrow a = free.get(0);
        String key = a.toString();
        sameTarget = key.equals(lastTarget) ? sameTarget + 1 : 0;
        lastTarget = key;
        if (sameTarget >= 3) { stop("tapped the same arrow 3 times and it didn't move — the game's rules may differ (" + key + ")"); return; }
        keepPanelOff(b);
        note(String.format(Locale.US, "Board: %s\nTapping %s (tap %d)", b.summary(), key, taps + 1));
        GameAction act = GameAction.tap(a.tapX, a.tapY, "arrow " + key);
        act.source = "arrows solver";
        exec.execute(act, (ok, latency, n) -> handler.post(() -> {
            if (!ok) { stop("tap failed: " + n); return; }
            taps++;
            handler.postDelayed(this::step, SETTLE_MS);
        }));
    }

    private void levelDone() {
        levels++;
        AutoNext next = svc.autoNext();
        if (next == null || !next.enabled(GAME)) { stop(String.format(Locale.US, "level cleared (%d taps) 🎉", taps)); return; }
        note(String.format(Locale.US, "Level cleared in %d taps — next level…", taps));
        next.run(GAME, null, fr -> ArrowsBoard.read(fr.px, fr.w, fr.h).consistent(), 20_000, this::note,
                () -> { taps = 0; emptyReads = 0; retries = 0; lattice = null; handler.postDelayed(this::step, 800); },
                why -> stop("level cleared; " + why));
    }

    /** The panel must not cover the board, or taps would land on it. */
    private void keepPanelOff(ArrowsBoard b) {
        int[] pr = svc.panelRect();
        if (pr == null) return;
        int top = Math.round(b.y0 - b.s), bottom = Math.round(b.y0 + b.rows * b.s);
        if (pr[3] <= top || pr[1] >= bottom) return;
        int h = pr[3] - pr[1];
        int screenH = svc.getResources().getDisplayMetrics().heightPixels;
        if (bottom + h + 10 < screenH) svc.movePanel(bottom + 10);
        else if (top - h - 10 > 0) svc.movePanel(top - h - 10);
    }

    // ------------------------------------------------------------------ screen

    private interface Got { void frame(Frame f); }

    /** A fresh frame with the panel hidden. */
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
            stop("no new frame from the screen capture (is it still on?)");
        }, 3000);
    }

    private static final String Adapters_PKG = com.nickrocks.pixelflowbot.core.Adapters.ARROWS.packageName;

    void shutdown() {
        dead = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }
}
