package com.nickrocks.pixelflowbot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
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

import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.GameAdapter;
import com.nickrocks.pixelflowbot.learn.Transition;
import com.nickrocks.pixelflowbot.learn.nono.NonoAdapter;
import com.nickrocks.pixelflowbot.learn.nono.NonoState;
import com.nickrocks.pixelflowbot.nono.NextButton;
import com.nickrocks.pixelflowbot.nono.NonoSolver;
import com.nickrocks.pixelflowbot.nono.NonoVision;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The learning agent playing the real Nonogram.com app (floating panel: ◆ Nonogram AI).
 *
 * Loop: observe (screen capture) → extract state (grid, clues, cells, hearts, confidence)
 * → agent chooses a cell → tap it → wait for the board to settle → observe the result
 * → reward (filled = +1, heart lost / red mark = −5, solved +10, out of hearts −10)
 * → store the transition → learn (Training mode only) → repeat.
 *
 * It stops acting when paused or stopped, when capture stops, when the game leaves the front,
 * when a tap is cancelled, or when an observation is uncertain after retries.
 */
final class NonoAiMode {
    static final String TAG = "PFBot.NonoAI";
    private static final String PREF_SAFETY = "ai_safety_net";
    private static final int IDLE = 0, RUNNING = 1, PAUSED = 2;
    private static final int MAX_UNCERTAIN = 6;

    private final TapService svc;
    private final CandyMode host;
    private final AiHub hub;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final NonoVision vision = new NonoVision();
    private final WindowManager wm;

    private final LinearLayout box;
    private final TextView info;
    private final Button startButton, pauseButton, modeButton, safetyButton;
    private View pinger;
    private boolean pingFlip;

    private int state = IDLE;
    private boolean dead;
    private int readId;
    private String gamePkg;
    private Recorder rec;

    // Episode
    private long episode = -1;
    private int step, uncertain, noChange, startHearts = -1, blocked;
    private float episodeReward;
    private Pending pending;
    private NonoVision.Reading lastGood;
    private String lastAction = "—", lastOutcome = "—";

    /** The action waiting for its result. */
    private static final class Pending {
        int cell;
        GameAdapter.Candidate cand;
        Agent.Choice choice;
        String stateBefore;
        int heartsBefore, redBefore;
        long tappedAt;
    }

    NonoAiMode(TapService svc, CandyMode host) {
        this.svc = svc;
        this.host = host;
        this.hub = AiHub.get(svc);
        this.prefs = svc.getSharedPreferences(TapService.PREFS, 0);
        this.wm = (WindowManager) svc.getSystemService(android.content.Context.WINDOW_SERVICE);

        box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row1 = new LinearLayout(svc);
        startButton = host.button("▶ Start", Ui.ACCENT, v -> start());
        pauseButton = host.button("⏸ Pause", Ui.MUTED, v -> { if (state == RUNNING) pause("Paused by you"); });
        row1.addView(startButton);
        row1.addView(pauseButton);
        row1.addView(host.button("■ STOP", Ui.DANGER, v -> { host.cancelNext(); end("stopped by you"); TapService.stopEverything(); }));
        box.addView(row1);

        info = new TextView(svc);
        info.setTextColor(Ui.TEXT_DIM);
        info.setTextSize(10.5f);
        info.setTypeface(Ui.MONO);
        info.setPadding(host.dp(4), host.dp(4), 0, host.dp(2));
        info.setMaxWidth(host.dp(310));
        box.addView(info);

        LinearLayout row2 = new LinearLayout(svc);
        modeButton = host.button("", Ui.GAME_NONO, v -> cycleMode());
        safetyButton = host.button("", Ui.MUTED, v -> {
            prefs.edit().putBoolean(PREF_SAFETY, !safetyNet()).apply();
            refresh();
        });
        row2.addView(modeButton);
        row2.addView(safetyButton);
        box.addView(row2);
        refresh();
        setInfo("Learning agent for Nonogram.com. Train it first in the app's AI Lab (simulator), then open a puzzle and Start.\n" + modelLine());
    }

    View box() { return box; }

    boolean busy() { return state != IDLE; }

    void note(String s) { setInfo(s); }

    private boolean safetyNet() { return prefs.getBoolean(PREF_SAFETY, true); }

    private void cycleMode() {
        if (state == RUNNING) { host.toast("Pause first to change mode."); return; }
        synchronized (hub.lock) {
            Agent.Mode[] m = Agent.Mode.values();
            hub.agent.mode = m[(hub.agent.mode.ordinal() + 1) % m.length];
        }
        refresh();
        setInfo(modeHelp() + "\n" + modelLine());
    }

