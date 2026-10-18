package com.nickrocks.pixelflowbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
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

import com.nickrocks.pixelflowbot.amaze.AmazeNext;
import com.nickrocks.pixelflowbot.amaze.AmazeSolver;
import com.nickrocks.pixelflowbot.amaze.AmazeVision;
import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Amaze GO auto-solver (the arrows puzzle): reads every arrow on the board, taps only arrows whose
 * way out is completely clear (which can never cost a water drop), several at once when their
 * paths don't cross, and re-reads the board between rounds. Zooms out big boards so the whole
 * puzzle is visible, stops instantly if a drop is lost, and moves on to the next level.
 */
final class AmazeMode {
    static final String TAG = "PFBot.Amaze";
    private static final String PREF_SPEED = "amaze_speed";
    private static final int IDLE = 0, PLAYING = 1, PAUSED = 2;
    private static final int MAX_UNSTABLE = 16;

    private final TapService svc;
    private final CandyMode host;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AmazeVision vision = new AmazeVision();
    private final WindowManager wm;

    private final LinearLayout box;
    private final TextView info;
    private final Button startButton, pauseButton, speedButton;
    private View pinger, overlay;
    private boolean pingFlip;

    private int state = IDLE;
    private boolean dead, readBusy, arranged;
    private int readId;
    private String gamePkg;
    private Recorder rec;

    // Per level
    private String prevSig;
    private int unstable, zoomTries, noBoard, noEffect, maxDrops = -1, dropDips;
    private int cleared, rounds, lastCount = -1;
    private Set<Integer> lastBatchHeads = new HashSet<>();
    private long startedAt;

    AmazeMode(TapService svc, CandyMode host) {
        this.svc = svc;
        this.host = host;
        this.prefs = svc.getSharedPreferences(TapService.PREFS, 0);
        this.wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);

        box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row1 = new LinearLayout(svc);
        startButton = host.button("▶ Start", Ui.ACCENT, v -> start());
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
        info.setText("Amaze GO: open a level, then Start. Read = see which arrows can leave.");
        box.addView(info);

