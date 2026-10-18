package com.nickrocks.pixelflowbot;

import android.util.Log;

import com.nickrocks.pixelflowbot.core.Lab;
import com.nickrocks.pixelflowbot.core.LabGame;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.ModelStore;
import com.nickrocks.pixelflowbot.learn.SimRunner;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One lab game's learning agent, model files, training thread and evaluation — the same code for
 * every simulated game (Block Puzzle, Dodge, and any added later).
 * Files: files/ai/&lt;gameId&gt;/model_vN.json, model_latest.json, stable.txt, eval.txt.
 */
final class LabRuntime<S> {
    private static final String TAG = "AIGameBot.Lab";

    final LabGame<S> game;
    final Agent agent;
    final ModelStore store;
    final File dir;
    final Object lock = new Object();
    String loadNote;

    volatile boolean training, stopTraining, evaluating, stopEval;
    volatile String trainLine = "", evalText = "";
    volatile float stepsPerSecond;
    final List<float[]> curve = new CopyOnWriteArrayList<>();   // {episode, avg score of last 50}
    private final float[] recent = new float[50];
    private int recentN;

    LabRuntime(LabGame<S> game, File aiDir) {
        this.game = game;
        dir = new File(aiDir, game.id());
        store = new ModelStore(dir, game.id());
        agent = new Agent(game.id(), game.adapter().featureNames(), System.nanoTime());
        game.configure(agent);
        try {
            loadNote = store.loadLatest(agent) ? "loaded saved model" : "new, untrained";
        } catch (Exception e) {
            loadNote = "couldn't load the saved model, starting fresh";
            Log.w(TAG, "load failed", e);
        }
        game.configure(agent);
        agent.mode = Agent.Mode.PLAY;
        File ev = new File(dir, "eval.txt");
        try { if (ev.exists()) evalText = new String(Files.readAllBytes(ev.toPath()), StandardCharsets.UTF_8); } catch (IOException ignored) { }
    }

    long trainedEpisodes() { return agent.stats.get(Agent.Mode.TRAINING).episodes; }

    // ------------------------------------------------------------------ training

    void startTraining() {
        if (training || evaluating) return;
        training = true;
        stopTraining = false;
        Thread t = new Thread(() -> {
            Lab.Trainer<S> tr = new Lab.Trainer<>(game, trainedEpisodes());
            long t0 = System.currentTimeMillis(), steps0;
            int sinceSave = 0;
            synchronized (lock) {
                agent.mode = Agent.Mode.TRAINING;
                steps0 = agent.stats().steps;
            }
            try {
                while (!stopTraining) {
                    synchronized (lock) {
                        SimRunner.Result r = tr.episode(agent, game.maxSteps(), () -> stopTraining);
                        float sc = (float) tr.lastScore();
                        recent[recentN++ % recent.length] = sc;
                        float avg = 0;
                        int n = Math.min(recentN, recent.length);
                        for (int i = 0; i < n; i++) avg += recent[i];
                        avg /= n;
                        float secs = (System.currentTimeMillis() - t0) / 1000f;
                        stepsPerSecond = secs > 0 ? (agent.stats().steps - steps0) / secs : 0;
                        trainLine = String.format(Locale.US, "ε %.3f · %d training games · last: %s, reward %.0f%naverage %s of the last %d: %.1f",
                                agent.epsilon(), trainedEpisodes(), r.win ? "won" : "lost", r.reward, game.scoreName(), n, avg);
                        curve.add(new float[]{trainedEpisodes(), avg});
                        if (curve.size() > 600) curve.remove(0);
                    }
                    if (++sinceSave >= 100) { sinceSave = 0; save(); }
                }
            } catch (RuntimeException e) {
                trainLine = "Training stopped: " + e;
                Log.w(TAG, "training failed", e);
            } finally {
                synchronized (lock) { agent.mode = Agent.Mode.PLAY; }
                save();
                training = false;
            }
        }, "lab-train-" + game.id());
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    void stopTraining() { stopTraining = true; }

    void save() {
        synchronized (lock) {
            try { store.autosave(agent); } catch (IOException e) { Log.w(TAG, "autosave failed", e); }
        }
    }

    // ------------------------------------------------------------------ evaluation

    /** Baselines and the learner on the fixed evaluation seeds; extra = more lines (e.g. lag tests). */
    void startEvaluation(int episodes, Runnable extra) {
        if (evaluating || training) return;
        evaluating = true;
        stopEval = false;
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append(new java.text.SimpleDateFormat("MMM d HH:mm", Locale.US).format(new java.util.Date()))
                    .append(" — ").append(episodes).append(" held-out games (never used for training)\n");
            try {
                for (LabGame.Baseline<S> b : game.baselines()) {
                    if (stopEval) break;
                    evalText = sb + "running " + b.name() + "…";
                    sb.append(row(Lab.evaluate(game, b.name() + " (baseline)", Lab.of(b, 7), episodes, game.maxSteps(), () -> stopEval)));
                }
                if (!stopEval) {
                    evalText = sb + "running the learner…";
                    Lab.Report r;
                    synchronized (lock) { r = Lab.evaluate(game, "Learner (" + trainedEpisodes() + " games)", agent, episodes, game.maxSteps(), () -> stopEval); }
                    sb.append(row(r));
                    agent.stats.get(Agent.Mode.EVALUATION).episode(false, (float) r.meanScore);
                }
                if (extra != null && !stopEval) { evalText = sb.toString(); extraOut = sb; extra.run(); }
            } catch (RuntimeException e) {
                sb.append("\nEvaluation failed: ").append(e);
            }
            evalText = sb.toString();
            try (FileWriter w = new FileWriter(new File(dir, "eval.txt"))) { dir.mkdirs(); w.write(evalText); } catch (IOException ignored) { }
            evaluating = false;
        }, "lab-eval-" + game.id()).start();
    }

    /** Where an {@code extra} evaluation step appends its lines. */
    StringBuilder extraOut;

    String row(Lab.Report r) {
        return String.format(Locale.US, "%-26s %s %6.1f ± %4.1f · wins %d/%d%n", r.player, game.scoreName(), r.meanScore,
                Double.isNaN(r.ci95()) ? 0 : r.ci95(), r.wins, r.episodes);
    }
}
