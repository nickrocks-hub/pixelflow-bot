package com.nickrocks.pixelflowbot.learn.nono;

/**
 * What the agent knows about a nonogram: the clues (null = hidden/frozen) and each cell
 * (EMPTY / FILLED / CROSS), plus mistakes so far. The simulator also keeps the true solution;
 * the real game never reveals it, and the agent never sees it.
 */
public final class NonoState {
    public static final int EMPTY = 0, FILLED = 1, CROSS = 2;

    public final int rows, cols;
    public final int[][] rowClues, colClues;
    public final int[] cells;
    public int mistakes;
    public int maxMistakes = 3;
    /** Simulator only: the hidden answer (1 = filled). Never used for features. */
    public boolean[] solution;

    public NonoState(int[][] rowClues, int[][] colClues) {
        this.rowClues = rowClues;
        this.colClues = colClues;
        this.rows = rowClues.length;
        this.cols = colClues.length;
        this.cells = new int[rows * cols];
    }

    public NonoState copy() {
        NonoState s = new NonoState(rowClues, colClues);
        System.arraycopy(cells, 0, s.cells, 0, cells.length);
        s.mistakes = mistakes;
        s.maxMistakes = maxMistakes;
        s.solution = solution;
        return s;
    }

    public int filledCount() {
        int n = 0;
        for (int v : cells) if (v == FILLED) n++;
        return n;
    }

    public int clueTotal() {
        int t = 0;
        for (int[] c : rowClues) if (c != null) for (int v : c) t += v;
        return t;
    }
}
