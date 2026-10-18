package com.nickrocks.pixelflowbot.game.nonogram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything an agent is allowed to see: board size, row and column clues, the visible state of
 * every cell, and its most recent actions. It is built from a {@link Board}, which never has the
 * solution, so there is no path from here to the hidden answer. Immutable (all arrays copied).
 */
public final class Observation {
    public final int rows, cols;
    private final int[][] rowClues, colClues;
    private final int[] cells;
    private final List<Action> recent;
    public final int moves;

    Observation(int rows, int cols, int[][] rowClues, int[][] colClues, int[] cells, List<Action> recent, int moves) {
        this.rows = rows;
        this.cols = cols;
        this.rowClues = Board.deepCopy(rowClues);
        this.colClues = Board.deepCopy(colClues);
        this.cells = cells.clone();
        this.recent = Collections.unmodifiableList(new ArrayList<>(recent));
        this.moves = moves;
    }

    public int[] rowClue(int r) { return rowClues[r].clone(); }
    public int[] colClue(int c) { return colClues[c].clone(); }
    public int[][] rowClues() { return Board.deepCopy(rowClues); }
    public int[][] colClues() { return Board.deepCopy(colClues); }

    /** Board.UNKNOWN / FILLED / EMPTY. */
    public int cell(int r, int c) { return cells[r * cols + c]; }
    public int cell(int i) { return cells[i]; }
    public int[] cells() { return cells.clone(); }

    /** Most recent actions, oldest first. */
    public List<Action> recentActions() { return recent; }

    public int count(int state) {
        int n = 0;
        for (int v : cells) if (v == state) n++;
        return n;
    }

    /** Cells as the constraint solver's grid: FILLED→1, EMPTY→0, UNKNOWN→−1. */
    public int[] solverGrid() {
        int[] g = new int[cells.length];
        for (int i = 0; i < g.length; i++) {
            g[i] = cells[i] == Board.FILLED ? LineSolver.FILLED : cells[i] == Board.EMPTY ? LineSolver.EMPTY : LineSolver.UNKNOWN;
        }
        return g;
    }

    /** The board rebuilt from this observation (e.g. to try a move without touching the real game). */
    public Board toBoard() {
        Board b = new Board(rowClues, colClues);
        for (int i = 0; i < cells.length; i++) {
            int r = i / cols, c = i % cols;
            if (cells[i] == Board.FILLED) b.apply(Action.fill(r, c));
            else if (cells[i] == Board.EMPTY) b.apply(Action.markEmpty(r, c));
        }
        return b;
    }

    public String summary() {
        return cols + "x" + rows + " filled " + count(Board.FILLED) + " marked " + count(Board.EMPTY)
                + " unknown " + count(Board.UNKNOWN);
    }
}
