package com.nickrocks.pixelflowbot;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.nickrocks.pixelflowbot.core.EpisodeRecorder;
import com.nickrocks.pixelflowbot.core.GameAction;
import com.nickrocks.pixelflowbot.core.GameProfile;
import com.nickrocks.pixelflowbot.core.RewardModel;
import com.nickrocks.pixelflowbot.core.SessionGuard;
import com.nickrocks.pixelflowbot.core.StateObservation;
import com.nickrocks.pixelflowbot.core.vlm.InstructionAgent;
import com.nickrocks.pixelflowbot.core.vlm.ScreenGrid;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Floating-panel player for games without a built-in player: the on-device AI agent following the
 * owner's instructions. Loop: screenshot (+ labelled grid) → on-device model decides → safety guard
 * → gesture → wait → repeat. Nothing leaves the phone.
 * Every step is stored as experience; every 25 steps and at the end, what it learned about the
 * game is written to the profile as rules (hypotheses you can see and edit in the app).
 */
final class InstructionMode {
    private static final String TAG = "AIGameBot.Instr";
    private static final int IDLE = 0, PLAYING = 1, PAUSED = 2, CALIBRATING = 3;

    private final TapService svc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final GestureExecutor exec;
    private final SessionGuard guard = new SessionGuard();
    private final LinearLayout box;
    private final TextView info;
    private final Button calBtn, startBtn, pauseBtn;
    private View pinger;
    private boolean pingFlip;

    private int state = IDLE;
    private GameProfile profile;
    private volatile InstructionAgent agent;
    private EpisodeRecorder rec;
    private GameAction pending;
    private long pendingStart, pendingLatency;
    private int noEffect, waits;
    private boolean dead;

    InstructionMode(TapService svc) {
        this.svc = svc;
        this.exec = new GestureExecutor(svc, handler);
        guard.minConfidence = 0.5f;
        guard.maxNoEffect = 5;
        guard.playable.add(StateObservation.Screen.MENU);
        guard.playable.add(StateObservation.Screen.LEVEL_COMPLETE);
        guard.playable.add(StateObservation.Screen.LOSS);
        box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(svc);
        startBtn = svc.button("▶ Start", Ui.ACCENT, v -> start());
        pauseBtn = svc.button("⏸ Pause", Ui.MUTED, v -> pause("paused by you"));
        r1.addView(startBtn);
        r1.addView(pauseBtn);
        r1.addView(svc.button("■ STOP", Ui.DANGER, v -> { end("stopped by you"); TapService.stopEverything(); }));
        box.addView(r1);
        LinearLayout r2 = new LinearLayout(svc);
        calBtn = svc.button("🔍 Calibrate", Ui.INFO, v -> calibrate());
        r2.addView(calBtn);
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
        refresh();
    }

    View box() { return box; }
    boolean busy() { return state == PLAYING || state == CALIBRATING; }
    void note(String s) { info.setText(s); }

    /** The panel switched to this game. */
    void setProfile(GameProfile p) {
        if (busy()) return;
        profile = p;
        agent = null;
        LocalSettings ls = new LocalSettings(svc);
        if (p == null) { note(""); return; }
        if (!ls.ready()) {
            note(p.name + ": no built-in player. The on-device AI can play it from your instructions — import a model in "
                    + "AI GAME BOT → On-device AI first.");
        } else {
            String instr = p.settings.get("objective");
            boolean cal = "yes".equals(p.settings.get("calibration_confirmed"));
            note(p.name + " · on-device AI (" + ls.modelName() + ", up to " + ls.maxSteps() + " steps)\n"
                    + (instr == null || instr.trim().isEmpty() ? "⚠ No instructions yet — add them in the game's profile.\n" : "")
                    + (cal ? "Calibration confirmed." : "Press 🔍 Calibrate on a game screen first, then confirm it in the app.")
                    + "\nRuns on this phone — nothing is sent anywhere.");
        }
        refresh();
    }

