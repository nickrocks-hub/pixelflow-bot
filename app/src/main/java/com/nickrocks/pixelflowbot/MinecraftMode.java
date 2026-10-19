package com.nickrocks.pixelflowbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nickrocks.pixelflowbot.core.GameAction;
import com.nickrocks.pixelflowbot.core.mc.McBrain;
import com.nickrocks.pixelflowbot.core.mc.McLayout;
import com.nickrocks.pixelflowbot.core.mc.McVision;
import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating-panel player for Minecraft (Bedrock, com.mojang.minecraftpe), first-minutes goal:
 * punch trees for logs → planks, crafting table, sticks → place the table → wooden pickaxe (and an
 * axe and sword if there's wood) → pickaxe in hand. {@link McBrain} decides, this class looks at the
 * screen and performs the touches. Each step: capture → decide → touch (panel hidden so it can't
 * catch a touch) → wait → repeat. Stops on damage, an unknown screen, the game leaving the front,
 * or ■.
 */
final class MinecraftMode {
    static final String GAME = "minecraft";
    static final String MINECRAFT_PKG = "com.mojang.minecraftpe";
    private static final long GAP_MS = 220;      // between touches in one step

    private final TapService svc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final GestureExecutor exec;
    private final LinearLayout box;
    private final TextView info;
    private final Button startBtn, pauseBtn, ctrlBtn, setBtn;
    private final SharedPreferences prefs;
    private McBrain brain;
    private Recorder rec;
    private boolean running, dead;
    private int frameW = 2520, frameH = 1080, portraitTries;
    private long t0;

    MinecraftMode(TapService svc) {
        this.svc = svc;
        this.exec = new GestureExecutor(svc, handler);
        this.prefs = svc.getSharedPreferences(TapService.PREFS, android.content.Context.MODE_PRIVATE);
        box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(svc);
        startBtn = svc.button("▶ Start", Ui.ACCENT, v -> start());
        pauseBtn = svc.button("⏸ Pause", Ui.MUTED, v -> stop("Paused by you. ▶ starts over from what's on screen."));
        r1.addView(startBtn);
        r1.addView(pauseBtn);
        r1.addView(svc.button("■ STOP", Ui.DANGER, v -> { stop("stopped by you"); TapService.stopEverything(); }));
        box.addView(r1);
        LinearLayout r2 = new LinearLayout(svc);
        ctrlBtn = svc.button("", Ui.MUTED, v -> {
            prefs.edit().putBoolean("mc_dpad", !prefs.getBoolean("mc_dpad", false)).apply();
            refresh();
        });
        setBtn = svc.button("", Ui.MUTED, v -> {
            prefs.edit().putBoolean("mc_pickaxe_only", !prefs.getBoolean("mc_pickaxe_only", false)).apply();
            refresh();
        });
        r2.addView(ctrlBtn);
        r2.addView(setBtn);
        box.addView(r2);
        LinearLayout r3 = new LinearLayout(svc);
        r3.addView(svc.button("🔍 Look", Ui.INFO, v -> lookOnce()));
        r3.addView(svc.button("📸 Save screen", Ui.MUTED, v -> {
            if (CaptureService.isRunning()) CaptureService.requestSaveFrame(); else svc.toast("Screen capture is off.");
        }));
        box.addView(r3);
        info = new TextView(svc);
        info.setTextColor(Ui.TEXT_DIM);
        info.setTextSize(11);
        info.setMaxWidth(svc.dp(300));
        info.setPadding(svc.dp(4), svc.dp(4), 0, 0);
        box.addView(info);
        note("Minecraft: gathers logs, crafts a table and wooden tools. Stand near trees in Survival, close menus, then ▶ Start. "
                + "Pick your touch layout below (joystick or D-pad).");
        refresh();
    }

    View box() { return box; }
    boolean busy() { return running; }
    void note(String s) { info.setText(s); }

    private boolean dpad() { return prefs.getBoolean("mc_dpad", false); }
    private boolean pickaxeOnly() { return prefs.getBoolean("mc_pickaxe_only", false); }

    private void refresh() {
        startBtn.setText(running ? "▶ Playing" : "▶ Start");
        pauseBtn.setEnabled(running);
        pauseBtn.setAlpha(running ? 1f : 0.4f);
        ctrlBtn.setText(dpad() ? "✚ D-pad" : "🕹 Joystick");
        setBtn.setText(pickaxeOnly() ? "⛏ Pickaxe only" : "⛏🪓🗡 All tools");
        ctrlBtn.setEnabled(!running);
        setBtn.setEnabled(!running);
    }

    private boolean ready() {
        if (!CaptureService.isRunning()) { note("Screen capture is off — start it in the app (Launch & play does it for you)."); return false; }
        if (!TapService.isRunning()) { note("The tap service is off."); return false; }
        return true;
    }

    /** One look, no touches: what the bot sees right now. */
    private void lookOnce() {
        if (running || !ready()) return;
        note("Looking…");
        grab(f -> {
            String m;
            if (f.w < f.h) m = "The capture is in portrait — turn the phone to landscape in the game and look again.";
            else {
                McVision.Screen s = McVision.screen(f);
                StringBuilder sb = new StringBuilder("Screen: " + s.name().toLowerCase(Locale.US));
                if (s == McVision.Screen.WORLD) {
                    sb.append(" · health ").append(McVision.health(f)).append("/20");
                    List<McVision.Trunk> ts = McVision.trunks(f);
                    sb.append(ts.isEmpty() ? "\nNo tree trunk in view." : "\n" + ts.size() + " trunk(s); nearest " + ts.get(0));
                } else if (s != McVision.Screen.OTHER) {
                    sb.append("\n").append(McVision.craft(f).summary());
                }
                m = sb.toString();
            }
            handler.post(() -> note(m));
        });
    }

    private void start() {
        if (running || !ready()) return;
        brain = new McBrain();
        brain.controls = dpad() ? McBrain.Controls.DPAD : McBrain.Controls.JOYSTICK;
        brain.fullSet = !pickaxeOnly();
        float k = prefs.getFloat("mc_turn_k", Float.NaN);
        if (!Float.isNaN(k)) brain.setTurn(k);
        rec = new Recorder(svc);
        rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"start\",\"mode\":\"minecraft\",\"controls\":\"" + brain.controls + "\",\"fullSet\":" + brain.fullSet + "}");
        running = true;
        portraitTries = 0;
        t0 = System.currentTimeMillis();
        svc.setCompact(true);
        refresh();
        note("Starting… (log: " + rec.where() + ")");
        handler.postDelayed(this::loop, 300);
    }

    void stop(String why) {
        boolean was = running;
        running = false;
        svc.setCompact(false);
        View p = svc.panelView();
        if (p != null) p.setVisibility(View.VISIBLE);
        refresh();
        if (was) {
            note(why + (brain != null ? "\n" + brain.status() : ""));
            if (rec != null) rec.line("{\"t\":" + System.currentTimeMillis() + ",\"event\":\"end\",\"why\":" + Recorder.q(why) + "}");
        }
    }

    private void loop() {
        if (!running || dead) return;
        String fg = svc.foregroundPackage();
        String pkg = MinecraftMode.MINECRAFT_PKG;
        if (fg != null && !fg.equals("?") && !fg.equals(pkg) && !fg.equals(svc.getPackageName())) { stop("Stopped: Minecraft isn't in front (" + fg + ")."); return; }
        grab(f -> {
            if (f.w < f.h) {
                handler.post(() -> {
                    if (++portraitTries >= 3) stop("Stopped: the screen capture is in portrait. Play Minecraft in landscape (stop and restart capture if this stays).");
                    else handler.postDelayed(this::loop, 500);
                });
                return;
            }
            portraitTries = 0;
            frameW = f.w;
            frameH = f.h;
            McBrain.Step st;
            try {
                st = brain.next(f);
            } catch (RuntimeException e) {
                CaptureService.saveFrame(f, true);
                handler.post(() -> stop("Stopped: " + e));
                return;
            }
            if (rec != null) {
                rec.line("{\"t\":" + System.currentTimeMillis() + ",\"step\":" + brain.steps() + ",\"phase\":\"" + brain.phase + "\",\"note\":" + Recorder.q(st.note)
                        + ",\"acts\":" + Recorder.q(st.acts.toString()) + (st.stop != null ? ",\"stop\":" + Recorder.q(st.stop) : "") + "}");
            }
            if (st.stop != null && !st.success) CaptureService.saveFrame(f, true);
            if (!Float.isNaN(brain.turnK)) prefs.edit().putFloat("mc_turn_k", brain.turnK).apply();
            final McBrain.Step step = st;
            handler.post(() -> {
                if (!running) return;
                if (step.stop != null) {
                    long min = (System.currentTimeMillis() - t0) / 60000;
                    stop((step.success ? "✅ " : "Stopped: ") + step.stop + " (" + min + " min)");
                    return;
                }
                note(step.note + "\n" + brain.status());
                perform(step.acts, 0, () -> handler.postDelayed(this::loop, step.settleMs));
            });
        });
    }

    // ------------------------------------------------------------------ touches

    /** Performs acts[i..] one after the other with the panel hidden, then calls done. */
    private void perform(List<McBrain.Act> acts, int i, Runnable done) {
        View panel = svc.panelView();
        if (!running) { if (panel != null) panel.setVisibility(View.VISIBLE); return; }
        if (i >= acts.size()) {
            if (panel != null) panel.setVisibility(View.VISIBLE);
            done.run();
            return;
        }
        if (panel != null) panel.setVisibility(View.INVISIBLE);
        McBrain.Act a = acts.get(i);
        Runnable next = () -> handler.postDelayed(() -> perform(acts, i + 1, done), GAP_MS);
        float sx = frameW / McLayout.REF_W, sy = frameH / McLayout.REF_H;
        // let the hidden panel leave the screen before touching where it was
        handler.postDelayed(() -> {
            if (!running) return;
            switch (a.kind) {
                case WALK:
                    walk(a.ms, sx, sy, next);
                    return;
                case HOLD:
                    run(GameAction.longPress(a.x * sx, a.y * sy, a.ms, "mine"), next);
                    return;
                case SWIPE:
                    run(GameAction.swipe(a.x * sx, a.y * sy, a.x2 * sx, a.y2 * sy, a.ms, "look"), next);
                    return;
                default:
                    run(GameAction.tap(a.x * sx, a.y * sy, "tap"), next);
            }
        }, i == 0 ? 60 : 0);
    }

    private void run(GameAction g, Runnable next) {
        g.source = "minecraft planner";
        exec.execute(g, (ok, latency, why) -> handler.post(() -> {
            if (!ok) { stop("Stopped: a touch failed (" + why + ")"); return; }
            next.run();
        }));
    }

    /** Walk forward: hold the D-pad's forward arrow, or push the joystick up and keep it there. */
    private void walk(long ms, float sx, float sy, Runnable next) {
        if (dpad()) {
            run(GameAction.longPress(McLayout.DPAD_UP_X * sx, McLayout.DPAD_UP_Y * sy, ms, "walk"), next);
            return;
        }
        float x = McLayout.JOY_X * sx, y0 = McLayout.JOY_Y * sy, y1 = (McLayout.JOY_Y - McLayout.JOY_REACH) * sy;
        Path push = new Path();
        push.moveTo(x, y0);
        push.lineTo(x, y1);
        final GestureDescription.StrokeDescription s1 = new GestureDescription.StrokeDescription(push, 0, 160, true);
        boolean ok = svc.dispatchGesture(new GestureDescription.Builder().addStroke(s1).build(), new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                Path stay = new Path();
                stay.moveTo(x, y1);
                stay.lineTo(x + 1, y1);
                GestureDescription.StrokeDescription s2 = s1.continueStroke(stay, 0, Math.max(50, ms), false);
                boolean ok2 = svc.dispatchGesture(new GestureDescription.Builder().addStroke(s2).build(), new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g2) { handler.post(next); }
                    @Override public void onCancelled(GestureDescription g2) { handler.post(() -> stop("Stopped: walking was interrupted (screen touched?)")); }
                }, handler);
                if (!ok2) handler.post(() -> stop("Stopped: the walk gesture was rejected."));
            }
            @Override public void onCancelled(GestureDescription g) { handler.post(() -> stop("Stopped: walking was interrupted (screen touched?)")); }
        }, handler);
        if (!ok) stop("Stopped: the walk gesture was rejected — is the tap service on?");
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
