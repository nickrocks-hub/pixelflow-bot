package com.nickrocks.pixelflowbot.game.nonogram;

import com.nickrocks.pixelflowbot.core.GameProfile;
import com.nickrocks.pixelflowbot.core.Perception;
import com.nickrocks.pixelflowbot.core.StateObservation;
import com.nickrocks.pixelflowbot.core.StateObservation.Source;

/**
 * Adapter for the built-in Nonogram on the shared core. Its "perception" is exact — the board is
 * our own data, not a screenshot — so every fact is OBSERVED with confidence 1, and there are no
 * hidden facts (the answer is not part of the observation at all).
 */
public final class NonoSimAdapter implements Perception<Observation> {
    public static final String ID = "nonogram-sim";

    @Override
    public StateObservation observe(Observation o, GameProfile p) {
        StateObservation s = new StateObservation(p.id, p.version, System.currentTimeMillis());
        s.screen = StateObservation.Screen.GAMEPLAY;
        s.confidence = 1f;
        s.detail = o;
        s.add("grid", o.cols + "x" + o.rows, 1f, Source.OBSERVED);
        for (int r = 0; r < o.rows; r++) s.add("row " + (r + 1), Clues.text(o.rowClue(r)), 1f, Source.OBSERVED);
        for (int c = 0; c < o.cols; c++) s.add("col " + (c + 1), Clues.text(o.colClue(c)), 1f, Source.OBSERVED);
        s.add("filled", o.count(Board.FILLED), 1f, Source.OBSERVED);
        s.add("crossed", o.count(Board.EMPTY), 1f, Source.OBSERVED);
        s.add("unknown", o.count(Board.UNKNOWN), 1f, Source.OBSERVED);
        Board b = o.toBoard();
        int done = 0;
        for (int r = 0; r < o.rows; r++) if (b.rowSatisfied(r)) done++;
        for (int c = 0; c < o.cols; c++) if (b.colSatisfied(c)) done++;
        s.add("lines satisfied", done, 1f, Source.INFERRED);
        s.add("solved", b.isSolved(), 1f, Source.INFERRED);
        return s;
    }
}