    private void refresh() {
        startBtn.setText(state == PLAYING ? "▶ Playing" : state == PAUSED ? "▶ Resume" : "▶ Start");
        pauseBtn.setEnabled(state == PLAYING);
        pauseBtn.setAlpha(state == PLAYING ? 1f : 0.4f);
        calBtn.setEnabled(state == IDLE || state == PAUSED);
        calBtn.setAlpha(calBtn.isEnabled() ? 1f : 0.4f);
    }

    private boolean prepare() {
        LocalSettings ls = new LocalSettings(svc);
        if (profile == null) { svc.toast("Open a game from your library first."); return false; }
        if (!ls.ready()) { note("Import a model in AI GAME BOT → On-device AI first."); return false; }
        if (!CaptureService.isRunning()) { note("Screen capture is off — start it in the app (Launch & play does it for you)."); return false; }
        String fg = svc.foregroundPackage();
        if (profile.packageName != null && fg != null && !fg.equals("?") && !fg.equals(profile.packageName) && !fg.equals(svc.getPackageName())) {
            note(profile.name + " isn't in front (" + fg + ")."); return false;
        }
        return true;
    }

    /** The agent with the model loaded. Runs on the worker thread (the first load takes a while). */
    private InstructionAgent agentNow() throws IOException {
        InstructionAgent a = agent;
        if (a != null) return a;
        LocalSettings ls = new LocalSettings(svc);
        if (!LocalVision.isLoaded(ls.modelPath())) handler.post(() -> note("Loading the on-device model (10–60 s the first time)…"));
        LocalVision m = LocalVision.get(svc, ls.modelPath(), ls.modelName(), ls.preferGpu());
        GameProfile p = AgentPlatform.get(svc).profile(profile.id);
        a = new InstructionAgent(p, m);
        a.maxCalls = ls.maxSteps();
        agent = a;
        return a;
    }

    // ------------------------------------------------------------------ calibration

    private void calibrate() {
        if (!prepare()) return;
        state = CALIBRATING;
        refresh();
        note("Looking at the screen…");
        shot((jpeg, w, h, sw, sh) -> worker.execute(() -> {
            try {
                InstructionAgent.Calibration c = agentNow().calibrate(jpeg, w, h, sw, sh);
                final long ms = agent.modelMs;
                GameProfile p = AgentPlatform.get(svc).profile(profile.id);
                if (c.board != null) {
                    int[] bd = c.board;
                    p.settings.put("board", bd[0] + "," + bd[1] + "," + bd[2] + "," + bd[3] + "," + bd[4] + "," + bd[5]);
                } else {
                    p.settings.remove("board");
                }
                p.settings.put("calibration", c.text());
                p.settings.put("calibration_confirmed", "no");
                p.settings.put("calibration_questions", String.join("\n", c.questions));
                p.settings.put("calibration_confidence", String.format(Locale.US, "%.2f", c.confidence));
                profile = AgentPlatform.get(svc).profiles.save(p);
                agent = null;
                String q = c.questions.isEmpty() ? "" : "\nQuestions for you (answer in the profile's instructions):\n• " + String.join("\n• ", c.questions);
                handler.post(() -> {
                    state = IDLE;
                    refresh();
                    note("Calibration (confidence " + String.format(Locale.US, "%.2f", c.confidence) + ", " + String.format(Locale.US, "%.0f s", ms / 1000.0) + " on this phone):\n"
                            + c.text() + q + "\nOpen the game's profile in AI GAME BOT to confirm or correct it.");
                });
            } catch (Exception e) {
                handler.post(() -> { state = IDLE; refresh(); note("Calibration failed: " + e.getMessage()); });
            }
        }));
    }

    // ------------------------------------------------------------------ playing

    private void start() {
        if (state == PLAYING) return;
        if (!prepare()) return;
        if (rec == null) {
            rec = new EpisodeRecorder(AgentPlatform.get(svc).experience, profile, "PLAY",
                    (before, a, after) -> {
                        String eff = after == null ? "none" : after.value("effect of last action (model)", "none");
                        return new RewardModel.Reward(InstructionAgent.rewardOf(eff), "vision model's own report: " + eff + " (unverified)", false, false);
                    });
            rec.begin(null, "on-device instructions agent, " + new LocalSettings(svc).modelName());
            noEffect = 0;
        }
        state = PLAYING;
        svc.setCompact(true);
        refresh();
        loop();
    }

