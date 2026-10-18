package com.nickrocks.pixelflowbot.game.dodge;

import com.nickrocks.pixelflowbot.learn.Env;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Dodge: a simple real-time game. Each tick (50 ms at 20 Hz in the app) the player may move one
 * lane left, right or stay. Every {@code period} ticks the obstacles drop one row and a new row
 * appears at the top. A hidden "corridor" lane drifts at most one lane per row and is kept free in
 * two consecutive rows, so a perfect player can always survive — losses are skill, not bad luck. The game speeds up every 200 ticks
 * (period 6 → 2). Touching an obstacle ends the game. Surviving 1200 ticks (60 s) is a win.
 *
 * Rewards: +0.1 per tick survived (source: survival time), −10 on a crash.
 *
 * Simulated latency, to test whether a phone bot could keep up:
 *  - {@code obsDelay}: the state handed to the agent is that many ticks old (screen capture lag);
 *  - {@code actDelay}: the agent's move takes effect that many ticks later (tap latency).
 * The true game always advances in real time.
 */
public final class DodgeEnv implements Env<DodgeState> {
    public static final int WIN_TICKS = 1200;
    public static final float R_TICK = 0.1f, R_CRASH = -10f;

    public int obsDelay, actDelay;

    private Random rnd;
    private int corridor;
    private DodgeState truth;
    private final ArrayDeque<DodgeState> seen = new ArrayDeque<>();
    private final ArrayDeque<Integer> pending = new ArrayDeque<>();

    public DodgeEnv() { }

    public DodgeEnv(int obsDelay, int actDelay) {
        this.obsDelay = obsDelay;
        this.actDelay = actDelay;
    }

    public DodgeState truth() { return truth; }

    @Override
    public DodgeState reset(Random seed) {
        rnd = new Random(seed.nextLong());
        boolean[] ob = new boolean[DodgeState.LANES * DodgeState.ROWS];
        truth = new DodgeState(ob, DodgeState.LANES / 2, 0, 6, 6, false);
        corridor = DodgeState.LANES / 2;
        seen.clear();
        pending.clear();
        for (int i = 0; i < actDelay; i++) pending.add(0);
        seen.add(truth);
        return observed();
    }

    private DodgeState observed() {
        while (seen.size() > obsDelay + 1) seen.removeFirst();
        return seen.peekFirst();
    }

    /** action: −1 left, 0 stay, +1 right (Integer). */
    @Override
    public Outcome<DodgeState> step(DodgeState ignoredPossiblyStale, Object action) {
        int a = action instanceof Integer ? Math.max(-1, Math.min(1, (Integer) action)) : 0;
        pending.add(a);
        int move = pending.removeFirst();
        DodgeState s = truth;
        Outcome<DodgeState> o = new Outcome<>();
        int lane = Math.max(0, Math.min(DodgeState.LANES - 1, s.lane + move));
        boolean[] ob = s.obstacles;
        boolean crash = ob[(DodgeState.ROWS - 1) * DodgeState.LANES + lane];
        int tick = s.tick + 1, period = s.period, until = s.untilMove - 1;
        if (!crash && until <= 0) {
            ob = drop(ob, tick);
            until = period;
            crash = ob[(DodgeState.ROWS - 1) * DodgeState.LANES + lane];
        }
        if (tick % 200 == 0 && period > 2) period--;
        truth = new DodgeState(ob, lane, tick, period, until, crash);
        seen.add(truth);
        o.next = observed();
        if (crash) {
            o.reward = R_CRASH;
            o.done = true;
            o.note = "crashed";
        } else {
            o.reward = R_TICK;
            o.note = "survived";
            if (tick >= WIN_TICKS) { o.done = true; o.win = true; o.note = "survived 60 s"; }
        }
        return o;
    }

    private boolean[] drop(boolean[] ob, int tick) {
        int L = DodgeState.LANES;
        boolean[] n = new boolean[ob.length];
        System.arraycopy(ob, 0, n, L, ob.length - L);
        // New top row: denser as the game goes on, never fully blocked.
        float p = Math.min(0.5f, 0.2f + tick / 4000f);
        for (int l = 0; l < L; l++) if (rnd.nextFloat() < p) n[l] = true;
        int next = Math.max(0, Math.min(L - 1, corridor + rnd.nextInt(3) - 1));
        n[corridor] = false;    // free where the corridor was (you're here when this row arrives)
        n[next] = false;        // and where it goes next (move over while this row is level with you)
        corridor = next;
        return n;
    }
}
