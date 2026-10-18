package com.nickrocks.pixelflowbot.vision;

import java.util.Arrays;

/**
 * Move selection, version 2 (hand-written rules; learning comes later).
 *
 * Working assumption (from recordings, still a hypothesis): characters ride the belt around
 * all four sides and can only hit a cube that is the FIRST cube in its row or column seen
 * from that side ("exposed"), and only if it is their colour.
 *
 * Changes from v1, based on the first real game log (lost Level 25 with slots full of
 * leftovers K1, K1, K2, B9, Y8):
 *  - Tracks shots already on the belt per colour ("in flight"), so it doesn't send three
 *    characters after the same few cubes.
 *  - Characters in waiting slots go first whenever their colour has free exposed cubes.
 *  - Prefers characters that can use up all their shots ("clean"), because leftovers
 *    come back and fill a waiting slot. With 3+ slots used and no clean move, it waits for
 *    the belt to finish, then takes the least risky move (as the user did in Level 24).
 */
public final class Planner {
    public static final int MIN_BELT_FREE = 2;
    public static final int MAX_SLOTS_FOR_RISK = 2;
    private static final int N = GameColor.values().length;

    public static final class Move {
        public final boolean fromSlot;
        public final int index;
        public final CharReader.Character ch;
        public final float score;
        public final boolean clean;
        Move(boolean fromSlot, int index, CharReader.Character ch, float score, boolean clean) {
            this.fromSlot = fromSlot; this.index = index; this.ch = ch; this.score = score; this.clean = clean;
        }
        /** Where to tap, in 1080x2520 layout coordinates (character body, under the number). */
        public float layoutX() { return fromSlot ? CharReader.SLOT_X[index] : ch.x; }
        public float layoutY() { return (fromSlot ? CharReader.SLOT_Y : CharReader.QUEUE_Y) + 25; }
        @Override public String toString() {
            return (fromSlot ? "slot " : "queue ") + (index + 1) + " (" + ch.code() + ")";
        }
    }

    public static final class Decision {
        public final Move move;       // null = don't tap now
        public final boolean stuck;   // true = pause and ask the user
        public final String reason;
        Decision(Move move, boolean stuck, String reason) { this.move = move; this.stuck = stuck; this.reason = reason; }
    }

    // ---- memory between reads ----
    private final float[] inFlight = new float[N];
    private int[] prevRemaining;
    private CharReader.Character[] prevSlots;
    /** Slot characters sent without effect: code -> cubes of that colour left when sent. */
    private final java.util.Map<String, Integer> noEffect = new java.util.HashMap<>();

    public void reset() { Arrays.fill(inFlight, 0); prevRemaining = null; prevSlots = null; noEffect.clear(); }

    /** Call after actually tapping a move. */
    public void committed(Move m) {
        int c = m.ch.color.ordinal();
        inFlight[c] += m.ch.shots;
        if (m.fromSlot && prevRemaining != null) noEffect.put(m.ch.code(), prevRemaining[c]);
    }