    private void loop() {
        if (state != PLAYING || dead) return;
        String b = agent == null ? null : agent.budgetProblem();
        if (b != null) { pause(b); return; }
        shot((jpeg, w, h, sw, sh) -> worker.execute(() -> {
            InstructionAgent.Decision d;
            try {
                d = agentNow().decide(jpeg, w, h, sw, sh);
            } catch (Exception e) {
                handler.post(() -> pause("model call failed: " + e.getMessage()));
                return;
            }
            final InstructionAgent.Decision fd = d;
            handler.post(() -> act(fd));
        }));
    }

    private void act(InstructionAgent.Decision d) {
        if (state != PLAYING) return;
        // The reward for the previous action is the model's report on what it did.
        if (pending != null && rec != null) rec.step(pending, pendingStart, pendingLatency, d.obs);
        pending = null;
        if ("no_effect".equals(d.effect)) noEffect++; else if ("worked".equals(d.effect)) noEffect = 0;
        String status = String.format(Locale.US, "step %d of max %d · %s · conf %.2f · %.1f s to think\nSees: %s",
                agent.steps(), agent.maxCalls, d.obs.screen.name().toLowerCase(Locale.US), d.confidence,
                d.reply == null ? 0 : d.reply.ms / 1000.0, d.observation);
        if (d.stop) { pause(d.problem != null ? d.problem : "the model stopped"); return; }
        if (d.wait || d.obs.screen == StateObservation.Screen.TRANSITION) {
            if (++waits > 6) { pause("waited 6 times — nothing to do?"); return; }
            note(status + "\nWaiting… " + d.reason);
            handler.postDelayed(this::loop, 1500);
            return;
        }
        waits = 0;
        SessionGuard.Status st = new SessionGuard.Status();
        st.captureOn = CaptureService.isRunning();
        st.controlOn = TapService.isRunning();
        st.foregroundPackage = svc.foregroundPackage();
        st.ownPackage = svc.getPackageName();
        st.latest = d.obs;
        st.actionsWithoutEffect = noEffect;
        String why = guard.check(profile, st);
        if (why != null) { pause(why); return; }
        note(status + "\nDoing: " + d.action + "\nWhy: " + d.reason);
        final long t0 = System.currentTimeMillis();
        final GameAction a = d.action;
        exec.execute(a, (ok, latency, n) -> handler.post(() -> {
            if (!ok) { pause("gesture failed: " + n); return; }
            pending = a;
            pendingStart = t0;
            pendingLatency = latency;
            if (agent.steps() % 25 == 0) learn(false);
            handler.postDelayed(this::loop, new LocalSettings(svc).settleMs());
        }));
    }

    private void pause(String why) {
        if (state != PLAYING && state != CALIBRATING) { note(why); return; }
        state = PAUSED;
        svc.setCompact(false);
        refresh();
        note("Paused: " + why + (agent != null ? "\n" + agent.steps() + " steps" : ""));
        if (rec != null) rec.end("paused: " + why, null);
        rec = null;
        learn(true);   // Resume starts a fresh episode
    }

    void end(String why) {
        if (state == IDLE) return;
        if (rec != null) rec.end(why, null);
        rec = null;
        state = IDLE;
        svc.setCompact(false);
        refresh();
    }

    /** Rule learning: the model condenses the session into rules saved in the profile. */
    private void learn(boolean finalPass) {
        if (agent == null) return;
        final InstructionAgent ag = agent;
        worker.execute(() -> {
            try {
                String rules = ag.learnRules();
                if (rules == null) return;
                GameProfile p = AgentPlatform.get(svc).profile(profile.id);
                p.settings.put("learned", rules);
                AgentPlatform.get(svc).profiles.save(p);
                if (finalPass) handler.post(() -> info.append("\nSaved what it learned to the profile."));
            } catch (Exception e) {
                Log.w(TAG, "learning failed", e);
            }
        });
    }

