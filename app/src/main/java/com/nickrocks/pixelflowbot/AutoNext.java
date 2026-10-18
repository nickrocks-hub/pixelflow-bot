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
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Goes from a finished level to the next one, for any game.
 *
 * Each game has a list of "steps" (buttons to press in order, e.g. Continue → Play). A step is
 * either built in (Nonogram's Next Level button) or taught by the user: they tap the button once
 * and the bot remembers its position plus a small picture of it. Later it only taps that spot
 * while the same picture is on screen again, held still for two looks in a row, so ads, offers,
 * fail screens and popups (which look different) never get tapped.
 */
final class AutoNext {
    private static final String TAG = "PFBot.Next";
    private static final int GW = 18, GH = 6;           // fingerprint grid
    private static final float PATCH_W = 0.36f, PATCH_H = 0.06f;
    private static final long STEP_TIMEOUT_MS = 45_000;
    private static final long POLL_MS = 700;

    /** A screen spot to tap, with what it should look like. */
    static final class Step {
        float rx, ry;          // tap point, as a fraction of the screen
        int[] look;            // GWxGH mean RGB of the patch around it

        String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append(rx).append(',').append(ry);
            for (int v : look) sb.append(',').append(v);
            return sb.toString();
        }

        static Step decode(String s) {
            try {
                String[] p = s.split(",");
                Step st = new Step();
                st.rx = Float.parseFloat(p[0]);
                st.ry = Float.parseFloat(p[1]);
                st.look = new int[p.length - 2];
                for (int i = 2; i < p.length; i++) st.look[i - 2] = Integer.parseInt(p[i]);
                return st.look.length == GW * GH * 3 ? st : null;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private final TapService svc;
    private final CandyMode host;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final WindowManager wm;
    private View pinger, teachOverlay;
    private boolean pingFlip;
    private int runId;
    private boolean running;

    AutoNext(TapService svc, CandyMode host) {
        this.svc = svc;
        this.host = host;
        this.prefs = svc.getSharedPreferences(TapService.PREFS, 0);
        this.wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);
    }

    // ------------------------------------------------------------------ settings

    boolean enabled(String game) { return prefs.getBoolean("next_on_" + game, true); }

    void setEnabled(String game, boolean on) { prefs.edit().putBoolean("next_on_" + game, on).apply(); }

    List<Step> steps(String game) {
        List<Step> out = new ArrayList<>();
        String s = prefs.getString("next_steps_" + game, "");
        if (s.isEmpty()) return out;
        for (String part : s.split(";")) {
            Step st = Step.decode(part);
            if (st != null) out.add(st);
        }
        return out;
    }

    private void saveSteps(String game, List<Step> steps) {
        StringBuilder sb = new StringBuilder();
        for (Step st : steps) { if (sb.length() > 0) sb.append(';'); sb.append(st.encode()); }
        prefs.edit().putString("next_steps_" + game, sb.toString()).apply();
    }

    void clearSteps(String game) { prefs.edit().remove("next_steps_" + game).apply(); }

    boolean busy() { return running || teachOverlay != null; }

    // ------------------------------------------------------------------ fingerprints

    static int[] look(Frame f, float cx, float cy) {
        int[] out = new int[GW * GH * 3];
        float pw = f.w * PATCH_W, ph = f.h * PATCH_H;
        float x0 = cx - pw / 2, y0 = cy - ph / 2;
        for (int gy = 0; gy < GH; gy++) {
            for (int gx = 0; gx < GW; gx++) {
                long r = 0, g = 0, b = 0;
                int n = 0;
                int sx0 = Math.round(x0 + gx * pw / GW), sx1 = Math.round(x0 + (gx + 1) * pw / GW);
                int sy0 = Math.round(y0 + gy * ph / GH), sy1 = Math.round(y0 + (gy + 1) * ph / GH);
                for (int y = Math.max(0, sy0); y < Math.min(f.h, sy1); y++) {
                    for (int x = Math.max(0, sx0); x < Math.min(f.w, sx1); x++) {
                        int p = f.px[y * f.w + x];
                        r += (p >> 16) & 0xff; g += (p >> 8) & 0xff; b += p & 0xff; n++;
                    }
                }
                int i = (gy * GW + gx) * 3;
                if (n > 0) { out[i] = (int) (r / n); out[i + 1] = (int) (g / n); out[i + 2] = (int) (b / n); }
            }
        }
        return out;
    }

    /** Mean and 90th-percentile per-cell colour difference. */
    static float[] diff(int[] a, int[] b) {
        int cells = GW * GH;
        int[] d = new int[cells];
        long sum = 0;
        for (int c = 0; c < cells; c++) {
            int m = 0;
            for (int k = 0; k < 3; k++) m = Math.max(m, Math.abs(a[c * 3 + k] - b[c * 3 + k]));
            d[c] = m;
            sum += m;
        }
        java.util.Arrays.sort(d);
        return new float[]{sum / (float) cells, d[(int) (cells * 0.9f)]};
    }

    static boolean matches(Step st, Frame f) {
        int[] now = look(f, st.rx * f.w, st.ry * f.h);
        float[] d = diff(st.look, now);
        return d[0] < 16 && d[1] < 40;
    }

    /** How much detail a patch has: a flat colour (no text/edges) makes a poor fingerprint. */
    static float detail(int[] look) {
        float mean = 0;
        for (int v : look) mean += v;
        mean /= look.length;
        float var = 0;
        for (int v : look) var += (v - mean) * (v - mean);
        return (float) Math.sqrt(var / look.length);
    }

    // ------------------------------------------------------------------ teaching

    /**
     * Teach one more step for this game: grabs a clean frame, then waits for the user to tap the
     * button. The tap is remembered and passed through to the game.
     */
    void teach(String game, Consumer<String> say) {
        if (busy()) { say.accept("Already busy — wait or press STOP."); return; }
        if (!CaptureService.isRunning()) { say.accept("Start screen capture in the app first."); return; }
        final View panel = host.panelView();
        if (panel != null) panel.setVisibility(View.INVISIBLE);
        say.accept("Looking at the screen…");
        fresh(220, frame -> {
            if (panel != null) panel.setVisibility(View.VISIBLE);
            if (frame == null) { say.accept("No frame from the screen capture — try again."); return; }
            showTeachOverlay(game, frame, say);
        });
    }

    private void showTeachOverlay(String game, Frame frame, Consumer<String> say) {
        int n = steps(game).size() + 1;
        final Paint banner = new Paint(Paint.ANTI_ALIAS_FLAG);
        banner.setColor(0xE60B1022);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Ui.NEON);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(host.dp(15));
        text.setTypeface(Ui.MEDIUM);
        final Paint small = new Paint(text);
        small.setColor(Ui.TEXT_DIM);
        small.setTextSize(host.dp(12));
        final String[] lines = {
                "Step " + n + ": tap the button that leads to the next level",
                "(Continue / Next / Play — never an ad or a coin button)",
                "Long-press anywhere to cancel"};
        View v = new View(svc) {
            long downAt;
            @Override protected void onDraw(Canvas c) {
                float h = host.dp(92);
                c.drawRect(0, 0, getWidth(), h, banner);
                c.drawText(lines[0], getWidth() / 2f, host.dp(36), text);
                c.drawText(lines[1], getWidth() / 2f, host.dp(58), small);
                c.drawText(lines[2], getWidth() / 2f, host.dp(78), small);
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downAt = System.currentTimeMillis(); return true; }
                if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
                removeTeachOverlay();
                if (System.currentTimeMillis() - downAt > 700) { say.accept("Teaching cancelled."); return true; }
                float x = e.getRawX(), y = e.getRawY();
                Step st = new Step();
                st.rx = x / frame.w;
                st.ry = y / frame.h;
                st.look = look(frame, x, y);
                if (detail(st.look) < 6) {
                    say.accept("That spot is a plain colour, so the bot can't recognise it reliably. Tap the button's label instead.");
                    return true;
                }
                List<Step> all = steps(game);
                all.add(st);
                saveSteps(game, all);
                say.accept("Saved step " + all.size() + " for " + host.gameName(game) + ". Pressing it for you now.\n"
                        + "If another screen appears before the level starts, press Teach again on it.");
                handler.postDelayed(() -> tap(x, y, null), 250);
                return true;
            }
        };
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try {
            wm.addView(v, lp);
            teachOverlay = v;
        } catch (Throwable t) {
            say.accept("Couldn't show the teaching screen: " + t.getMessage());
        }
    }

    private void removeTeachOverlay() {
        if (teachOverlay != null) {
            try { wm.removeView(teachOverlay); } catch (Throwable ignored) { }
            teachOverlay = null;
        }
    }

    // ------------------------------------------------------------------ running

    /**
     * Presses the game's next-level steps in order, then waits until {@code ready} says the new
     * level is playable and calls {@code onReady}. {@code builtin} (may be null) finds a button
     * without teaching. Calls {@code onFail} with a reason if something unexpected is on screen.
     */
    void run(String game, Function<Frame, float[]> builtin, Predicate<Frame> ready, long readyTimeoutMs,
             Consumer<String> say, Runnable onReady, Consumer<String> onFail) {
        cancel();
        final int id = ++runId;
        running = true;
        List<Step> taught = steps(game);
        final int total = taught.isEmpty() ? (builtin != null ? 1 : 0) : taught.size();
        if (total == 0) {
            running = false;
            onFail.accept("auto-next isn't taught for " + host.gameName(game) + " yet — on the level-complete screen press Teach and tap the next-level button");
            return;
        }
        say.accept("Level done — looking for the next-level button…");
        stepLoop(id, game, taught, builtin, 0, 0, System.currentTimeMillis() + STEP_TIMEOUT_MS, ready, readyTimeoutMs, say, onReady, onFail);
    }

    private void stepLoop(int id, String game, List<Step> taught, Function<Frame, float[]> builtin, int step, int hits, long deadline,
                          Predicate<Frame> ready, long readyTimeoutMs, Consumer<String> say, Runnable onReady, Consumer<String> onFail) {
        if (id != runId) return;
        int total = taught.isEmpty() ? 1 : taught.size();
        if (step >= total) {
            say.accept("Waiting for the next level to load…");
            waitReady(id, ready, System.currentTimeMillis() + readyTimeoutMs, 0, onReady, onFail);
            return;
        }
        if (System.currentTimeMillis() > deadline) {
            running = false;
            onFail.accept("didn't see " + (taught.isEmpty() ? "the Next Level button" : "step " + (step + 1) + " of " + total)
                    + " — a popup, ad or a different screen is up. Close it, then press Start/Solve.");
            return;
        }
        fresh(0, frame -> {
            if (id != runId) return;
            if (frame == null) { handler.postDelayed(() -> stepLoop(id, game, taught, builtin, step, 0, deadline, ready, readyTimeoutMs, say, onReady, onFail), POLL_MS); return; }
            worker.execute(() -> {
                float[] at = null;
                if (taught.isEmpty()) at = builtin.apply(frame);
                else if (matches(taught.get(step), frame)) at = new float[]{taught.get(step).rx * frame.w, taught.get(step).ry * frame.h};
                final float[] pt = at;
                handler.post(() -> {
                    if (id != runId) return;
                    if (pt == null) {
                        handler.postDelayed(() -> stepLoop(id, game, taught, builtin, step, 0, deadline, ready, readyTimeoutMs, say, onReady, onFail), POLL_MS);
                        return;
                    }
                    if (hits + 1 < 2) {   // must look the same twice (not mid-animation)
                        handler.postDelayed(() -> stepLoop(id, game, taught, builtin, step, hits + 1, deadline, ready, readyTimeoutMs, say, onReady, onFail), 450);
                        return;
                    }
                    int[] pr = host.panelRect();
                    if (pr != null && pt[0] >= pr[0] && pt[0] <= pr[2] && pt[1] >= pr[1] && pt[1] <= pr[3]) {
                        running = false;
                        onFail.accept("the panel is covering the next-level button — drag it away");
                        return;
                    }
                    say.accept("Pressing " + (total > 1 ? "step " + (step + 1) + "/" + total : "Next Level") + "…");
                    tap(pt[0], pt[1], () -> handler.postDelayed(() -> stepLoop(id, game, taught, builtin, step + 1, 0,
                            System.currentTimeMillis() + STEP_TIMEOUT_MS, ready, readyTimeoutMs, say, onReady, onFail), 1200));
                });
            });
        });
    }

    private void waitReady(int id, Predicate<Frame> ready, long deadline, int hits, Runnable onReady, Consumer<String> onFail) {
        if (id != runId) return;
        if (System.currentTimeMillis() > deadline) {
            running = false;
            onFail.accept("the next level didn't appear (an ad or popup may be open). Close it, then press Start/Solve.");
            return;
        }
        fresh(0, frame -> {
            if (id != runId) return;
            if (frame == null) { handler.postDelayed(() -> waitReady(id, ready, deadline, 0, onReady, onFail), 1000); return; }
            worker.execute(() -> {
                boolean ok;
                try { ok = ready.test(frame); } catch (Throwable t) { ok = false; }
                final boolean fok = ok;
                handler.post(() -> {
                    if (id != runId) return;
                    if (fok && hits + 1 >= 2) {
                        running = false;
                        onReady.run();
                    } else {
                        handler.postDelayed(() -> waitReady(id, ready, deadline, fok ? hits + 1 : 0, onReady, onFail), fok ? 600 : 1000);
                    }
                });
            });
        });
    }

    void cancel() {
        runId++;
        running = false;
        removeTeachOverlay();
    }

    void shutdown() {
        cancel();
        handler.removeCallbacksAndMessages(null);
        if (pinger != null) { try { wm.removeView(pinger); } catch (Throwable ignored) { } pinger = null; }
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ frames & taps

    /** A frame taken after now (the tiny overlay flip guarantees the screen produces one). */
    private void fresh(long delay, Consumer<Frame> cb) {
        final boolean[] done = {false};
        handler.postDelayed(() -> {
            CaptureService.requestFrame(frame -> handler.post(() -> {
                if (done[0]) return;
                done[0] = true;
                cb.accept(frame);
            }));
            ping();
        }, delay);
        handler.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            CaptureService.requestFrame(null);
            cb.accept(null);
        }, delay + 2000);
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
                lp.gravity = Gravity.TOP | Gravity.END;
                wm.addView(pinger, lp);
            }
            pingFlip = !pingFlip;
            pinger.setBackgroundColor(pingFlip ? 0x03000000 : 0x02000000);
        } catch (Throwable t) {
            Log.w(TAG, "ping failed", t);
        }
    }

    private void tap(float x, float y, Runnable after) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        boolean ok = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { if (after != null) handler.post(after); }
            @Override public void onCancelled(GestureDescription d) { if (after != null) handler.post(after); }
        }, handler);
        if (!ok && after != null) handler.post(after);
    }
}
