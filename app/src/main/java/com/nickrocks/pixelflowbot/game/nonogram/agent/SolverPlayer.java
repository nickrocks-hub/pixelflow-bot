package com.nickrocks.pixelflowbot.game.nonogram.agent;

import com.nickrocks.pixelflowbot.game.nonogram.Action;
import com.nickrocks.pixelflowbot.game.nonogram.LineSolver;
import com.nickrocks.pixelflowbot.game.nonogram.Observation;
import com.nickrocks.pixelflowbot.game.nonogram.PuzzleGenerator;

import java.util.Locale;

/**
 * BASELINE — a constraint-based solver. It does not learn anything; every move is a logical
 * deduction from the clues and the visible cells, tried in this order:
 *   1. line logic: one row or column on its own forces the cell;
 *   2. contradiction test: assuming the cell is filled breaks the clues somewhere (so it's empty), or vice versa;
 *   3. search: the clues have exactly one solution, found by search, and the cell is taken from it.
 * If the visible board already contradicts the clues (someone made a mistake), it clears a cell
 * that disagrees with the clues' only solution.
 */
public final class SolverPlayer implements Player {
    @Override public String name() { return "Constraint solver"; }
    @Override public Source source() { return Source.CONSTRAINT_SOLVER; }

    @Override
    public Decision decide(Observation obs) {
        LineSolver ls = new LineSolver(obs.rowClues(), obs.colClues());
        int[] g = obs.solverGrid();
        int rows = obs.rows, cols = obs.cols;

        // 0. Is the board consistent with the clues at all?
        int[] check = g.clone();
        if (!ls.propagate(check)) return repair(ls, g, rows, cols, "the marked cells contradict the clues");

        // 1. Line logic, one line at a time.
        for (int r = 0; r < rows; r++) {
            int[] out = ls.solveRow(r, g);
            for (int c = 0; c < cols; c++) {
                if (g[r * cols + c] == LineSolver.UNKNOWN && out[c] != LineSolver.UNKNOWN) {
                    return decision(r, c, out[c], String.format(Locale.US, "line logic: row %d on its own forces this cell", r + 1));
                }
            }
        }
        for (int c = 0; c < cols; c++) {
            int[] out = ls.solveCol(c, g);
            for (int r = 0; r < rows; r++) {
                if (g[r * cols + c] == LineSolver.UNKNOWN && out[r] != LineSolver.UNKNOWN) {
                    return decision(r, c, out[r], String.format(Locale.US, "line logic: column %d on its own forces this cell", c + 1));
                }
            }
        }

        // 2. Contradiction test on each unknown cell.
        for (int i = 0; i < g.length; i++) {
            if (g[i] != LineSolver.UNKNOWN) continue;
            int[] a = g.clone();
            a[i] = LineSolver.FILLED;
            if (!ls.propagate(a)) return decision(i / cols, i % cols, LineSolver.EMPTY, "contradiction test: filling it breaks the clues");
            int[] b = g.clone();
            b[i] = LineSolver.EMPTY;
            if (!ls.propagate(b)) return decision(i / cols, i % cols, LineSolver.FILLED, "contradiction test: leaving it empty breaks the clues");
        }

        // 3. Search.
        LineSolver.Count cnt = ls.countSolutions(g, PuzzleGenerator.nodeBudget(Math.max(rows, cols)) * 4);
        if (cnt.solutions == 1) {
            for (int i = 0; i < g.length; i++) {
                if (g[i] == LineSolver.UNKNOWN) return decision(i / cols, i % cols, cnt.first[i], "search: the only solution of the clues has it this way");
            }
            return new Decision(null, Source.CONSTRAINT_SOLVER, "every cell is decided", Float.NaN);
        }
        if (cnt.solutions == 0) return repair(ls, g, rows, cols, "no solution fits the marked cells");
        return new Decision(null, Source.CONSTRAINT_SOLVER,
                cnt.solutions < 0 ? "search budget ran out" : "the clues allow more than one picture", Float.NaN);
    }

    /** Clears a marked cell that disagrees with the clues' only solution (found from the clues alone). */
    private Decision repair(LineSolver ls, int[] g, int rows, int cols, String why) {
        LineSolver.Count cnt = ls.countSolutions(null, PuzzleGenerator.nodeBudget(Math.max(rows, cols)) * 4);
        if (cnt.solutions != 1) return new Decision(null, Source.CONSTRAINT_SOLVER, why + " (can't tell which cell is wrong)", Float.NaN);
        for (int i = 0; i < g.length; i++) {
            if (g[i] != LineSolver.UNKNOWN && g[i] != cnt.first[i]) {
                return new Decision(Action.clear(i / cols, i % cols), Source.CONSTRAINT_SOLVER, why + ": this cell is wrong", Float.NaN);
            }
        }
        return new Decision(null, Source.CONSTRAINT_SOLVER, why, Float.NaN);
    }

    private static Decision decision(int r, int c, int value, String reason) {
        Action a = value == LineSolver.FILLED ? Action.fill(r, c) : Action.markEmpty(r, c);
        return new Decision(a, Source.CONSTRAINT_SOLVER, reason, Float.NaN);
    }
}