    void shutdown() {
        dead = true;
        end("panel closed");
        handler.removeCallbacksAndMessages(null);
        if (pinger != null) { try { ((WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE)).removeView(pinger); } catch (Throwable ignored) { } }
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ screenshots

    private interface Shot { void got(byte[] jpeg, int w, int h, int screenW, int screenH); }

    /** A fresh frame with the panel hidden, shrunk to IMG_W wide, labelled grid drawn on, JPEG-encoded. */
    private void shot(Shot cb) {
        final View panel = svc.panelView();
        if (panel != null) panel.setVisibility(View.INVISIBLE);
        final boolean[] done = {false};
        handler.postDelayed(() -> {
            CaptureService.requestFrame(frame -> worker.execute(() -> {
                byte[] jpeg;
                int w = InstructionAgent.IMG_W, h;
                try {
                    Bitmap full = Bitmap.createBitmap(frame.px, frame.w, frame.h, Bitmap.Config.ARGB_8888);
                    h = Math.round(frame.h * (w / (float) frame.w));
                    Bitmap small = Bitmap.createScaledBitmap(full, w, h, true).copy(Bitmap.Config.ARGB_8888, true);
                    drawGrid(small, InstructionAgent.gridFor(w, h));
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    small.compress(Bitmap.CompressFormat.JPEG, 80, bo);
                    full.recycle();
                    small.recycle();
                    jpeg = bo.toByteArray();
                } catch (Throwable t) {
                    handler.post(() -> { if (panel != null) panel.setVisibility(View.VISIBLE); pause("couldn't encode the screenshot: " + t); });
                    return;
                }
                final byte[] j = jpeg;
                final int fh = h;
                handler.post(() -> {
                    if (done[0]) return;
                    done[0] = true;
                    if (panel != null) panel.setVisibility(View.VISIBLE);
                    cb.got(j, w, fh, frame.w, frame.h);
                });
            }));
            ping();
        }, 200);
        handler.postDelayed(() -> {
            if (done[0]) return;
            done[0] = true;
            CaptureService.requestFrame(null);
            if (panel != null) panel.setVisibility(View.VISIBLE);
            if (state == CALIBRATING) state = IDLE;
            pause("no new frame from the screen capture (is it still on?)");
            refresh();
        }, 3000);
    }

    /** Thin lines and a small name ("C7") in each cell's top-left corner, readable on any background. */
    static void drawGrid(Bitmap b, ScreenGrid g) {
        Canvas c = new Canvas(b);
        Paint dark = new Paint();
        dark.setColor(0x66000000);
        dark.setStrokeWidth(3);
        Paint light = new Paint();
        light.setColor(0xAAFFFFFF);
        light.setStrokeWidth(1);
        for (int i = 1; i < g.cols; i++) { float x = i * g.cellW; c.drawLine(x, 0, x, g.imgH, dark); c.drawLine(x, 0, x, g.imgH, light); }
        for (int j = 1; j < g.rows; j++) { float y = j * g.cellH; c.drawLine(0, y, g.imgW, y, dark); c.drawLine(0, y, g.imgW, y, light); }
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xFFFFFF00);
        txt.setTextSize(Math.max(10, g.cellH * 0.24f));
        txt.setFakeBoldText(true);
        Paint box = new Paint();
        box.setColor(0xB0000000);
        Paint.FontMetrics fm = txt.getFontMetrics();
        float th = fm.descent - fm.ascent;
        for (int j = 0; j < g.rows; j++) {
            for (int i = 0; i < g.cols; i++) {
                String s = g.label(j, i);
                float x = i * g.cellW + 2, y = j * g.cellH + 2;
                c.drawRect(x, y, x + txt.measureText(s) + 4, y + th, box);
                c.drawText(s, x + 2, y - fm.ascent, txt);
            }
        }
    }

    /** Flip a tiny invisible overlay so the screen recorder produces a new frame. */
    private void ping() {
        try {
            if (pinger == null) {
                pinger = new View(svc);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(2, 2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                ((WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE)).addView(pinger, lp);
            }
            pingFlip = !pingFlip;
            pinger.setBackgroundColor(pingFlip ? 0x03000000 : 0x02000000);
        } catch (Throwable t) {
            Log.w(TAG, "ping failed", t);
        }
    }
}