    public String inFlightText() {
        StringBuilder sb = new StringBuilder();
        for (GameColor g : GameColor.values()) {
            int v = Math.round(inFlight[g.ordinal()]);
            if (v > 0) sb.append(sb.length() > 0 ? " " : "").append(g.code).append(v);
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** Updates the in-flight estimate from what changed since the last read. */
    private void observe(GameState s, int[] rem) {
        if (s.beltFree == 5) Arrays.fill(inFlight, 0);
        if (prevRemaining != null) {
            for (int i = 0; i < N; i++) {
                int drop = prevRemaining[i] - rem[i];
                if (drop > 0) inFlight[i] = Math.max(0, inFlight[i] - drop);
            }
        }
        // A character that just landed in a slot is no longer on the belt.
        if (prevSlots != null) {
            for (int i = 0; i < s.slots.length; i++) {
                CharReader.Character now = s.slots[i], before = prevSlots[i];
                if (now != null && (before == null || before.color != now.color)) {
                    int c = now.color.ordinal();
                    inFlight[c] = Math.max(0, inFlight[c] - now.shots);
                }
            }
        }
        prevRemaining = rem;
        prevSlots = s.slots;
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
        int[] out = new int[N];
        for (int r = 0; r < m; r++) for (int c = 0; c < n; c++) if (seen[r][c]) out[b[r][c].ordinal()]++;
        return out;
    }

    public static int[] remaining(GameColor[][] b) {
        int[] out = new int[N];
        for (GameColor[] row : b) for (GameColor g : row) if (g != GameColor.EMPTY) out[g.ordinal()]++;
        return out;
    }

    public Decision decide(GameState s) {
        if (s.screen != GameState.Screen.GAMEPLAY) return new Decision(null, false, "not gameplay");
        if (s.board == null) return new Decision(null, false, "measuring board");
        int[] exp = exposed(s.board), rem = remaining(s.board);
        observe(s, rem);
        if (s.beltFree < 0) return new Decision(null, false, "belt counter unreadable");
        if (s.beltFree < MIN_BELT_FREE) return new Decision(null, false, "belt busy (" + s.beltFree + "/5 free)");

        int slotsUsed = s.slotsUsed();
        Move best = null;      // slot moves, clean queue moves, and risky queue moves while slots are mostly free
        Move gamble = null;    // risky queue move with the highest share of its shots usable
        for (int pass = 0; pass < 2; pass++) {
            boolean slots = pass == 0;
            CharReader.Character[] list = slots ? s.slots : s.queue;
            for (int i = 0; i < list.length; i++) {
                CharReader.Character c = list[i];
                if (c == null || c.hidden) continue;
                // Sent from a slot before and nothing of its colour was cleared since: don't loop on it.
                Integer before = slots ? noEffect.get(c.code()) : null;
                if (before != null) {
                    if (rem[c.color.ordinal()] < before) noEffect.remove(c.code());
                    else continue;
                }
                float avail = exp[c.color.ordinal()] - inFlight[c.color.ordinal()];
                if (avail <= 0) continue;
                boolean clean = c.shots <= avail;
                if (!slots && !clean && slotsUsed > MAX_SLOTS_FOR_RISK) {
                    float share = Math.min(1f, avail / c.shots);
                    if (gamble == null || share > gamble.score) gamble = new Move(false, i, c, share, false);
                    continue;
                }
                float score = (slots ? 1000 : 0) + (clean ? 500 : 0)
                        + Math.min(c.shots, avail) - (clean ? 0 : 0.5f * (c.shots - avail));
                if (best == null || score > best.score) best = new Move(slots, i, c, score, clean);
            }
        }
        if (best != null) {
            int ci = best.ch.color.ordinal();
            return new Decision(best, false, best.ch.color.name().toLowerCase() + ": " + exp[ci] + " exposed, "
                    + Math.round(inFlight[ci]) + " already on belt" + (best.clean ? "" : " (may leave leftovers)"));
        }

        if (s.beltFree < 5) return new Decision(null, false, "waiting for the belt to expose more cubes");
        if (gamble != null && slotsUsed <= 4) {
            return new Decision(gamble, false, "calculated risk: no safe move, " + slotsUsed + "/5 slots used, "
                    + Math.round(gamble.score * 100) + "% of shots usable now");
        }
        if (slotsUsed > MAX_SLOTS_FOR_RISK + 1)
            return new Decision(null, true, "stuck: nothing can hit a cube and " + slotsUsed + "/5 slots are used");

        // Dig: the queue character whose colour has the most cubes left (it may be useful soon).
        Move dig = null;
        for (int i = 0; i < s.queue.length; i++) {
            CharReader.Character c = s.queue[i];
            if (c == null) continue;
            float sc = c.hidden ? 0.5f : rem[c.color.ordinal()];
            if (dig == null || sc > dig.score) dig = new Move(false, i, c, sc, false);
        }
        if (dig == null) {
            int cubes = 0;
            for (int v : rem) cubes += v;
            if (cubes > 30 && slotsUsed == 0)
                return new Decision(null, true, "can't see any characters (" + cubes + " cubes left)");
            return new Decision(null, false, "no characters left, waiting for the level to finish");
        }
        return new Decision(dig, false, "digging: nothing exposed for any character");
    }
}
