package com.nickrocks.pixelflowbot;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.nickrocks.pixelflowbot.core.EpisodeRecorder;
import com.nickrocks.pixelflowbot.core.GameAction;
import com.nickrocks.pixelflowbot.core.GameProfile;
import com.nickrocks.pixelflowbot.core.KnownReward;
import com.nickrocks.pixelflowbot.core.Lab;
import com.nickrocks.pixelflowbot.core.LabGame;
import com.nickrocks.pixelflowbot.game.dodge.DodgeGame;
import com.nickrocks.pixelflowbot.game.dodge.DodgeState;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.Env;
import com.nickrocks.pixelflowbot.learn.GameAdapter;
import com.nickrocks.pixelflowbot.learn.LinearQ;
import com.nickrocks.pixelflowbot.learn.ModelStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * One simulated lab game (Block Puzzle, Dodge): play it yourself, watch a baseline or the
 * learner, train the learner, evaluate everything on held-out games, manage model versions.
 * Every game played here is stored as experience in the game's profile (your games as USER
 * demonstrations, the AI's as PLAY/BASELINE).
 */
public class LabActivity extends Activity {
    static final String EXTRA_PROFILE = "profile";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ctl<?> ctl;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        GameProfile p = AgentPlatform.get(this).profile(getIntent().getStringExtra(EXTRA_PROFILE));
        if (p == null || !LabGames.has(p.adapterId)) { finish(); return; }
        ctl = make(LabGames.runtime(this, p.adapterId), p);
        ScrollView sv = new ScrollView(this);
        sv.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.BG, Ui.BG_2}));
        sv.addView(ctl.build());
        setContentView(sv);
        ctl.newGame(System.nanoTime());
    }

    private <S> Ctl<S> make(LabRuntime<S> rt, GameProfile p) { return new Ctl<>(rt, p); }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        if (ctl != null) ctl.stopRunning();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (ctl != null) ctl.endEpisode("left the screen");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (ctl != null) ctl.refreshPanels();
            handler.postDelayed(this, 500);
        }
    };

    // =====================================================================================

    private final class Ctl<S> {
        final LabRuntime<S> rt;
        final LabGame<S> game;
        final GameProfile profile;
        final LabScreen<S> screen;
        final boolean realTime;

        Env<S> env;
        S state;
        long seed;
        Object lastAction;
        boolean over, running;
        int player = 0;                 // 0 you, 1 hand-written rule, 2 learner
        boolean fast;
        Object pendingMove;
        Agent playAgent;                // a copy of the learner's weights for watching (training keeps the original)
        final Random baseRnd = new Random(7);
        EpisodeRecorder rec;
        final KnownReward known = new KnownReward();
        long moves;

        TextView status, decision, trainLive, weights, evalText, modelInfo, dataInfo;
        Button[] playerBtns;
        Button playBtn, stepBtn, speedBtn, trainBtn, evalBtn;
        Curve curve;

        Ctl(LabRuntime<S> rt, GameProfile p) {
            this.rt = rt;
            this.game = rt.game;
            this.profile = p;
            this.screen = LabGames.screen(LabActivity.this, game.id());
            this.realTime = game.tickMs() > 0;
            screen.setMoveListener(a -> {
                if (player != 0 || over) return;
                if (realTime) {
                    pendingMove = a;
                    if (!running) startRunning();
                } else {
                    apply(a, "you", "you", Float.NaN);
                }
            });
        }

        View build() {
            LinearLayout root = new LinearLayout(LabActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(18), dp(14), dp(28));
            TextView t = text(game.title(), 28, Color.WHITE);
            t.setTypeface(Ui.BLACK);
            root.addView(t);
            root.addView(note(screen.help() + (realTime ? " (" + (1000 / game.tickMs()) + " ticks per second)" : "")));

            View v = screen.view();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            root.addView(v, lp);
            status = text("", 14, Ui.TEXT);
            status.setTypeface(Ui.MEDIUM);
            status.setGravity(Gravity.CENTER);
            status.setPadding(0, dp(8), 0, dp(4));
            root.addView(status);

            String rule = game.baselines().get(game.baselines().size() - 1).name();
            playerBtns = chips(root, new String[]{"You", rule, "Learner"}, i -> { stopRunning(); player = i; refreshPlayer(); });
            LinearLayout ctl = new LinearLayout(LabActivity.this);
            ctl.addView(small("New game", Ui.MUTED, x -> newGame(System.nanoTime())), weight());
            ctl.addView(small("Restart", Ui.MUTED, x -> newGame(seed)), weight());
            stepBtn = small("Step", Ui.MUTED, x -> { stopRunning(); aiMove(); });
            if (!realTime) ctl.addView(stepBtn, weight());
            playBtn = small("▶ Play", Ui.ACCENT, x -> { if (running) stopRunning(); else startRunning(); });
            ctl.addView(playBtn, weight());
            root.addView(ctl);
            if (!realTime) {
                speedBtn = small("", Ui.MUTED, x -> { fast = !fast; refreshPlayer(); });
                root.addView(speedBtn);
            }
            decision = text("", 12, Ui.TEXT);
            decision.setTypeface(Ui.MONO);
            decision.setPadding(dp(4), dp(8), 0, 0);
            root.addView(decision);

            // Train
            root.addView(section("TRAIN THE LEARNER"));
            LinearLayout tc = card();
            LinearLayout tr = new LinearLayout(LabActivity.this);
            trainBtn = small("▶ Train", Ui.ACCENT, x -> {
                if (rt.training) rt.stopTraining();
                else if (rt.evaluating) toast("Wait for the evaluation to finish.");
                else rt.startTraining();
            });
            tr.addView(trainBtn, weight());
            tr.addView(small("Save version", Ui.INFO, x -> saveVersion(false)), weight());
            tr.addView(small("Reset…", Ui.DANGER, x -> resetDialog()), weight());
            tc.addView(tr);
            LinearLayout tr2 = new LinearLayout(LabActivity.this);
            tr2.addView(small("✓ Mark stable", Ui.MUTED, x -> saveVersion(true)), weight());
            tr2.addView(small("↶ Roll back to stable", Ui.MUTED, x -> rollback()), weight());
            tc.addView(tr2);
            trainLive = mono();
            tc.addView(trainLive);
            curve = new Curve(this);
            tc.addView(curve, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)));
            modelInfo = text("", 11, Ui.TEXT_FAINT);
            modelInfo.setPadding(dp(4), dp(6), 0, 0);
            tc.addView(modelInfo);
            TextView wl = text("What it has learned (strongest weights; + favours a move, − avoids it):", 12, Ui.TEXT_DIM);
            wl.setPadding(dp(4), dp(10), 0, dp(2));
            tc.addView(wl);
            weights = mono();
            tc.addView(weights);
            tc.addView(note("Policy learning: linear Q-learning from the game's own rewards, on training seeds only. "
                    + "The inputs are measurements of the position; their weights start at zero and are learned. "
                    + "The baseline rules are hand-written and never change."));
            root.addView(tc);

            // Evaluate
            root.addView(section("EVALUATE"));
            LinearLayout ec = card();
            evalBtn = small("Run evaluation", Ui.ACCENT, x -> {
                if (rt.evaluating) rt.stopEval = true;
                else if (rt.training) toast("Stop training first.");
                else { stopRunning(); rt.startEvaluation(game.id().equals(DodgeGame.ID) ? 40 : 30, lagTest()); }
            });
            ec.addView(evalBtn);
            evalText = mono();
            ec.addView(evalText);
            ec.addView(note("Fixed held-out games (separate seeds from training). The learner plays greedily and doesn't learn here."
                    + (realTime ? " Also measures the learner and the rule with 100 ms and 200 ms of simulated screen/tap lag." : "")));
            root.addView(ec);

            // Data
            root.addView(section("EXPERIENCE"));
            LinearLayout dc = card();
            dataInfo = text("", 12, Ui.TEXT_DIM);
            dc.addView(dataInfo);
            dc.addView(note("Every game on this screen is saved to the \"" + profile.name + "\" profile: yours as demonstrations (USER), "
                    + "the AI's as PLAY / BASELINE, with rewards and their source. Demonstrations are recorded but not yet used for training."));
            root.addView(dc);

            refreshPlayer();
            return root;
        }

        // ---------------------------------------------------------------- playing

        void newGame(long s) {
            stopRunning();
            endEpisode(over ? null : "new game");
            seed = s;
            env = game.newEnv();
            state = env.reset(new Random(s));
            over = false;
            lastAction = null;
            moves = 0;
            pendingMove = null;
            playAgent = null;
            screen.show(state, null);
            rec = new EpisodeRecorder(AgentPlatform.get(LabActivity.this).experience, profile, modeName(), known);
            rec.begin(game.perception().observe(state, profile), "seed " + Long.toHexString(s));
            decision.setText(player == 0 ? (realTime ? "Tap to start." : "Your move.") : "Press Play or Step.");
            refreshStatus();
        }

        String modeName() { return player == 0 ? "USER" : player == 2 ? "PLAY" : "BASELINE"; }

        void endEpisode(String why) {
            if (rec != null && rec.open()) rec.end(why == null ? "ended" : why, null);
        }

        void startRunning() {
            if (over) { toast("Game over — New game or Restart."); return; }
            if (player == 2) playAgent = snapshotLearner();
            running = true;
            refreshPlayer();
            loop.run();
        }

        void stopRunning() {
            running = false;
            handler.removeCallbacks(loop);
            if (playBtn != null) refreshPlayer();
        }

        final Runnable loop = new Runnable() {
            @Override public void run() {
                if (!running || over) { stopRunning(); return; }
                if (player == 0) {
                    Object a = pendingMove != null ? pendingMove : screen.idleAction();
                    pendingMove = null;
                    apply(a, "you", "you", Float.NaN);
                } else aiMove();
                if (running && !over) handler.postDelayed(this, realTime ? game.tickMs() : fast ? 60 : 450);
            }
        };

        /** A copy of the learner's current weights, so watching never interferes with training. */
        Agent snapshotLearner() {
            Agent a = new Agent(game.id(), game.adapter().featureNames(), 1);
            a.model = LinearQ.fromJson(com.nickrocks.pixelflowbot.learn.Json.obj(com.nickrocks.pixelflowbot.learn.Json.parse(rt.agent.model.toJson())));
            a.mode = Agent.Mode.PLAY;
            a.playEps = 0f;
            return a;
        }

        void aiMove() {
            if (over || player == 0) return;
            List<GameAdapter.Candidate> cands = game.adapter().candidates(state);
            if (cands.isEmpty()) return;
            long t0 = SystemClock.uptimeMillis();
            int i;
            String who, why;
            float q = Float.NaN;
            if (player == 2) {
                if (playAgent == null || !running) playAgent = snapshotLearner();
                Agent.Choice ch = playAgent.choose(cands);
                i = ch.index;
                q = ch.q;
                who = "Learned policy";
                why = rt.trainedEpisodes() == 0 ? "untrained — all weights 0, so it picks at random"
                        : String.format(Locale.US, "highest learned value %.2f (next best %.2f)", ch.q, ch.secondQ);
            } else {
                LabGame.Baseline<S> b = game.baselines().get(game.baselines().size() - 1);
                i = b.choose(state, cands, baseRnd);
                who = b.name() + " (baseline, not learned)";
                why = "fixed rule";
            }
            decisionMs = SystemClock.uptimeMillis() - t0;
            apply(cands.get(i).action, cands.get(i).label, who + " · " + why, q);
        }

        long decisionMs;

        void apply(Object action, String label, String who, float q) {
            if (over) return;
            List<GameAdapter.Candidate> cands = game.adapter().candidates(state);
            boolean legal = false;
            for (GameAdapter.Candidate c : cands) if (c.action.equals(action)) { legal = true; break; }
            if (!legal) return;
            long t0 = System.currentTimeMillis();
            Env.Outcome<S> o = env.step(state, action);
            state = o.next;
            lastAction = action;
            moves++;
            if (rec != null) {
                GameAction ga = GameAction.logical(label);
                ga.source = player == 0 ? "user" : player == 2 ? "policy" : "baseline";
                known.next(o.reward, game.title() + " env: " + o.note, o.done, o.win);
                rec.step(ga, t0, player == 0 ? 0 : decisionMs, game.perception().observe(state, profile));
            }
            if (o.done) {
                over = true;
                endEpisode(o.win ? "won: " + o.note : o.note);
                stopRunning();
            }
            screen.show(state, lastAction);
            if (player != 0 && (!realTime || moves % 10 == 0 || over)) {
                decision.setText(who + "\n" + label + "  →  " + String.format(Locale.US, "%+.1f", o.reward) + " (" + o.note + ")");
            } else if (player == 0 && !realTime) {
                decision.setText("You: " + label + "  →  " + String.format(Locale.US, "%+.1f", o.reward) + " (" + o.note + ")");
            }
            refreshStatus();
        }

        void refreshStatus() {
            String sc = String.format(Locale.US, "%s %.1f", game.scoreName(), game.score(state));
            status.setText((over ? "Game over · " : "") + sc + " · " + moves + (realTime ? " ticks" : " moves"));
            status.setTextColor(over ? 0xFFFCD34D : Ui.TEXT);
        }

        void refreshPlayer() {
            int[] cols = {Ui.GAME_NONO, Ui.INFO, Ui.GAME_AI};
            for (int i = 0; i < 3; i++) Ui.tint(playerBtns[i], i == player ? cols[i] : Ui.MUTED, dp(12));
            playBtn.setText(running ? "⏸ Pause" : player == 0 && realTime ? "▶ Start" : "▶ Play");
            Ui.tint(playBtn, running ? Ui.DANGER : Ui.ACCENT, dp(12));
            stepBtn.setEnabled(player != 0);
            stepBtn.setAlpha(player != 0 ? 1f : 0.4f);
            if (speedBtn != null) speedBtn.setText(fast ? "AI speed: fast" : "AI speed: slow");
            if (rec != null && rec.open() && moves == 0) {
                // Nothing played yet: re-open so the episode is filed under the right player.
                rec.end("player changed", null);
                rec = new EpisodeRecorder(AgentPlatform.get(LabActivity.this).experience, profile, modeName(), known);
                rec.begin(game.perception().observe(state, profile), "seed " + Long.toHexString(seed));
            }
        }

        // ---------------------------------------------------------------- training / evaluation panels

        void refreshPanels() {
            trainBtn.setText(rt.training ? "■ Stop" : "▶ Train");
            Ui.tint(trainBtn, rt.training ? Ui.DANGER : Ui.ACCENT, dp(12));
            trainLive.setText(rt.training ? rt.trainLine + String.format(Locale.US, "%n%.0f steps/s", rt.stepsPerSecond)
                    : rt.trainedEpisodes() == 0 ? "Not trained yet (" + rt.loadNote + ")."
                    : "Trained on " + rt.trainedEpisodes() + " games · " + rt.agent.model.updates + " updates.");
            weights.setText(rt.agent.model.updates == 0 ? "Nothing yet." : rt.agent.model.explain(8).trim());
            List<ModelStore.Info> vs = rt.store.list();
            int stable = rt.store.stableVersion();
            modelInfo.setText((vs.isEmpty() ? "No saved versions yet (autosaves every 100 games)." : "Latest saved: " + vs.get(0))
                    + "\n" + (stable < 0 ? "No stable version marked." : "Stable: v" + stable + " (Roll back returns here)"));
            curve.invalidate();
            evalBtn.setText(rt.evaluating ? "■ Stop evaluation" : "Run evaluation");
            Ui.tint(evalBtn, rt.evaluating ? Ui.DANGER : Ui.ACCENT, dp(12));
            evalText.setText(rt.evalText.isEmpty() ? "No results yet." : rt.evalText);
            com.nickrocks.pixelflowbot.core.ExperienceStore.Summary s = AgentPlatform.get(LabActivity.this).experience.summary(profile.id);
            dataInfo.setText(String.format(Locale.US, "%d games · %d steps stored · %.1f KB", s.episodes, s.steps, s.bytes / 1024f));
        }

        /** Dodge only: how the learner and the rule cope with simulated capture/tap lag. */
        Runnable lagTest() {
            if (!game.id().equals(DodgeGame.ID)) return null;
            return () -> {
                StringBuilder out = rt.extraOut;
                out.append("\nWith simulated lag (screen capture + tap):\n");
                for (int[] d : new int[][]{{1, 1}, {2, 2}}) {
                    DodgeGame g = new DodgeGame(d[0], d[1]);
                    LabGame.Baseline<DodgeState> rule = g.baselines().get(2);
                    Lab.Report rr = Lab.evaluate(g, "rule", Lab.of(rule, 7), 30, g.maxSteps(), () -> rt.stopEval);
                    Lab.Report lr;
                    synchronized (rt.lock) { lr = Lab.evaluate(g, "learner", rt.agent, 30, g.maxSteps(), () -> rt.stopEval); }
                    out.append(String.format(Locale.US, "  %d ms lag: rule %.1f s · learner %.1f s%n", (d[0] + d[1]) * DodgeGame.TICK_MS,
                            rr.meanScore, lr.meanScore));
                    rt.evalText = out.toString();
                }
                out.append("If a phone's capture + tap delay is above ~50 ms, reflex games like this one are out of reach.");
            };
        }

        void saveVersion(boolean stable) {
            try {
                ModelStore.Info i;
                synchronized (rt.lock) { i = rt.store.saveVersion(rt.agent, stable ? "stable" : ""); }
                if (stable) rt.store.markStable(i.version);
                toast((stable ? "Marked stable: v" : "Saved v") + i.version);
            } catch (Exception e) { toast("Failed: " + e.getMessage()); }
            refreshPanels();
        }

        void rollback() {
            if (rt.training) { toast("Stop training first."); return; }
            int v = rt.store.stableVersion();
            if (v < 0) { toast("No stable version yet — Mark stable when the model is good."); return; }
            new AlertDialog.Builder(LabActivity.this).setTitle("Roll back to v" + v + "?")
                    .setMessage("The current weights are replaced by the stable version. Saved versions are kept.")
                    .setPositiveButton("Roll back", (d, w) -> {
                        try {
                            synchronized (rt.lock) {
                                rt.store.rollbackToStable(rt.agent);
                                game.configure(rt.agent);
                                rt.agent.mode = Agent.Mode.PLAY;
                            }
                            playAgent = null;
                            toast("Rolled back to v" + v);
                        } catch (Exception e) { toast("Rollback failed: " + e.getMessage()); }
                        refreshPanels();
                    }).setNegativeButton("Cancel", null).show();
        }

        void resetDialog() {
            if (rt.training) { toast("Stop training first."); return; }
            new AlertDialog.Builder(LabActivity.this).setTitle("Reset the learner?")
                    .setMessage("Deletes its weights, statistics, saved versions and stable mark. Experience records are kept.")
                    .setPositiveButton("Reset", (d, w) -> {
                        synchronized (rt.lock) {
                            rt.agent.resetLearning();
                            game.configure(rt.agent);
                            rt.agent.mode = Agent.Mode.PLAY;
                            rt.store.deleteAll();
                            rt.curve.clear();
                        }
                        playAgent = null;
                        toast("Learner reset.");
                        refreshPanels();
                    }).setNegativeButton("Cancel", null).show();
        }
    }

    /** Training curve: average score of the last 50 training games. */
    private final class Curve extends View {
        private final Ctl<?> c;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(), label = new Paint(Paint.ANTI_ALIAS_FLAG);

        Curve(Ctl<?> c) {
            super(LabActivity.this);
            this.c = c;
            line.setColor(Ui.NEON);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(dp(2));
            grid.setColor(0x22FFFFFF);
            label.setColor(Ui.TEXT_FAINT);
            label.setTextSize(dp(10));
        }

        @Override protected void onDraw(Canvas cv) {
            List<float[]> pts = new ArrayList<>(c.rt.curve);
            float w = getWidth(), h = getHeight(), top = dp(14), bot = h - dp(4);
            for (int k = 0; k <= 4; k++) { float y = bot - (bot - top) * k / 4f; cv.drawLine(0, y, w, y, grid); }
            float max = 1;
            for (float[] p : pts) max = Math.max(max, p[1]);
            cv.drawText(String.format(Locale.US, "training: average %s of the last 50 games (top = %.0f)", c.game.scoreName(), max), 0, dp(10), label);
            if (pts.size() < 2) return;
            float e0 = pts.get(0)[0], e1 = pts.get(pts.size() - 1)[0];
            Path p = new Path();
            for (int i = 0; i < pts.size(); i++) {
                float x = e1 > e0 ? (pts.get(i)[0] - e0) / (e1 - e0) * w : 0;
                float y = bot - (bot - top) * pts.get(i)[1] / max;
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            cv.drawPath(p, line);
        }
    }

    // ------------------------------------------------------------------ widgets

    private interface IntCallback { void on(int i); }

    private Button[] chips(LinearLayout parent, String[] labels, IntCallback cb) {
        LinearLayout row = new LinearLayout(this);
        Button[] out = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            out[i] = small(labels[i], Ui.MUTED, v -> cb.on(k));
            row.addView(out[i], weight());
        }
        parent.addView(row);
        return out;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(4), dp(3), dp(2));
        return lp;
    }

    private TextView section(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setTypeface(Ui.BLACK);
        t.setLetterSpacing(0.2f);
        t.setPadding(dp(4), dp(22), 0, dp(8));
        return t;
    }

    private TextView note(String s) {
        TextView t = text(s, 11, Ui.TEXT_FAINT);
        t.setPadding(dp(4), dp(6), 0, 0);
        return t;
    }

    private TextView mono() {
        TextView t = text("", 11, Ui.TEXT);
        t.setTypeface(Ui.MONO);
        t.setPadding(dp(4), dp(8), 0, 0);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(10), dp(10), dp(12));
        c.setBackground(Ui.card(dp(18), dp(1), Ui.CARD_STROKE));
        return c;
    }

    private Button small(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13);
        b.setTypeface(Ui.MEDIUM);
        b.setBackground(Ui.button(color, dp(12)));
        b.setStateListAnimator(null);
        b.setPadding(dp(6), dp(8), dp(6), dp(8));
        b.setMinHeight(0);
        b.setMinimumHeight(dp(40));
        b.setOnClickListener(l);
        return b;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
