package com.nickrocks.pixelflowbot;

import android.content.Context;
import android.util.Log;

import com.nickrocks.pixelflowbot.game.nonogram.PuzzleGenerator;
import com.nickrocks.pixelflowbot.game.nonogram.agent.Evaluator;
import com.nickrocks.pixelflowbot.game.nonogram.agent.LearnerPlayer;
import com.nickrocks.pixelflowbot.game.nonogram.agent.PlayAdapter;
import com.nickrocks.pixelflowbot.game.nonogram.agent.Player;
import com.nickrocks.pixelflowbot.game.nonogram.agent.RandomPlayer;
import com.nickrocks.pixelflowbot.game.nonogram.agent.SolverPlayer;
import com.nickrocks.pixelflowbot.game.nonogram.agent.Trainer;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.ModelStore;
import com.nickrocks.pixelflowbot.learn.SimRunner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Process-wide home of the in-app Nonogram's puzzles and agents.
 *
 * Two learning agents with separate model files, so solver help is never mixed into the pure one:
 *   files/ai/nonogram-game/            Learner (basic + heuristic features only)
 *   files/ai/nonogram-game-assisted/   Learner + solver hints (adds single-line solver features)
 * The constraint solver is a separate baseline and is never trained.
 */
final class NonoGameHub {
    private static final String TAG = "PFBot.NonoGame";
    private static NonoGameHub instance;

    final PuzzleGenerator gen = new PuzzleGenerator();
    final SolverPlayer solver = new SolverPlayer();
    final LearnerPlayer[] learners = new LearnerPlayer[2];   // 0 = pure, 1 = solver-assisted
    final ModelStore[] stores = new ModelStore[2];
    final String[] loadNotes = new String[2];
    final File dir;
    final Object lock = new Object();

    // Training state (read by the UI).
    volatile boolean training, stopTraining;
    volatile int trainingWhich;
    volatile String trainLine = "";
    volatile float stepsPerSecond;
    final List<float[]> curve = new java.util.concurrent.CopyOnWriteArrayList<>();   // {episode, recent win rate}

    // Evaluation state.
    volatile boolean evaluating, stopEval;
    volatile String evalText = "";

    static synchronized NonoGameHub get(Context c) {
        if (instance == null) instance = new NonoGameHub(c.getApplicationContext());
        return instance;
    }

