package com.nickrocks.pixelflowbot.game.nonogram;

/**
 * A generated puzzle: its clues, where it came from (split + seed, so it can be rebuilt exactly),
 * and its hidden solution.
 *
 * The solution is deliberately not public: only code in this package (the training environment,
 * which needs it for rewards, and the generator) can read it. Agents live in another package and
 * only ever receive an {@link Observation}.
 */
public final class Puzzle {
    /** Which set a puzzle belongs to. Training and evaluation never share a puzzle. */
    public enum Split {
        TRAIN("T"), EVAL("E"), PLAY("P");
        public final String code;
        Split(String code) { this.code = code; }
    }

    public final Split split;
    public final int index;          // position in its set (training / evaluation / play)
    public final long seed;          // base seed; the same seed always rebuilds the same puzzle
    public final int attempt;        // how many candidate pictures were rejected before this one
    public final int rows, cols;
    private final int[][] rowClues, colClues;
    private final boolean[] solution;
    /** Search nodes the uniqueness proof needed (1 = line logic alone solves it). */
    public final int proofNodes;

    Puzzle(Split split, int index, long seed, int attempt, int rows, int cols, boolean[] solution, int proofNodes) {
        this.split = split;
        this.index = index;
        this.seed = seed;
        this.attempt = attempt;
        this.rows = rows;
        this.cols = cols;
        this.solution = solution.clone();
        this.rowClues = Clues.rows(solution, rows, cols);
        this.colClues = Clues.cols(solution, rows, cols);
        this.proofNodes = proofNodes;
    }

    public int[][] rowClues() { return Board.deepCopy(rowClues); }
    public int[][] colClues() { return Board.deepCopy(colClues); }

    /** e.g. "E10-0007" (evaluation, 10×10, #7). */
    public String id() {
        return String.format(java.util.Locale.US, "%s%02d-%04d", split.code, rows, index);
    }

    /** Package-private: the hidden answer for one cell. */
    boolean solutionFilled(int i) { return solution[i]; }

    int solutionCount() {
        int n = 0;
        for (boolean b : solution) if (b) n++;
        return n;
    }

    /** Identical clues ⇒ identical (unique) puzzle. Used to keep evaluation puzzles out of training. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(rows).append('x').append(cols).append(':');
        for (int[] c : rowClues) sb.append(Clues.text(c)).append('/');
        sb.append('|');
        for (int[] c : colClues) sb.append(Clues.text(c)).append('/');
        return sb.toString();
    }

    @Override public String toString() { return id() + " (seed " + Long.toHexString(seed) + ")"; }
}
