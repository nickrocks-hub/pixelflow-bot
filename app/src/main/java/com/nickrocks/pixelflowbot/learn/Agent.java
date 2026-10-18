package com.nickrocks.pixelflowbot.learn;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The learning agent, independent of any game.
 *
 *  - TRAINING:   ε-greedy exploration (ε decays from 0.30 to 0.03 over the first episodes),
 *                every transition updates the model, plus replay of past experience.
 *  - EVALUATION: greedy (ε = 0), no learning — measures how good the current model really is.
 *  - PLAY:       the saved model with minimal exploration (ε = 0.0 by default), no learning.
 *
 * Stats are kept separately per mode, so training luck never inflates evaluation numbers.
 */
public final class Agent {
    public enum Mode { TRAINING, EVALUATION, PLAY }

    public final String adapterId;
    public LinearQ model;
    public Mode mode = Mode.TRAINING;
    public final ReplayBuffer replay = new ReplayBuffer(20000);
    public final Map<Mode, Stats> stats = new EnumMap<>(Mode.class);
    public ExperienceLog log;                       // may be null (e.g. fast simulator runs)
    public final Random rnd;

    public float epsStart = 0.30f, epsEnd = 0.03f, playEps = 0.0f;
    public int epsDecayEpisodes = 400;
    public int replayEvery = 1, replayBatch = 8;
    private long sinceReplay;

    public Agent(String adapterId, String[] featureNames, long seed) {
        this.adapterId = adapterId;
        this.model = new LinearQ(featureNames);
        this.rnd = new Random(seed);
        for (Mode m : Mode.values()) stats.put(m, new Stats());
    }

    public Stats stats() { return stats.get(mode); }

    public float epsilon() {
        switch (mode) {
            case EVALUATION: return 0f;
            case PLAY: return playEps;
            default:
                long e = stats.get(Mode.TRAINING).episodes;
                float t = Math.min(1f, e / (float) Math.max(1, epsDecayEpisodes));
                return epsStart + (epsEnd - epsStart) * t;
        }
    }

    public boolean learning() { return mode == Mode.TRAINING; }

    /** The agent's decision for one step. */
    public static final class Choice {
        public int index;
        public float q;
        public boolean explored;
        public float secondQ;   // runner-up value: small margin = unsure
    }

    public Choice choose(List<GameAdapter.Candidate> cands) {
        Choice c = new Choice();
        if (cands.isEmpty()) { c.index = -1; return c; }
        float best = -Float.MAX_VALUE, second = -Float.MAX_VALUE;
        List<Integer> ties = new ArrayList<>();
        for (int i = 0; i < cands.size(); i++) {
            float v = model.q(cands.get(i).features);
            if (v > best + 1e-6f) { second = best; best = v; ties.clear(); ties.add(i); }
            else if (Math.abs(v - best) <= 1e-6f) ties.add(i);
            else if (v > second) second = v;
        }
        if (rnd.nextFloat() < epsilon()) {
            c.index = rnd.nextInt(cands.size());
            c.explored = true;
        } else {
            c.index = ties.get(rnd.nextInt(ties.size()));
        }
        c.q = model.q(cands.get(c.index).features);
        c.secondQ = second == -Float.MAX_VALUE ? best : second;
        return c;
    }

    /** Best candidate's features in a state (used as the next-state value in transitions). */
    public float[] bestFeatures(List<GameAdapter.Candidate> cands) {
        float best = -Float.MAX_VALUE;
        float[] f = null;
        for (GameAdapter.Candidate c : cands) {
            float v = model.q(c.features);
            if (v > best) { best = v; f = c.features; }
        }
        return f;
    }

    /** Records an experience and, in training mode, learns from it (plus a replay batch). */
    public void record(Transition t) {
        t.adapter = adapterId;
        t.mode = mode.name();
        stats().step(t.reward);
        if (log != null) log.write(t);
        if (!learning()) return;
        model.update(t);
        replay.add(t);
        if (++sinceReplay >= replayEvery) {
            sinceReplay = 0;
            for (int k = 0; k < replayBatch && replay.size() > 0; k++) model.update(replay.sample(rnd));
        }
    }

    public void endEpisode(boolean win, float episodeReward) {
        stats().episode(win, episodeReward);
    }

    public void resetLearning() {
        model = new LinearQ(model.featureNames);
        replay.clear();
        for (Mode m : Mode.values()) stats.put(m, new Stats());
    }
}