    private String modeHelp() {
        switch (hub.agent.mode) {
            case TRAINING: return "Training: explores a little and learns from every move (mistakes can cost hearts).";
            case EVALUATION: return "Evaluation: best moves only, no learning — measures how good the model is.";
            default: return "Play: uses the saved model, no exploration, no learning.";
        }
    }

    private String modelLine() {
        synchronized (hub.lock) {
            return String.format(Locale.US, "model: %d updates · trained on %d episodes", hub.agent.model.updates,
                    hub.agent.stats.get(Agent.Mode.TRAINING).episodes);
        }
    }

    private void refresh() {
        startButton.setText(state == IDLE ? "▶ Start" : state == PAUSED ? "▶ Resume" : "▶ Running");
        pauseButton.setEnabled(state == RUNNING);
        pauseButton.setAlpha(state == RUNNING ? 1f : 0.4f);
        String m = hub.agent.mode.name();
        modeButton.setText("Mode: " + m.charAt(0) + m.substring(1).toLowerCase(Locale.US));
        safetyButton.setText(safetyNet() ? "Safety net: on" : "Safety net: off");
    }

    private void setInfo(String s) {
        String head = "";
        if (state != IDLE && episode >= 0) {
            synchronized (hub.lock) {
                head = String.format(Locale.US, "%s ep %d · step %d · reward %+.0f · ε %.2f%n%s%n",
                        state == PAUSED ? "PAUSED" : hub.agent.mode.name(), episode, step, episodeReward, hub.agent.epsilon(),
                        hub.agent.stats().line());
            }
        }
        info.setText(head + s);
    }

