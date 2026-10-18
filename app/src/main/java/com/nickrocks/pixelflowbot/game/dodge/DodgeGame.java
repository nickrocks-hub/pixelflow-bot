package com.nickrocks.pixelflowbot.game.dodge;

import com.nickrocks.pixelflowbot.core.GameProfile;
import com.nickrocks.pixelflowbot.core.LabGame;
import com.nickrocks.pixelflowbot.core.Perception;
import com.nickrocks.pixelflowbot.core.StateObservation;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.Env;
import com.nickrocks.pixelflowbot.learn.GameAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Dodge on the shared core. For each of the 3 moves the learner sees where that move would put
 * it: how far the nearest obstacle is in the target lane (one-hot buckets), whether the field is
 * about to drop, how open the neighbouring lanes are. It learns from survival time and crashes.
 */
public final class DodgeGame implements LabGame<DodgeState> {
    public static final String ID = "dodge-sim";
    public static final long TICK_MS = 50;

    /** Simulated latency for this game instance (ticks). The app uses 0; the bench sweeps it. */
    public int obsDelay, actDelay;

    static final String[] FEATURES = {
            "bias", "moves (not stay)", "target is an edge lane",
            "obstacle in target lane: same row", "…1 row away", "…2 rows", "…3 rows", "…4-5 rows", "…6+ rows / none",
            "1 row away and the field drops next tick", "1 row away and it drops within 2 ticks",
            "2 rows away and it drops next tick", "open lanes next to target", "speed (1/period)"};

    private final Adapter adapter = new Adapter();

    public DodgeGame() { }

    public DodgeGame(int obsDelay, int actDelay) {
        this.obsDelay = obsDelay;
        this.actDelay = actDelay;
    }

    @Override public String id() { return ID; }
    @Override public String title() { return "Dodge"; }
    @Override public GameProfile.Pace pace() { return GameProfile.Pace.REAL_TIME; }
    @Override public long tickMs() { return TICK_MS; }
    @Override public GameAdapter<DodgeState> adapter() { return adapter; }
    @Override public Env<DodgeState> newEnv() { return new DodgeEnv(obsDelay, actDelay); }
    @Override public double score(DodgeState s) { return s.tick * TICK_MS / 1000.0; }
    @Override public String scoreName() { return "seconds"; }
    @Override public int maxSteps() { return DodgeEnv.WIN_TICKS + 20; }

    @Override
    public void configure(Agent a) {
        a.model.normalize = true;
        a.model.alpha = 0.05f;
        a.model.gamma = 0.8f;
        a.epsStart = 0.1f;
        a.epsEnd = 0.005f;
        a.epsDecayEpisodes = 300;
    }

    static final class Adapter implements GameAdapter<DodgeState> {
        @Override public String id() { return ID; }
        @Override public String title() { return "Dodge"; }
        @Override public String[] featureNames() { return FEATURES; }

        @Override
        public List<Candidate> candidates(DodgeState s) {
            List<Candidate> out = new ArrayList<>();
            if (s.crashed) return out;
            for (int a = -1; a <= 1; a++) {
                int t = s.lane + a;
                if (t < 0 || t >= DodgeState.LANES) continue;
                out.add(new Candidate(a < 0 ? "left" : a > 0 ? "right" : "stay", features(s, a), a));
            }
            return out;
        }

        @Override public String describe(DodgeState s) { return "tick " + s.tick + " lane " + (s.lane + 1); }
    }

    static float[] features(DodgeState s, int a) {
        int t = s.lane + a;
        int g = s.gap(t);
        float[] f = new float[FEATURES.length];
        f[0] = 1;
        f[1] = a != 0 ? 1 : 0;
        f[2] = t == 0 || t == DodgeState.LANES - 1 ? 1 : 0;
        f[3 + (g == 0 ? 0 : g == 1 ? 1 : g == 2 ? 2 : g == 3 ? 3 : g <= 5 ? 4 : 5)] = 1;
        f[9] = g == 1 && s.untilMove <= 1 ? 1 : 0;
        f[10] = g == 1 && s.untilMove <= 2 ? 1 : 0;
        f[11] = g == 2 && s.untilMove <= 1 ? 1 : 0;
        int open = 0;
        if (t > 0 && s.gap(t - 1) >= 3) open++;
        if (t < DodgeState.LANES - 1 && s.gap(t + 1) >= 3) open++;
        f[12] = open / 2f;
        f[13] = 1f / s.period;
        return f;
    }

    @Override
    public List<Baseline<DodgeState>> baselines() {
        List<Baseline<DodgeState>> l = new ArrayList<>();
        l.add(new Baseline<DodgeState>() {
            @Override public String name() { return "Random"; }
            @Override public int choose(DodgeState s, List<GameAdapter.Candidate> c, Random rnd) { return rnd.nextInt(c.size()); }
        });
        l.add(new Baseline<DodgeState>() {
            @Override public String name() { return "Never move"; }
            @Override public int choose(DodgeState s, List<GameAdapter.Candidate> c, Random rnd) {
                for (int i = 0; i < c.size(); i++) if ((Integer) c.get(i).action == 0) return i;
                return 0;
            }
        });
        l.add(new Baseline<DodgeState>() {
            // Hand-written: go to the neighbouring lane whose nearest obstacle is farthest (stay on ties).
            @Override public String name() { return "Hand-written rule"; }
            @Override public int choose(DodgeState s, List<GameAdapter.Candidate> c, Random rnd) {
                int best = 0, bg = -1;
                for (int i = 0; i < c.size(); i++) {
                    int a = (Integer) c.get(i).action;
                    int g = s.gap(s.lane + a) * 2 + (a == 0 ? 1 : 0);
                    if (g > bg) { bg = g; best = i; }
                }
                return best;
            }
        });
        return l;
    }

    @Override
    public Perception<DodgeState> perception() {
        return (s, p) -> {
            StateObservation o = new StateObservation(p.id, p.version, System.currentTimeMillis());
            o.screen = s.crashed ? StateObservation.Screen.LOSS : StateObservation.Screen.GAMEPLAY;
            o.confidence = 1f;
            o.detail = s;
            o.add("time", String.format(java.util.Locale.US, "%.2f s", s.tick * TICK_MS / 1000.0), 1, StateObservation.Source.OBSERVED);
            o.add("lane", s.lane + 1, 1, StateObservation.Source.OBSERVED);
            o.add("nearest obstacle in my lane (rows)", s.gap(s.lane), 1, StateObservation.Source.OBSERVED);
            o.add("drop period (ticks)", s.period, 1, StateObservation.Source.OBSERVED);
            if (obsDelay > 0) o.add("picture age", obsDelay * TICK_MS + " ms", 1, StateObservation.Source.HIDDEN);
            return o;
        };
    }
}
