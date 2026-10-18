package com.nickrocks.pixelflowbot.game.dodge;

/**
 * What the player sees of Dodge at one tick. With simulated latency (see {@link DodgeEnv}) this
 * can be an OLD picture of the field — exactly the situation of a bot watching a phone screen.
 */
public final class DodgeState {
    public static final int LANES = 5, ROWS = 10;

    /** obstacles[row * LANES + lane], row 0 = top, row ROWS−1 = the player's row. */
    public final boolean[] obstacles;
    public final int lane;            // player lane 0..LANES−1
    public final int tick;            // ticks survived
    public final int period;          // ticks between obstacle moves (smaller = faster)
    public final int untilMove;       // ticks until the obstacles move down next
    public final boolean crashed;

    public DodgeState(boolean[] obstacles, int lane, int tick, int period, int untilMove, boolean crashed) {
        this.obstacles = obstacles;
        this.lane = lane;
        this.tick = tick;
        this.period = period;
        this.untilMove = untilMove;
        this.crashed = crashed;
    }

    public boolean at(int row, int lane) { return obstacles[row * LANES + lane]; }

    /** Rows between the player and the nearest obstacle above in a lane (0 = in the player's row, ROWS = none). */
    public int gap(int ln) {
        for (int r = ROWS - 1; r >= 0; r--) if (at(r, ln)) return ROWS - 1 - r;
        return ROWS;
    }
}