    private void log(String json) {
        if (rec == null) return;
        try { rec.line("{\"t\":" + System.currentTimeMillis() + ",\"game\":\"nonogram-ai\"," + json.substring(1)); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ control

    private void start() {
        if (state == PAUSED) { resume(); return; }
        if (state != IDLE) return;
        if (!CaptureService.isRunning()) { host.toast("Capture is off. Start it from the app first."); return; }
        if (hub.simRunning) { host.toast("Stop the simulator in the AI Lab first (they share the model)."); return; }
        String pkg = host.foregroundPackage();
        if (pkg == null || pkg.equals("?") || pkg.equals(svc.getPackageName())) { host.toast("Open a Nonogram.com puzzle first."); return; }
        gamePkg = pkg;
        state = RUNNING;
        if (rec == null) { try { rec = new Recorder(svc); } catch (Throwable t) { rec = null; } }
        hub.agent.log = hub.experiences;
        newEpisode();
        setCompact(true);
        refresh();
        setInfo("Observing…");
        observe(220);
    }

    private void newEpisode() {
        episode = hub.newEpisodeId();
        step = uncertain = noChange = blocked = 0;
        startHearts = -1;
        episodeReward = 0;
        pending = null;
        lastGood = null;
        log("{\"event\":\"episode_start\",\"episode\":" + episode + ",\"mode\":\"" + hub.agent.mode + "\"}");
    }

    void autoStart() {
        if (state != IDLE) return;
        start();
    }

    private void resume() {
        state = RUNNING;
        uncertain = noChange = 0;
        refresh();
        setCompact(true);
        setInfo("Resuming…");
        observe(220);
    }

    private void pause(String why) {
        if (state != RUNNING) return;
        state = PAUSED;
        readId++;
        refresh();
        setInfo("Paused: " + why);
        log("{\"event\":\"pause\",\"why\":" + Recorder.q(why) + "}");
    }

    void end(String why) {
        if (state != IDLE) log("{\"event\":\"end\",\"why\":" + Recorder.q(why) + "}");
        state = IDLE;
        readId++;
        pending = null;
        hub.experiences.flush();
        if (hub.agent.learning()) hub.autosave();
        setCompact(false);
        refresh();
        setInfo("Stopped: " + why + "\n" + modelLine());
    }

    void shutdown() {
        if (dead) return;
        dead = true;
        state = IDLE;
        handler.removeCallbacksAndMessages(null);
        if (pinger != null) { try { wm.removeView(pinger); } catch (Throwable ignored) { } pinger = null; }
        worker.shutdownNow();
        hub.experiences.flush();
    }

    void setCompact(boolean on) {
        host.setCompact(on);
        View row2 = box.getChildAt(2);
        if (row2 != null) row2.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ observation

    /** Takes a fresh frame with the panel hidden and reads it on the worker thread. */
    private void observe(long delay) {
        final int id = ++readId;
        final boolean[] done = {false};
        handler.postDelayed(() -> {
            if (id != readId || state != RUNNING || dead) return;
            if (!CaptureService.isRunning()) { end("screen capture stopped"); return; }
            String pkg = host.foregroundPackage();
            if (gamePkg != null && pkg != null && !pkg.equals("?") && !pkg.equals(gamePkg) && !pkg.equals(svc.getPackageName())) {
                pause("the game isn't in front (" + pkg + ")");
                return;
            }
            final View panel = host.panelView();
            if (panel != null) panel.setVisibility(View.INVISIBLE);
            handler.postDelayed(() -> {
                if (id != readId) { if (panel != null) panel.setVisibility(View.VISIBLE); return; }
                CaptureService.requestFrame(frame -> worker.execute(() -> {
                    NonoVision.Reading r = vision.read(frame);
                    boolean next = r.grid == null && NextButton.find(frame) != null;
                    handler.post(() -> {
                        if (panel != null) panel.setVisibility(View.VISIBLE);
                        if (id != readId || done[0]) return;
                        done[0] = true;
                        onObservation(r, next);
                    });
                }));
                ping();
            }, 160);
            handler.postDelayed(() -> {
                if (id != readId || done[0]) return;
                done[0] = true;
                CaptureService.requestFrame(null);
                if (panel != null) panel.setVisibility(View.VISIBLE);
                NonoVision.Reading r = new NonoVision.Reading();
                r.error = "no new frame";
                onObservation(r, false);
            }, 2600);
        }, delay);
    }

    private void ping() {
        try {
            if (pinger == null) {
                pinger = new View(svc);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(2, 2,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                wm.addView(pinger, lp);
            }
            pingFlip = !pingFlip;
            pinger.setBackgroundColor(pingFlip ? 0x03000000 : 0x02000000);
        } catch (Throwable t) {
            Log.w(TAG, "ping failed", t);
        }
    }

    /** Turns a screen reading into the adapter's state. Hidden (frozen) clues stay null. */
    private NonoState toState(NonoVision.Reading r) {
        NonoState s = new NonoState(r.rowClues, r.colClues);
        for (int i = 0; i < r.cells.length; i++) {
            s.cells[i] = r.cells[i] == NonoVision.FILLED ? NonoState.FILLED : r.cells[i] == NonoVision.CROSS ? NonoState.CROSS : NonoState.EMPTY;
        }
        if (startHearts > 0 && r.hearts >= 0) s.mistakes = Math.max(0, startHearts - r.hearts);
        s.maxMistakes = startHearts > 0 ? startHearts : 3;
        return s;
    }

    private void onObservation(NonoVision.Reading r, boolean nextButtonVisible) {
        if (state != RUNNING) return;

        // 1) No puzzle on screen.
        if (r.grid == null) {
            if (pending != null && (nextButtonVisible || ++noChange >= 3)) {
                // The last fill completed the picture: the game moved to its celebration screen.
                finishPending(NonoAdapter.R_CORRECT + NonoAdapter.R_SOLVED, true, true, "solved (level complete screen)", null);
                return;
            }
            if (++uncertain >= MAX_UNCERTAIN) { pause("can't see a puzzle (" + r.error + ")"); return; }
            setInfo("Looking for the puzzle… (" + r.error + ")");
            observe(500);
            return;
        }
        // 2) Uncertain reading: retry, then pause safely (and keep one screenshot).
        if (r.error != null) {
            if (++uncertain >= MAX_UNCERTAIN) {
                CaptureService.saveFrame(r.frame, true);
                log("{\"event\":\"uncertain\",\"why\":" + Recorder.q(r.error) + ",\"clues\":" + Recorder.q(r.cluesText()) + "}");
                pause("observation uncertain: " + r.error + " (screenshot saved)");
                return;
            }
            setInfo("Unsure what I see (" + r.error + ") — looking again…");
            observe(400);
            return;
        }
        if (startHearts < 0 && r.hearts > 0) startHearts = r.hearts;

        // 3) Waiting for the result of the last tap.
        if (pending != null) {
            int got = r.cells[pending.cell];
            boolean heartLost = pending.heartsBefore > 0 && r.hearts >= 0 && r.hearts < pending.heartsBefore;
            boolean redMark = r.redCells > pending.redBefore;
            if (got == NonoVision.FILLED && !heartLost) {
                if (!stable(r)) return;
                boolean solved = allFilled(r);
                finishPending(NonoAdapter.R_CORRECT + (solved ? NonoAdapter.R_SOLVED : 0), solved, solved,
                        solved ? "solved" : "filled", r);
                return;
            }
            if (heartLost || redMark || got == NonoVision.CROSS) {
                if (!stable(r)) return;
                boolean out = r.hearts == 0;
                finishPending(NonoAdapter.R_MISTAKE + (out ? NonoAdapter.R_FAILED : 0), out, false,
                        out ? "mistake — out of hearts" : "mistake (heart lost)", r);
                return;
            }
            // Nothing visible yet: animations may still be running.
            if (++noChange <= 4) { observe(300); return; }
            log("{\"event\":\"no_effect\",\"cell\":" + pending.cell + "}");
            pending = null;
            pause("the tap didn't change the cell — is the panel or something else covering the game?");
            return;
        }

        uncertain = 0;
        lastGood = r;
        act(r);
    }

    private NonoVision.Reading confirmCandidate;

    /** Requires the same outcome on two consecutive reads (the board has settled). */
    private boolean stable(NonoVision.Reading r) {
        if (confirmCandidate != null && java.util.Arrays.equals(confirmCandidate.cells, r.cells) && confirmCandidate.hearts == r.hearts) {
            confirmCandidate = null;
            return true;
        }
        confirmCandidate = r;
        observe(220);
        return false;
    }

    private static boolean allFilled(NonoVision.Reading r) {
        if (r.frozen > 0) return false;   // can't tell from hidden clues; the game screen will tell us
        int need = 0, have = 0;
        for (int[] c : r.rowClues) for (int v : c) need += v;
        for (int v : r.cells) if (v == NonoVision.FILLED) have++;
        return have >= need;
    }

    // ------------------------------------------------------------------ reward + learning

    private void finishPending(float reward, boolean done, boolean win, String note, NonoVision.Reading after) {
        Pending p = pending;
        pending = null;
        confirmCandidate = null;
        noChange = 0;
        Transition t = new Transition();
        t.source = "real";
        t.episode = episode;
        t.step = step++;
        t.time = System.currentTimeMillis();
        t.state = p.stateBefore;
        t.action = p.cand.label;
        t.features = p.cand.features;
        t.reward = reward;
        t.done = done;
        t.explored = p.choice.explored;
        t.q = p.choice.q;
        t.note = note;
        t.confidence = after != null ? Math.max(0f, 1f - after.worstDigit / 0.08f) : 1f;
        synchronized (hub.lock) {
            if (!done && after != null) {
                NonoState ns = toState(after);
                t.nextState = hub.adapter.describe(ns);
                t.nextBest = hub.agent.bestFeatures(hub.adapter.candidates(ns));
            } else t.nextState = note;
            hub.agent.record(t);
        }
        episodeReward += reward;
        lastOutcome = String.format(Locale.US, "%s → %+.0f (%s)", p.cand.label, reward, note);
        log("{\"event\":\"step\",\"episode\":" + episode + ",\"action\":" + Recorder.q(p.cand.label) + ",\"reward\":" + reward
                + ",\"note\":" + Recorder.q(note) + ",\"q\":" + p.choice.q + ",\"explored\":" + p.choice.explored + "}");
        if (done) { endEpisode(win); return; }
        if (after != null) { lastGood = after; act(after); } else observe(300);
    }

    private void endEpisode(boolean win) {
        synchronized (hub.lock) { hub.agent.endEpisode(win, episodeReward); }
        hub.experiences.flush();
        if (hub.agent.learning()) hub.autosave();
        log("{\"event\":\"episode_end\",\"episode\":" + episode + ",\"win\":" + win + ",\"reward\":" + episodeReward
                + ",\"steps\":" + step + ",\"blocked\":" + blocked + "}");
        String msg = (win ? "✓ Solved" : "✗ Out of hearts") + String.format(Locale.US, " · %d moves · reward %+.0f%s",
                step, episodeReward, blocked > 0 ? " · " + blocked + " blocked by safety net" : "");
        state = IDLE;
        refresh();
        setInfo(msg + "\n" + hub.agent.stats().line());
        setCompact(false);
        if (win) handler.postDelayed(host::nonoAiSolved, 1500);
    }

    // ------------------------------------------------------------------ acting

    private void act(NonoVision.Reading r) {
        if (state != RUNNING) return;
        // Fill mode must be on, or taps would place crosses.
        if (r.toggle == 0) {
            setInfo("Switching the game to fill mode…");
            tap(r.toggleX, r.toggleY, () -> observe(450));
            return;
        }
        if (r.toggle < 0) {
            if (++uncertain >= MAX_UNCERTAIN) { pause("can't see the fill/cross switch"); return; }
            observe(400);
            return;
        }
        if (panelOver(r)) {
            int[] pr = host.panelRect();
            int h = pr[3] - pr[1];
            int y = Math.round(r.toggleY + r.grid.cell * 0.8f);
            if (y + h < r.frameH) host.movePanel(y); else host.movePanel(Math.max(0, r.grid.top - h - host.dp(8)));
            observe(350);
            return;
        }

        NonoState s = toState(r);
        List<GameAdapter.Candidate> cands;
        Agent.Choice ch;
        GameAdapter.Candidate c;
        String desc;
        int[] certain = safetyNet() ? certainCells(r) : null;
        synchronized (hub.lock) {
            cands = new ArrayList<>(hub.adapter.candidates(s));
            desc = hub.adapter.describe(s);
            while (true) {
                if (cands.isEmpty()) { c = null; ch = null; break; }
                ch = hub.agent.choose(cands);
                c = cands.get(ch.index);
                int cell = (Integer) c.action;
                if (certain == null || certain[cell] != NonoSolver.EMPTY) break;
                // Safety net: this fill is provably wrong. Don't do it; in Training, learn from it.
                blocked++;
                if (hub.agent.learning()) {
                    Transition t = new Transition();
                    t.source = "real";
                    t.episode = episode;
                    t.step = step;
                    t.time = System.currentTimeMillis();
                    t.state = desc;
                    t.action = c.label;
                    t.features = c.features;
                    t.reward = NonoAdapter.R_MISTAKE;
                    t.done = true;   // counterfactual: no next state
                    t.note = "blocked by safety net (would be a mistake)";
                    t.explored = ch.explored;
                    t.q = ch.q;
                    hub.agent.record(t);
                }
                cands.remove(ch.index);
            }
        }
        // Outside training, a negative value means the model itself expects to lose: stop and say so
        // instead of risking a heart.
        if (c != null && hub.agent.mode != Agent.Mode.TRAINING && ch.q < 0) {
            log("{\"event\":\"unsure\",\"action\":" + Recorder.q(c.label) + ",\"q\":" + ch.q + "}");
            pause(String.format(Locale.US, "not confident about any move (best: %s, Q %.2f)%s", c.label, ch.q,
                    r.frozen > 0 ? " — the rest depends on frozen clues" : " — train more in the AI Lab"));
            return;
        }
        if (c == null) {
            if (r.frozen > 0) { pause("❄ the remaining cells depend on frozen clues — fill one yourself, then Resume"); return; }
            pause("no cell left to fill, but the game hasn't finished — check the screen");
            return;
        }
        Pending p = new Pending();
        p.cell = (Integer) c.action;
        p.cand = c;
        p.choice = ch;
        p.stateBefore = desc;
        p.heartsBefore = r.hearts;
        p.redBefore = r.redCells;
        p.tappedAt = System.currentTimeMillis();
        pending = p;
        noChange = 0;
        lastAction = String.format(Locale.US, "%s · Q %.2f (next best %.2f)%s", c.label, ch.q, ch.secondQ, ch.explored ? " · exploring" : "");
        setInfo("Sees: " + desc + " · hearts " + r.hearts + (r.frozen > 0 ? " · ❄" + r.frozen : "")
                + "\nDoes: " + lastAction + "\nLast: " + lastOutcome);
        int row = p.cell / r.grid.cols, col = p.cell % r.grid.cols;
        tap(r.grid.cx(col), r.grid.cy(row), () -> observe(330));
    }

    /** Cells the exact solver can prove from what's visible (for the optional safety net). */
    private int[] certainCells(NonoVision.Reading r) {
        try {
            int[] start = new int[r.cells.length];
            java.util.Arrays.fill(start, NonoSolver.UNKNOWN);
            for (int i = 0; i < start.length; i++) if (r.cells[i] == NonoVision.FILLED) start[i] = NonoSolver.FILLED;
            NonoSolver.Result res = new NonoSolver().solveCertain(r.rowClues, r.colClues, start, 1500);
            return res.grid;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean panelOver(NonoVision.Reading r) {
        int[] pr = host.panelRect();
        if (pr == null) return false;
        boolean grid = pr[0] < r.grid.right && pr[2] > r.grid.left && pr[1] < r.grid.bottom && pr[3] > r.grid.top;
        boolean sw = r.toggleX >= pr[0] && r.toggleX <= pr[2] && r.toggleY >= pr[1] && r.toggleY <= pr[3];
        return grid || sw;
    }

    private void tap(float x, float y, Runnable after) {
        if (state != RUNNING) return;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build();
        boolean ok = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { handler.post(after); }
            @Override public void onCancelled(GestureDescription d) {
                handler.post(() -> { pending = null; pause("a tap was cancelled (did you touch the screen?)"); });
            }
        }, handler);
        if (!ok) { pending = null; pause("tap rejected — is the tap service still on?"); }
    }
}