    private NonoGameHub(Context ctx) {
        dir = new File(ctx.getFilesDir(), "ai");
        for (int k = 0; k < 2; k++) {
            PlayAdapter ad = new PlayAdapter(k == 1);
            Agent a = new Agent(ad.id(), ad.featureNames(), System.nanoTime());
            PlayAdapter.configure(a);
            stores[k] = new ModelStore(new File(dir, ad.id()), ad.id());
            try {
                loadNotes[k] = stores[k].loadLatest(a) ? "loaded saved model" : "new, untrained";
            } catch (Exception e) {
                loadNotes[k] = "couldn't load the saved model, starting fresh";
                Log.w(TAG, "load failed", e);
            }
            PlayAdapter.configure(a);
            a.mode = Agent.Mode.PLAY;
            learners[k] = new LearnerPlayer(a, ad);
        }
        File f = new File(dir, "nonogram-game-eval.txt");
        if (f.exists()) {
            try { evalText = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8"); } catch (IOException ignored) { }
        }
    }

    Player player(int which) {
        switch (which) {
            case 1: return learners[0];
            case 2: return learners[1];
            default: return solver;
        }
    }

    // ------------------------------------------------------------------ training

    /** Trains learner {@code which} (0 pure, 1 assisted) on TRAINING puzzles until stopped. */
    void startTraining(int which, int[] sizes) {
        if (training) return;
        training = true;
        stopTraining = false;
        trainingWhich = which;
        synchronized (lock) { curve.clear(); }
        Thread t = new Thread(() -> {
            LearnerPlayer lp = learners[which];
            Trainer tr = new Trainer(gen);
            tr.setSizes(sizes);
            long t0 = System.currentTimeMillis(), steps0;
            int sinceSave = 0;
            synchronized (lock) {
                lp.agent.mode = Agent.Mode.TRAINING;
                steps0 = lp.agent.stats().steps;
                tr.episodeId = lp.agent.stats().episodes + 1;
            }
            try {
                while (!stopTraining) {
                    synchronized (lock) {
                        SimRunner.Result r = tr.episode(lp, () -> stopTraining);
                        float secs = (System.currentTimeMillis() - t0) / 1000f;
                        stepsPerSecond = secs > 0 ? (lp.agent.stats().steps - steps0) / secs : 0;
                        trainLine = String.format(Locale.US, "%s · ε %.2f · last %s in %d steps%n%s",
                                lp.name(), lp.agent.epsilon(), r.win ? "solved" : "not solved", r.steps, lp.agent.stats().line());
                        curve.add(new float[]{lp.agent.stats().episodes, lp.agent.stats().recentWinRate()});
                        if (curve.size() > 600) curve.remove(0);
                    }
                    if (++sinceSave >= 100) { sinceSave = 0; save(which); }
                }
            } catch (RuntimeException e) {
                trainLine = "Training stopped: " + e;
                Log.w(TAG, "training failed", e);
            } finally {
                synchronized (lock) { lp.agent.mode = Agent.Mode.PLAY; }
                save(which);
                training = false;
            }
        }, "nono-train");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    void stopTraining() { stopTraining = true; }

    void save(int which) {
        synchronized (lock) {
            try { stores[which].autosave(learners[which].agent); } catch (IOException e) { Log.w(TAG, "autosave failed", e); }
        }
    }

    ModelStore.Info saveVersion(int which, String note) throws IOException {
        synchronized (lock) { return stores[which].saveVersion(learners[which].agent, note); }
    }

    void reset(int which) {
        synchronized (lock) {
            Agent a = learners[which].agent;
            a.resetLearning();
            PlayAdapter.configure(a);
            a.mode = Agent.Mode.PLAY;
            stores[which].deleteAll();
            curve.clear();
        }
    }

    // ------------------------------------------------------------------ evaluation

    /** Scores Random, the solver and both learners on the EVALUATION puzzles of the given sizes. */
    void startEvaluation(int[] sizes) {
        if (evaluating || training) return;
        evaluating = true;
        stopEval = false;
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append(new java.text.SimpleDateFormat("MMM d HH:mm", Locale.US).format(new java.util.Date()))
                    .append(" — evaluation puzzles only (never trained on)\n");
            try {
                Player[] ps = {new RandomPlayer(1), solver, learners[0], learners[1]};
                for (int size : sizes) {
                    sb.append('\n').append(size).append('×').append(size).append(" (")
                            .append(PuzzleGenerator.evalCount(size)).append(" puzzles)\n");
                    for (Player p : ps) {
                        if (stopEval) break;
                        evalText = sb + "running " + p.name() + "…";
                        Evaluator.Report r;
                        synchronized (lock) { r = Evaluator.run(p, gen, size, 999, () -> stopEval); }
                        sb.append(String.format(Locale.US, "%-22s %5.1f%% solved · %.2f mistakes · %.0f steps%n",
                                p.name(), 100 * r.solveRate(), r.puzzles == 0 ? 0 : r.mistakes / (float) r.puzzles,
                                r.puzzles == 0 ? 0 : r.steps / (float) r.puzzles));
                    }
                }
                sb.append("\nRandom and the constraint solver are baselines. Only the two learners learn; "
                        + "\"+ solver hints\" is given single-line solver output as features.");
            } catch (RuntimeException e) {
                sb.append("\nEvaluation failed: ").append(e);
            }
            evalText = sb.toString();
            try (FileWriter w = new FileWriter(new File(dir, "nonogram-game-eval.txt"))) {
                dir.mkdirs();
                w.write(evalText);
            } catch (IOException ignored) { }
            evaluating = false;
        }, "nono-eval").start();
    }
}
