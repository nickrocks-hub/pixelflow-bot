package com.nickrocks.pixelflowbot.nono;

import com.nickrocks.pixelflowbot.core.GameAction;
import com.nickrocks.pixelflowbot.core.GameProfile;
import com.nickrocks.pixelflowbot.core.Perception;
import com.nickrocks.pixelflowbot.core.RewardModel;
import com.nickrocks.pixelflowbot.core.StateObservation;
import com.nickrocks.pixelflowbot.core.StateObservation.Source;
import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.Locale;

/**
 * Adapter for Nonogram.com (com.easybrain.nonogram) on the shared core.
 *
 * Perception wraps the existing, field-tested {@link NonoVision} reader unchanged and turns its
 * reading into a {@link StateObservation}: clues are OBSERVED (with the digit-match confidence),
 * frozen ❄ clues are HIDDEN, a grey clue is the game itself saying the line is satisfied.
 * The reward model uses only signals this game really shows: newly filled cells, its red mistake
 * marks, lost hearts and the Level Completed screen.
 */
public final class NonoComAdapter implements Perception<Frame>, RewardModel {
    public static final String ID = "nonogram-com";
    public static final String PACKAGE = "com.easybrain.nonogram";

    /** Reward per newly filled cell (read back from the board). */
    public static final float R_FILL = 1f, R_MISTAKE = -5f, R_HEART = -5f, R_COMPLETE = 10f;

    private final NonoVision vision = new NonoVision();

    @Override
    public StateObservation observe(Frame f, GameProfile p) {
        NonoVision.Reading r = vision.read(f);
        if (r.grid == null && NextButton.find(f) != null) {
            StateObservation s = new StateObservation(p.id, p.version, System.currentTimeMillis());
            s.screen = StateObservation.Screen.LEVEL_COMPLETE;
            s.confidence = 0.9f;
            s.add("next-level button", "visible", 0.9f, Source.OBSERVED);
            s.detail = r;
            return s;
        }
        return fromReading(r, p);
    }

    /** Converts a reading the solver already made (no second read of the screen). */
    public static StateObservation fromReading(NonoVision.Reading r, GameProfile p) {
        StateObservation s = new StateObservation(p.id, p.version, System.currentTimeMillis());
        s.detail = r;
        if (r.grid == null) {
            s.screen = StateObservation.Screen.UNKNOWN;
            s.confidence = 0f;
            s.problem = r.error != null ? r.error : "no puzzle grid on screen";
            return s;
        }
        s.screen = StateObservation.Screen.GAMEPLAY;
        // Digit matches are rejected above 0.08 (see NonoVision); map 0 → 1.0 and 0.08 → 0.5.
        float digitConf = clamp(1f - r.worstDigit / 0.16f);
        s.confidence = r.error != null ? Math.min(0.3f, digitConf) : digitConf;
        if (r.error != null) s.problem = r.error;
        s.add("grid", r.grid.cols + "x" + r.grid.rows, 1f, Source.OBSERVED);
        s.add("cell px", Math.round(r.grid.cell), 1f, Source.OBSERVED);
        if (r.rowClues != null && r.colClues != null) {
            for (int i = 0; i < r.rowClues.length; i++) clue(s, "row " + (i + 1), r.rowClues[i], r.rowGrey != null && r.rowGrey[i], digitConf);
            for (int i = 0; i < r.colClues.length; i++) clue(s, "col " + (i + 1), r.colClues[i], r.colGrey != null && r.colGrey[i], digitConf);
        }
        if (r.cells != null) {
            int filled = 0, crossed = 0;
            for (int v : r.cells) { if (v == NonoVision.FILLED) filled++; else if (v == NonoVision.CROSS) crossed++; }
            s.add("filled", filled, 0.95f, Source.OBSERVED);
            s.add("crossed", crossed, 0.95f, Source.OBSERVED);
            s.add("unknown", r.cells.length - filled - crossed, 0.95f, Source.OBSERVED);
        }
        s.add("mistakes (red)", r.redCells, 0.9f, Source.OBSERVED);
        if (r.hearts >= 0) s.add("hearts", r.hearts, 0.9f, Source.OBSERVED);
        if (r.toggle >= 0) s.add("input mode", r.toggle == 1 ? "fill" : "cross", 0.9f, Source.OBSERVED);
        if (r.frozen > 0) s.add("hidden clues", r.frozen, 1f, Source.OBSERVED);
        return s;
    }

    private static void clue(StateObservation s, String key, int[] clue, boolean grey, float conf) {
        if (clue == null) { s.add(key, "❄ hidden", 1f, Source.HIDDEN); return; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clue.length; i++) { if (i > 0) sb.append(' '); sb.append(clue[i]); }
        s.add(key, clue.length == 0 ? "0" : sb.toString(), conf, Source.OBSERVED);
        if (grey) s.add(key + " satisfied", "yes (clue shown grey by the game)", conf, Source.OBSERVED);
    }

    @Override
    public Reward reward(StateObservation before, GameAction action, StateObservation after) {
        if (after == null) return Reward.none("no observation after the action");
        if (after.screen == StateObservation.Screen.LEVEL_COMPLETE) {
            return new Reward(R_COMPLETE, "Level Completed screen appeared", true, true);
        }
        if (before == null || before.screen != StateObservation.Screen.GAMEPLAY || after.screen != StateObservation.Screen.GAMEPLAY) {
            return Reward.none("no comparable board readings");
        }
        StringBuilder src = new StringBuilder();
        float r = 0;
        int dFill = after.intValue("filled", 0) - before.intValue("filled", 0);
        if (dFill != 0) { r += R_FILL * dFill; src.append(String.format(Locale.US, "board read-back: %+d filled cells", dFill)); }
        int dRed = after.intValue("mistakes (red)", 0) - before.intValue("mistakes (red)", 0);
        if (dRed > 0) { r += R_MISTAKE * dRed; sep(src).append("game marked ").append(dRed).append(" mistake(s) red"); }
        int h0 = before.intValue("hearts", -1), h1 = after.intValue("hearts", -1);
        boolean dead = false;
        if (h0 >= 0 && h1 >= 0 && h1 < h0) {
            r += R_HEART * (h0 - h1);
            sep(src).append("lost ").append(h0 - h1).append(" heart(s)");
            dead = h1 == 0;
        }
        if (src.length() == 0) src.append("no visible change");
        return new Reward(r, src.toString(), dead, false);
    }

    private static StringBuilder sep(StringBuilder sb) { return sb.length() > 0 ? sb.append("; ") : sb; }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
}