        LinearLayout row2 = new LinearLayout(svc);
        row2.addView(host.button("Read", Ui.INFO, v -> readOnce()));
        speedButton = host.button("", Ui.MUTED, v -> {
            prefs.edit().putInt(PREF_SPEED, (prefs.getInt(PREF_SPEED, 1) + 1) % 3).apply();
            refresh();
        });
        row2.addView(speedButton);
        row2.addView(host.button("Zoom out", Ui.MUTED, v -> manualZoom()));
        box.addView(row2);
        refresh();
    }

    View box() { return box; }

    boolean busy() { return state != IDLE || readBusy; }

    void note(String s) { setInfo(s); }

    // ---------------------------------------------------------------- UI

    private void refresh() {
        startButton.setText(state == IDLE ? "▶ Start" : state == PAUSED ? "▶ Resume" : "▶ Playing");
        pauseButton.setEnabled(state == PLAYING);
        pauseButton.setAlpha(state == PLAYING ? 1f : 0.4f);
        speedButton.setText(new String[]{"Speed: careful", "Speed: normal", "Speed: turbo"}[prefs.getInt(PREF_SPEED, 1)]);
    }

    private void setInfo(String s) {
        String head = "";
        if (state == PLAYING) head = "AMAZE · cleared " + cleared + (lastCount >= 0 ? " · left " + lastCount : "") + "\n";
        else if (state == PAUSED) head = "AMAZE PAUSED · cleared " + cleared + "\n";
        info.setText(head + s);
    }

    void setCompact(boolean on) {
        host.setCompact(on);
        View row2 = box.getChildAt(2);
        if (row2 != null) row2.setVisibility(on ? View.GONE : View.VISIBLE);
        info.setMaxLines(on ? 2 : 12);
    }

    private int batchMax() { return new int[]{1, 4, 10}[prefs.getInt(PREF_SPEED, 1)]; }
    private long tapGap() { return new long[]{350, 140, 70}[prefs.getInt(PREF_SPEED, 1)]; }
    private long settle() { return new long[]{900, 550, 380}[prefs.getInt(PREF_SPEED, 1)]; }

    // ---------------------------------------------------------------- capture

    private interface Sink { void accept(AmazeVision.Board b, Frame f); }

    /** Hides the panel (so it can't cover arrows or the drops), takes a fresh frame and reads it. */
    private void capture(Sink sink) {
        final int id = ++readId;
        final boolean[] done = {false};
        final View panel = host.panelView();
        if (panel != null) panel.setVisibility(View.INVISIBLE);
        handler.postDelayed(() -> {
            if (id != readId || dead) return;
            CaptureService.requestFrame(frame -> worker.execute(() -> {
                AmazeVision.Board b = vision.read(frame);
                handler.post(() -> {
                    if (id != readId || done[0]) return;
                    done[0] = true;
                    if (panel != null) panel.setVisibility(View.VISIBLE);
                    sink.accept(b, frame);
                });
            }));
            ping();
        }, 160);
        handler.postDelayed(() -> {
            if (id != readId || done[0]) return;
            done[0] = true;
            CaptureService.requestFrame(null);
            if (panel != null) panel.setVisibility(View.VISIBLE);
            AmazeVision.Board b = new AmazeVision.Board();
            b.error = "no new frame from the screen capture";
            sink.accept(b, null);
        }, 2500);
    }

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
        if (!CaptureService.isRunning()) { host.toast("Capture is off. Start it from the app first."); return; }
        readBusy = true;
        setInfo("Reading…");
        capture((b, f) -> {
            readBusy = false;
            if (b.error != null) {
                setInfo("Can't read the board: " + b.error + (b.cutOff ? "\nThe board runs off the screen — press Zoom out." : ""));
                if (f != null && !b.arrows.isEmpty()) CaptureService.saveFrame(f);
                return;
            }
            int free = AmazeSolver.freeArrows(b).size();
            int stuck = AmazeSolver.stuckAfterClearing(b);
            setInfo(b.summary() + " · " + b.ms + " ms\n" + free + " can leave now"
                    + (stuck == 0 ? " · whole board clears ✓" : " · " + stuck + " would stay stuck (misread?)")
                    + (b.cutOff ? "\nBoard runs off the screen — Start will zoom out first." : ""));
            showOverlay(b, 4000);
        });
    }

    /** Draws the bot's view: arrows that can leave in green, blocked ones in red. */
    private void showOverlay(AmazeVision.Board b, long ms) {
        removeOverlay();
        final Paint ok = new Paint(Paint.ANTI_ALIAS_FLAG);
        ok.setColor(0xDD22D3EE);
        ok.setStrokeWidth(Math.max(4, b.pitch / 5));
        ok.setStrokeCap(Paint.Cap.ROUND);
        final Paint no = new Paint(ok);
        no.setColor(0x88FF4D6D);
        final List<AmazeVision.Arrow> free = AmazeSolver.freeArrows(b);
        final Set<Integer> freeIds = new HashSet<>();
        for (AmazeVision.Arrow a : free) freeIds.add(a.id);
        View v = new View(svc) {
            @Override protected void onDraw(Canvas c) {
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                for (AmazeVision.Arrow a : b.arrows) {
                    Paint p = freeIds.contains(a.id) ? ok : no;
                    for (int k = 1; k < a.cells.length; k++) {
                        c.drawLine(b.px(a.cells[k - 1]) - loc[0], b.py(a.cells[k - 1]) - loc[1],
                                b.px(a.cells[k]) - loc[0], b.py(a.cells[k]) - loc[1], p);
                    }
                    c.drawCircle(b.px(a.head()) - loc[0], b.py(a.head()) - loc[1], Math.max(6, b.pitch / 4), p);
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

    void autoStart() { start(); }

    private void start() {
        if (state == PAUSED) { resume(); return; }
        if (state != IDLE || readBusy) return;
        if (!CaptureService.isRunning()) { host.toast("Capture is off. Start it from the app first."); return; }
        String pkg = host.foregroundPackage();
        if (pkg == null || pkg.equals("?") || pkg.equals(svc.getPackageName())) {
            host.toast("Open an Amaze GO level first, then press Start.");
            return;
        }
        gamePkg = pkg;
        removeOverlay();
        state = PLAYING;
        resetLevel();
        startedAt = System.currentTimeMillis();
        try { rec = new Recorder(svc); } catch (Throwable t) { rec = null; }
        log("{\"event\":\"start\",\"pkg\":" + Recorder.q(pkg) + "}");
        setCompact(true);
        refresh();
        setInfo("Reading the board…");
        handler.postDelayed(this::loop, 200);
    }

    private void resetLevel() {
        prevSig = null;
        unstable = zoomTries = noBoard = noEffect = dropDips = cleared = rounds = 0;
        maxDrops = -1;
        lastCount = -1;
        arranged = false;
        lastBatchHeads.clear();
    }

    private void resume() {
        state = PLAYING;
        prevSig = null;
        unstable = noBoard = noEffect = dropDips = 0;
        refresh();
        setCompact(true);
        setInfo("Resuming…");
        handler.postDelayed(this::loop, 200);
    }

    private void loop() {
        if (state != PLAYING || dead) return;
        if (!CaptureService.isRunning()) { end("screen capture stopped"); return; }
        String pkg = host.foregroundPackage();
        if (gamePkg != null && pkg != null && !pkg.equals("?") && !pkg.equals(gamePkg) && !pkg.equals(svc.getPackageName())) {
            pause("the game isn't in front (" + pkg + ")");
            return;
        }
        capture(this::onRead);
    }

    private void again(long ms) { handler.postDelayed(this::loop, ms); }

    private void onRead(AmazeVision.Board b, Frame f) {
        if (state != PLAYING) return;

        // Lives: the highest count seen is the baseline; a lower count twice in a row means a drop was lost.
        if (b.lives > 0) {
            if (rounds == 0 && b.lives > maxDrops) maxDrops = b.lives;
            else if (maxDrops > 0 && b.lives < maxDrops) {
                if (++dropDips >= 2) {
                    log("{\"event\":\"lost_drop\",\"drops\":" + b.lives + ",\"was\":" + maxDrops + "}");
                    if (f != null) CaptureService.saveFrame(f);
                    end("a water drop was lost — stopped so you don't lose more (screenshot saved)");
                    return;
                }
            } else dropDips = 0;
        }

        // The board is gone: either the level is complete or something covered it.
        boolean noInk = b.arrows.isEmpty() && b.error != null && b.error.startsWith("no arrows");
        if (noInk || (f != null && AmazeNext.find(f) != null)) {
            if (++noBoard >= 2) {
                if (cleared > 0 && lastCount >= 0 && lastCount <= lastBatchHeads.size()) { done(); return; }
                if (cleared > 0 && f != null && AmazeNext.find(f) != null) { done(); return; }
                pause("can't see the board (a popup or another screen?)");
                return;
            }
            again(400);
            return;
        }
        noBoard = 0;

        if (b.cutOff) {
            if (zoomTries < 4) {
                zoomTries++;
                setInfo("The board runs off the screen — zooming out (" + zoomTries + ")…");
                log("{\"event\":\"zoom_out\",\"try\":" + zoomTries + "}");
                zoomOut(b, f, () -> { prevSig = null; again(900); });
                return;
            }
            pause("the board still doesn't fit after zooming out — pinch it smaller by hand, then Resume");
            return;
        }

        if (b.error != null || (b.arrows.size() > 0 && AmazeSolver.stuckAfterClearing(b) > 0)) {
            // Right after the last arrows left, the celebration card shows before its button does.
            if (cleared > 0 && lastCount >= 0 && lastCount <= lastBatchHeads.size() && ++noBoard >= 3) { done(); return; }
            if (++unstable >= MAX_UNSTABLE) {
                log("{\"event\":\"unreadable\",\"why\":" + Recorder.q(String.valueOf(b.error)) + "}");
                if (f != null) CaptureService.saveFrame(f);
                pause("can't read the board reliably (" + (b.error != null ? b.error : "the arrows as read can't all leave") + "). Screenshot saved.");
                return;
            }
            setInfo("Reading… (" + (b.error != null ? b.error : "board still moving") + ")");
            prevSig = null;
            again(300);
            return;
        }

        // Act only on a still board: the same arrows on two reads in a row.
        String sig = signature(b);
        if (!sig.equals(prevSig)) {
            prevSig = sig;
            again(220);
            return;
        }
        unstable = 0;

        if (!arranged) {
            arranged = true;
            if (panelOver(b)) { arrangePanel(b); return; }
        }

        // Did the last round do anything?
        if (!lastBatchHeads.isEmpty()) {
            int stillThere = 0;
            for (AmazeVision.Arrow a : b.arrows) if (lastBatchHeads.contains(key(b, a))) stillThere++;
            if (stillThere == lastBatchHeads.size()) {
                if (++noEffect >= 3) { pause("taps aren't removing arrows — is something covering the game?"); return; }
            } else noEffect = 0;
            cleared += Math.max(0, (lastCount >= 0 ? lastCount : b.arrows.size()) - b.arrows.size());
        }
        lastCount = b.arrows.size();

        // Choose arrows whose way out is completely clear and whose tap point isn't under the panel.
        List<AmazeVision.Arrow> batch = AmazeSolver.batch(b, batchMax() * 3);
        int[] pr = host.panelRect();
        java.util.Iterator<AmazeVision.Arrow> it = batch.iterator();
        while (it.hasNext()) {
            float[] p = AmazeSolver.tapPoint(b, it.next());
            if (pr != null && p[0] >= pr[0] - 8 && p[0] <= pr[2] + 8 && p[1] >= pr[1] - 8 && p[1] <= pr[3] + 8) it.remove();
        }
        while (batch.size() > batchMax()) batch.remove(batch.size() - 1);
        if (batch.isEmpty()) {
            if (AmazeSolver.freeArrows(b).isEmpty()) pause("no arrow has a clear way out — misread? (press Read to check)");
            else pause("the free arrows are under the panel — drag the panel away, then Resume");
            return;
        }
        rounds++;
        lastBatchHeads.clear();
        StringBuilder sb = new StringBuilder();
        for (AmazeVision.Arrow a : batch) { lastBatchHeads.add(key(b, a)); sb.append(a.cells.length).append(' '); }
        log("{\"event\":\"round\",\"n\":" + rounds + ",\"arrows\":" + b.arrows.size() + ",\"tap\":" + batch.size()
                + ",\"drops\":" + b.lives + "}");
        setInfo(b.arrows.size() + " arrows left · tapping " + batch.size() + " with a clear way out");
        tapAll(b, batch, 0);
    }

    /** Identifies an arrow by its head position and direction (stable while it sits still). */
    private static int key(AmazeVision.Board b, AmazeVision.Arrow a) {
        int c = Math.round(b.px(a.head())), r = Math.round(b.py(a.head()));
        return ((c / 4) * 4000 + (r / 4)) * 4 + a.dir;
    }

    private static String signature(AmazeVision.Board b) {
        StringBuilder sb = new StringBuilder().append(b.arrows.size()).append('|');
        int[] keys = new int[b.arrows.size()];
        for (int i = 0; i < keys.length; i++) keys[i] = key(b, b.arrows.get(i)) ^ (b.arrows.get(i).cells.length << 24);
        java.util.Arrays.sort(keys);
        for (int k : keys) sb.append(k).append(',');
        return sb.toString();
    }

    private void tapAll(AmazeVision.Board b, List<AmazeVision.Arrow> batch, int i) {
        if (state != PLAYING) return;
        if (i >= batch.size()) { prevSig = null; again(settle()); return; }
        float[] p = AmazeSolver.tapPoint(b, batch.get(i));
        tap(p[0], p[1], () -> handler.postDelayed(() -> tapAll(b, batch, i + 1), tapGap()));
    }

    // ---------------------------------------------------------------- panel & zoom

    private boolean panelOver(AmazeVision.Board b) {
        int[] pr = host.panelRect();
        if (pr == null) return false;
        int pad = Math.round(b.pitch * 0.6f);
        return pr[0] < b.right + pad && pr[2] > b.left - pad && pr[1] < b.bottom + pad && pr[3] > b.top - pad;
    }

    /** Parks the compact panel above or below the board, wherever it fits. */
    private void arrangePanel(AmazeVision.Board b) {
        View panel = host.panelView();
        int h = panel != null ? panel.getHeight() : 0;
        int screenH = b.frameH > 0 ? b.frameH : svc.getResources().getDisplayMetrics().heightPixels;
        int gap = host.dp(8);
        int pad = Math.round(b.pitch * 0.6f);
        if (b.bottom + pad + gap + h <= screenH - gap) host.movePanel(b.bottom + pad + gap);
        else if (b.top - pad - gap - h >= 0) host.movePanel(b.top - pad - gap - h);
        else host.movePanel(host.dp(4));   // over the header: fine, reads hide the panel
        handler.postDelayed(() -> { if (state == PLAYING) { prevSig = null; loop(); } }, 300);
    }

    private void manualZoom() {
        if (state == PLAYING || readBusy) return;
        if (!CaptureService.isRunning()) { host.toast("Capture is off. Start it from the app first."); return; }
        readBusy = true;
        capture((b, f) -> {
            readBusy = false;
            zoomOut(b, f, () -> setInfo("Zoomed out. Press Read to check the board."));
        });
    }

    /** Two-finger pinch toward the board centre, starting on empty background so nothing is tapped. */
    private void zoomOut(AmazeVision.Board b, Frame f, Runnable after) {
        int w = f != null ? f.w : svc.getResources().getDisplayMetrics().widthPixels;
        int h = f != null ? f.h : svc.getResources().getDisplayMetrics().heightPixels;
        float cx = w / 2f;
        float cy = b.bottom > b.top ? (b.top + b.bottom) / 2f : h * 0.55f;
        cy = Math.max(h * 0.3f, Math.min(h * 0.85f, cy));
        float[] a0 = clearSpot(f, cx - w * 0.36f, cy - h * 0.12f);
        float[] b0 = clearSpot(f, cx + w * 0.36f, cy + h * 0.12f);
        Path p1 = new Path(); p1.moveTo(a0[0], a0[1]); p1.lineTo(cx - w * 0.05f, cy - h * 0.02f);
        Path p2 = new Path(); p2.moveTo(b0[0], b0[1]); p2.lineTo(cx + w * 0.05f, cy + h * 0.02f);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p1, 0, 450))
                .addStroke(new GestureDescription.StrokeDescription(p2, 0, 450))
                .build();
        svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { handler.post(after); }
            @Override public void onCancelled(GestureDescription d) { handler.post(after); }
        }, handler);
    }

    /** Nearest spot (searching outward) with no arrow ink within ~24 px, so a finger landing there taps nothing. */
    private static float[] clearSpot(Frame f, float x, float y) {
        if (f == null) return new float[]{x, y};
        for (int r = 0; r < 200; r += 12) {
            for (int k = 0; k < 8; k++) {
                double ang = k * Math.PI / 4;
                float xx = (float) (x + Math.cos(ang) * r), yy = (float) (y + Math.sin(ang) * r);
                if (clean(f, xx, yy, 24)) return new float[]{xx, yy};
                if (r == 0) break;
            }
        }
        return new float[]{x, y};
    }

    private static boolean clean(Frame f, float x, float y, int rad) {
        int cx = Math.round(x), cy = Math.round(y);
        if (cx < rad || cy < rad || cx >= f.w - rad || cy >= f.h - rad) return false;
        for (int dy = -rad; dy <= rad; dy += 3) {
            for (int dx = -rad; dx <= rad; dx += 3) {
                int p = f.px[(cy + dy) * f.w + cx + dx];
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                if (r < 200 || g < 180) return false;   // anything darker than the cream background
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- end states

    private void done() {
        long secs = (System.currentTimeMillis() - startedAt) / 1000;
        log("{\"event\":\"solved\",\"secs\":" + secs + ",\"cleared\":" + cleared + ",\"rounds\":" + rounds + "}");
        state = IDLE;
        refresh();
        setInfo("✓ Level cleared in " + secs + " s (" + rounds + " rounds).");
        host.toast("Amaze GO level cleared ✓");
        handler.postDelayed(host::amazeSolved, 1200);
    }

    void parkForNextLevel() {
        setCompact(true);
        host.movePanel(host.dp(4));
    }

    private void pause(String why) {
        if (state != PLAYING) return;
        state = PAUSED;
        refresh();
        setInfo("Paused: " + why);
        log("{\"event\":\"pause\",\"why\":" + Recorder.q(why) + "}");
    }

    void end(String why) {
        if (state != IDLE) log("{\"event\":\"end\",\"why\":" + Recorder.q(why) + "}");
        state = IDLE;
        readId++;
        setCompact(false);
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
            rec.line("{\"t\":" + System.currentTimeMillis() + ",\"game\":\"amaze\"," + json.substring(1));
        } catch (Throwable ignored) { }
    }

    private void tap(float x, float y, Runnable after) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build();
        boolean ok = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { handler.post(after); }
            @Override public void onCancelled(GestureDescription d) { handler.post(() -> pause("a tap was cancelled (did you touch the screen?)")); }
        }, handler);
        if (!ok) pause("tap rejected — is the tap service still on?");
    }
}
