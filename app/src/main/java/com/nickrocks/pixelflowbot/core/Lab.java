package com.nickrocks.pixelflowbot.core;

import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.Env;
import com.nickrocks.pixelflowbot.learn.GameAdapter;
import com.nickrocks.pixelflowbot.learn.SimRunner;

import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Training and evaluation for any {@link LabGame}.
 *
 *  - Training episodes use seeds from the TRAIN stream, evaluation from the EVAL stream
 *    (different salts → different games; the same evaluation seeds every time, so results are
 *    comparable between model versions).
 *  - Evaluation never updates a model: learners are switched to EVALUATION mode (greedy, no
 *    learning) for the run.
 */
public final class Lab {
    private Lab() { }

    private static final long SALT_TRAIN = 0x3C6E_F372_FE94_F82BL, SALT_EVAL = 0xA54F_F53A_5F1D_36F1L;

    public static long trainSeed(String gameId, long k) { return mix(SALT_TRAIN ^ gameId.hashCode() * 0x9E3779B97F4A7C15L ^ mix(k)); }

    public static long evalSeed(String gameId, long k) { return mix(SALT_EVAL ^ gameId.hashCode() * 0x9E3779B97F4A7C15L ^ mix(k)); }

    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Anything that picks a candidate: a baseline or a learning agent. */
    public interface Chooser<S> {
        int choose(S state, List<GameAdapter.Candidate> cands);
    }

    public static <S> Chooser<S> of(LabGame.Baseline<S> b, long seed) {
        Random rnd = new Random(seed);
        return (s, c) -> b.choose(s, c, rnd);
    }

    /**
     * The agent's choice. In EVALUATION mode it is fully deterministic (highest value, ties go to
     * the first candidate), so the same model always gets the same score on the same games.
     */
    public static <S> Chooser<S> of(Agent a) {
        return (s, c) -> {
            if (a.mode != Agent.Mode.EVALUATION) return a.choose(c).index;
            int best = 0;
            float bv = -Float.MAX_VALUE;
            for (int i = 0; i < c.size(); i++) {
                float v = a.model.q(c.get(i).features);
                if (v > bv + 1e-6f) { bv = v; best = i; }
            }
            return best;
        };
    }

    public static final class Report {
        public String player;
        public int episodes, wins;
        public double meanScore, sdScore, meanSteps, minScore, maxScore;
        public double[] scores;
        public long ms;

        /** 95% interval of the mean (normal approximation). */
        public double ci95() { return episodes < 2 ? Double.NaN : 1.96 * sdScore / Math.sqrt(episodes); }

        public String line(String scoreName) {
            return String.format(Locale.US, "%-24s %s %7.1f ± %-5.1f (min %.0f, max %.0f)  wins %d/%d  steps %.0f",
                    player, scoreName, meanScore, ci95(), minScore, maxScore, wins, episodes, meanSteps);
        }
    }

    /** Plays {@code episodes} evaluation games (seeds 0..episodes-1 of the EVAL stream). */
    public static <S> Report evaluate(LabGame<S> g, String name, Chooser<S> chooser, int episodes, int maxSteps, BooleanSupplier stop) {
        Report r = new Report();
        r.player = name;
        r.scores = new double[episodes];
        r.minScore = Double.MAX_VALUE;
        r.maxScore = -Double.MAX_VALUE;
        long t0 = System.currentTimeMillis();
        GameAdapter<S> ad = g.adapter();
        double sum = 0, sum2 = 0, steps = 0;
        int done = 0;
        for (int e = 0; e < episodes; e++) {
            if (stop != null && stop.getAsBoolean()) break;
            Env<S> env = g.newEnv();
            S s = env.reset(new Random(evalSeed(g.id(), e)));
            boolean win = false;
            int n = 0;
            for (; n < maxSteps; n++) {
                List<GameAdapter.Candidate> cands = ad.candidates(s);
                if (cands.isEmpty()) break;
                Env.Outcome<S> o = env.step(s, cands.get(chooser.choose(s, cands)).action);
                s = o.next;
                if (o.done) { win = o.win; n++; break; }
            }
            double sc = g.score(s);
            r.scores[done++] = sc;
            sum += sc;
            sum2 += sc * sc;
            steps += n;
            if (win) r.wins++;
            r.minScore = Math.min(r.minScore, sc);
            r.maxScore = Math.max(r.maxScore, sc);
        }
        r.episodes = done;
        if (done > 0) {
            r.meanScore = sum / done;
            r.sdScore = Math.sqrt(Math.max(0, sum2 / done - r.meanScore * r.meanScore));
            r.meanSteps = steps / done;
        }
        r.ms = System.currentTimeMillis() - t0;
        return r;
    }

    /** Evaluates a learning agent greedily with learning off, then restores its mode. */
    public static <S> Report evaluate(LabGame<S> g, String name, Agent agent, int episodes, int maxSteps, BooleanSupplier stop) {
        Agent.Mode prev = agent.mode;
        agent.mode = Agent.Mode.EVALUATION;
        try { return evaluate(g, name, of(agent), episodes, maxSteps, stop); }
        finally { agent.mode = prev; }
    }

    /** Trainer: an env whose reset uses the next TRAINING seed, so training never sees evaluation games. */
    public static final class Trainer<S> {
        private final LabGame<S> game;
        private final Env<S> env;
        public long next;   // index into the training seed stream
        private S last;     // latest state of the current/last episode

        public Trainer(LabGame<S> game, long startAt) {
            this.game = game;
            this.next = startAt;
            Env<S> inner = game.newEnv();
            this.env = new Env<S>() {
                @Override public S reset(Random rnd) { return last = inner.reset(new Random(trainSeed(game.id(), next++))); }
                @Override public Outcome<S> step(S state, Object action) {
                    Outcome<S> o = inner.step(state, action);
                    last = o.next;
                    return o;
                }
            };
        }

        /** The game's score at the end of the last training episode. */
        public double lastScore() { return last == null ? 0 : game.score(last); }

        public SimRunner.Result episode(Agent agent, int maxSteps, BooleanSupplier stop) {
            if (agent.mode != Agent.Mode.TRAINING) throw new IllegalStateException("agent is not in training mode");
            return SimRunner.episode(agent, game.adapter(), env, next, maxSteps, stop);
        }
    }
}
