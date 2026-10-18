package com.nickrocks.pixelflowbot.game.nonogram;

import com.nickrocks.pixelflowbot.nono.NonoSolver;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Deterministic constraint logic for nonograms, working only from clues and known cells.
 *
 * Used for two things, both clearly NOT learning:
 *  1. Puzzle validation: {@link #countSolutions} proves a generated puzzle has exactly one answer.
 *     It counts search nodes instead of milliseconds, so the same seed always gives the same
 *     verdict on any device (a time limit would make puzzle sets depend on phone speed).
 *  2. The constraint-solver baseline agent ({@code agent.SolverPlayer}).
 *
 * Grid values: {@link #UNKNOWN} −1, {@link #EMPTY} 0, {@link #FILLED} 1. Each line is solved
 * exactly with {@link NonoSolver#solveLine} (dynamic programming over every block placement).
 */
public final class LineSolver {
    public static final int UNKNOWN = -1, EMPTY = 0, FILLED = 1;

    public final int rows, cols;
    private final int[][] rowClues, colClues;

    public LineSolver(int[][] rowClues, int[][] colClues) {
        this.rowClues = rowClues;
        this.colClues = colClues;
        this.rows = rowClues.length;
        this.cols = colClues.length;
    }

    /** Line-solves rows and columns until nothing changes. Returns false on contradiction. */
    public boolean propagate(int[] g) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        boolean[] queued = new boolean[rows + cols];
        for (int i = 0; i < rows + cols; i++) { queue.add(i); queued[i] = true; }
        int[] line = new int[Math.max(rows, cols)];
        while (!queue.isEmpty()) {
            int id = queue.poll();
            queued[id] = false;
            boolean isRow = id < rows;
            int idx = isRow ? id : id - rows;
            int n = isRow ? cols : rows;
            for (int k = 0; k < n; k++) line[k] = g[isRow ? idx * cols + k : k * cols + idx];
            int[] out = NonoSolver.solveLine(isRow ? rowClues[idx] : colClues[idx], line, n);
            if (out == null) return false;
            for (int k = 0; k < n; k++) {
                if (out[k] != line[k]) {
                    g[isRow ? idx * cols + k : k * cols + idx] = out[k];
                    int other = isRow ? rows + k : k;
                    if (!queued[other]) { queued[other] = true; queue.add(other); }
                }
            }
        }
        return true;
    }

    /** One line solved on its own: cells it forces, or null if the line contradicts its clue. */
    public int[] solveRow(int r, int[] g) {
        int[] line = new int[cols];
        for (int c = 0; c < cols; c++) line[c] = g[r * cols + c];
        return NonoSolver.solveLine(rowClues[r], line, cols);
    }

    public int[] solveCol(int c, int[] g) {
        int[] line = new int[rows];
        for (int r = 0; r < rows; r++) line[r] = g[r * cols + c];
        return NonoSolver.solveLine(colClues[c], line, rows);
    }

    /** Result of {@link #countSolutions}. */
    public static final class Count {
        /** 0, 1 or 2 (2 means "two or more"); −1 if the node budget ran out first. */
        public int solutions;
        public int[] first;
        public int nodes;
        public boolean needsSearch;   // false = line logic alone was enough
    }

    /**
     * Counts solutions up to two, exploring at most {@code nodeBudget} search nodes. Fully
     * deterministic. {@code start} may hold known cells (or be null for a blank grid).
     */
    public Count countSolutions(int[] start, int nodeBudget) {
        Count c = new Count();
        int[] g = new int[rows * cols];
        Arrays.fill(g, UNKNOWN);
        if (start != null) System.arraycopy(start, 0, g, 0, g.length);
        int[][] found = new int[2][];
        int[] nodes = {0};
        try {
            c.solutions = search(g, found, 0, nodes, nodeBudget, true);
        } catch (Budget b) {
            c.solutions = -1;
        }
        c.nodes = nodes[0];
        c.first = found[0];
        c.needsSearch = nodes[0] > 1;
        return c;
    }

    private static final class Budget extends RuntimeException {
        Budget() { super(null, null, false, false); }
    }

    private int search(int[] g, int[][] found, int count, int[] nodes, int budget, boolean root) {
        if (++nodes[0] > budget) throw new Budget();
        if (!propagate(g)) return 0;
        int pick = -1, bestScore = -1;
        for (int i = 0; i < g.length; i++) {
            if (g[i] != UNKNOWN) continue;
            int r = i / cols, cc = i % cols, known = 0;
            for (int k = 0; k < cols; k++) if (g[r * cols + k] != UNKNOWN) known++;
            for (int k = 0; k < rows; k++) if (g[k * cols + cc] != UNKNOWN) known++;
            if (known > bestScore) { bestScore = known; pick = i; }
        }
        if (pick < 0) {
            found[count] = g.clone();
            return 1;
        }
        int total = 0;
        for (int v = FILLED; v >= EMPTY; v--) {
            int[] copy = g.clone();
            copy[pick] = v;
            total += search(copy, found, count + total, nodes, budget, false);
            if (count + total >= 2) return total;
        }
        return total;
    }
}
