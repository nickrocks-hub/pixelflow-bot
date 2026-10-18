package com.nickrocks.pixelflowbot.vision;

import java.util.ArrayList;
import java.util.List;

/**
 * Move selection, version 1 (a simple rule, not learned yet).
 *
 * Working assumption (from recordings, still a hypothesis): characters ride the belt
 * around all four sides and can only hit a cube that is the FIRST cube in its row or
 * column as seen from that side ("exposed"), and only if it is their colour.
 *
 * Rule: send the character whose colour has the most exposed cubes, preferring
 * characters in waiting slots (that frees a slot). Keep some belt space free.
 * If nothing has exposed cubes: wait while the belt is busy; otherwise dig with a queue
 * character only while there is room in the waiting slots; else report "stuck".
 */
public final class Planner {
    /** Don't send a character unless at least this many belt spaces are free. */
    public static final int MIN_BELT_FREE = 2;
    /** Never dig (send a probably-useless character) with this many slots already used. */
    public static final int MAX_SLOTS_FOR_DIGGING = 2;

    public static final class Move {
        public final boolean fromSlot;
        public final int index;
        public final CharReader.Character ch;
        public final float score;
        Move(boolean fromSlot, int index, CharReader.Character ch, float score) {
            this.fromSlot = fromSlot; this.index = index; this.ch = ch; this.score = score;
        }
        /** Where to tap, in 1080x2520 layout coordinates (character body, under the number). */
        public float layoutX() { return fromSlot ? CharReader.SLOT_X[index] : CharReader.QUEUE_X[index]; }
        public float layoutY() { return (fromSlot ? CharReader.SLOT_Y : CharReader.QUEUE_Y) + 25; }
        @Override public String toString() {
            return (fromSlot ? "slot " : "queue ") + (index + 1) + " (" + ch.color.code + ch.shots + ")";
        }
    }

    public static final class Decision {
        public final Move move;       // null = don't tap now
        public final boolean stuck;   // true = pause and ask the user
        public final String reason;
        Decision(Move move, boolean stuck, String reason) { this.move = move; this.stuck = stuck; this.reason = reason; }
    }

    /** Exposed cube count per colour (index = GameColor.ordinal()). */
    public static int[] exposed(GameColor[][] b) {
        int m = b.length, n = b[0].length;
        boolean[][] seen = new boolean[m][n];
        for (int r = 0; r < m; r++) {
            for (int c = 0; c < n; c++) if (b[r][c] != GameColor.EMPTY) { seen[r][c] = true; break; }
            for (int c = n - 1; c >= 0; c--) if (b[r][c] != GameColor.EMPTY) { seen[r][c] = true; break; }
        }
        for (int c = 0; c < n; c++) {
            for (int r = 0; r < m; r++) if (b[r][c] != GameColor.EMPTY) { seen[r][c] = true; break; }
            for (int r = m - 1; r >= 0; r--) if (b[r][c] != GameColor.EMPTY) { seen[r][c] = true; break; }
        }
        int[] out = new int[GameColor.values().length];
        for (int r = 0; r < m; r++) for (int c = 0; c < n; c++) if (seen[r][c]) out[b[r][c].ordinal()]++;
        return out;
    }

    public static int[] remaining(GameColor[][] b) {
        int[] out = new int[GameColor.values().length];
        for (GameColor[] row : b) for (GameColor g : row) if (g != GameColor.EMPTY) out[g.ordinal()]++;
        return out;
    }

    public static Decision decide(GameState s) {
        if (s.screen != GameState.Screen.GAMEPLAY) return new Decision(null, false, "not gameplay");
        if (s.board == null) return new Decision(null, false, "measuring board");
        if (s.beltFree < 0) return new Decision(null, false, "belt counter unreadable");
        if (s.beltFree < MIN_BELT_FREE) return new Decision(null, false, "belt busy (" + s.beltFree + "/5 free)");

        int[] exp = exposed(s.board), rem = remaining(s.board);
        List<Move> useful = new ArrayList<>();
        for (int i = 0; i < s.slots.length; i++) {
            CharReader.Character c = s.slots[i];
            if (c != null && exp[c.color.ordinal()] > 0)
                useful.add(new Move(true, i, c, Math.min(c.shots, exp[c.color.ordinal()]) + 3f));
        }
        for (int i = 0; i < s.queue.length; i++) {
            CharReader.Character c = s.queue[i];
            if (c != null && exp[c.color.ordinal()] > 0)
                useful.add(new Move(false, i, c, Math.min(c.shots, exp[c.color.ordinal()])));
        }
        Move best = null;
        for (Move mv : useful) if (best == null || mv.score > best.score) best = mv;
        if (best != null) {
            return new Decision(best, false, best.ch.color.name().toLowerCase() + ": "
                    + exp[best.ch.color.ordinal()] + " exposed, " + rem[best.ch.color.ordinal()] + " left");
        }

        if (s.beltFree < 5) return new Decision(null, false, "nothing exposed yet, belt still working");
        if (s.slotsUsed() > MAX_SLOTS_FOR_DIGGING)
            return new Decision(null, true, "stuck: no character can hit an exposed cube and " + s.slotsUsed() + "/5 slots are used");

        // Dig: the queue character whose colour has the most cubes left (it may be useful soon).
        Move dig = null;
        for (int i = 0; i < s.queue.length; i++) {
            CharReader.Character c = s.queue[i];
            if (c == null) continue;
            float sc = rem[c.color.ordinal()];
            if (dig == null || sc > dig.score) dig = new Move(false, i, c, sc);
        }
        if (dig == null) return new Decision(null, false, "no characters left, waiting for the level to finish");
        return new Decision(dig, false, "digging: nothing exposed for any character");
    }
}
