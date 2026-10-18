package com.nickrocks.pixelflowbot.nono;

import java.util.ArrayDeque;

/**
 * Nonogram solver: exact line solving (dynamic programming over every possible block placement)
 * repeated until nothing changes, then search with propagation when lines alone aren't enough.
 * It also proves the answer is unique, so the bot never fills a cell it isn't sure of.
 */
public final class NonoSolver {
    public static final int UNKNOWN = -1, EMPTY = 0, FILLED = 1;

    public enum Status { SOLVED, MULTIPLE, CONTRADICTION, TIMEOUT }

    public static final class Result {
        public Status status;
        public int[] grid;          // rows*cols of EMPTY/FILLED when SOLVED
        public String note = "";
        public long ms;
        public int guesses;
    }

    private int rows, cols;
    private int[][] rowClues, colClues;
    private long deadline;
    private int guesses;

    public Result solve(int[][] rowClues, int[][] colClues, long timeLimitMs) {
        return solve(rowClues, colClues, null, timeLimitMs);
    }

    public Result solve(int[][] rowClues, int[][] colClues, int[] start) {
        return solve(rowClues, colClues, start, 4000);
    }

    public Result solve(int[][] rowClues, int[][] colClues, int[] start, long timeLimitMs) {
        long t0 = System.currentTimeMillis();
        this.rows = rowClues.length;
        this.cols = colClues.length;
        this.rowClues = rowClues;
        this.colClues = colClues;
        this.deadline = t0 + timeLimitMs;
        this.guesses = 0;
        Result res = new Result();
        int[] g = new int[rows * cols];
        java.util.Arrays.fill(g, UNKNOWN);
        if (start != null) System.arraycopy(start, 0, g, 0, g.length);
        int[][] found = new int[2][];
        int n;
        try {
            n = search(g, found, 0);
        } catch (Timeout t) {
            res.status = Status.TIMEOUT;
            res.note = "gave up after " + timeLimitMs + " ms";
            res.ms = System.currentTimeMillis() - t0;
            return res;
        }
        res.guesses = guesses;
        res.ms = System.currentTimeMillis() - t0;
        if (n == 0) { res.status = Status.CONTRADICTION; res.note = "the clues have no solution (misread?)"; }
        else if (n > 1) { res.status = Status.MULTIPLE; res.note = "more than one solution"; res.grid = found[0]; }
        else { res.status = Status.SOLVED; res.grid = found[0]; res.note = guesses == 0 ? "logic only" : guesses + " guesses"; }
        return res;
    }

    private static final class Timeout extends RuntimeException {
        Timeout() { super(null, null, false, false); }
    }

    /** Counts solutions (stops at 2), storing them in found. */
    private int search(int[] g, int[][] found, int count) {
        if (System.currentTimeMillis() > deadline) throw new Timeout();
        if (!propagate(g)) return 0;
        int pick = -1, bestScore = -1;
        for (int i = 0; i < g.length; i++) {
            if (g[i] != UNKNOWN) continue;
            // Prefer cells in nearly-finished lines: cheap and decisive.
            int r = i / cols, c = i % cols, known = 0;
            for (int k = 0; k < cols; k++) if (g[r * cols + k] != UNKNOWN) known++;
            for (int k = 0; k < rows; k++) if (g[k * cols + c] != UNKNOWN) known++;
            if (known > bestScore) { bestScore = known; pick = i; }
        }
        if (pick < 0) {
            found[count] = g.clone();
            return 1;
        }
        guesses++;
        int total = 0;
        for (int v = FILLED; v >= EMPTY; v--) {
            int[] copy = g.clone();
            copy[pick] = v;
            total += search(copy, found, count + total);
            if (count + total >= 2) return total;
        }
        return total;
    }

    /** Line-solves rows and columns until stable. False on contradiction. */
    boolean propagate(int[] g) {
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
            int[] out = solveLine(isRow ? rowClues[idx] : colClues[idx], line, n);
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

    /**
     * Exact line solver. Returns the line with every cell that is the same in all valid placements
     * filled in, or null if no placement fits.
     */
    static int[] solveLine(int[] clue, int[] known, int n) {
        int k = clue.length;
        // zeros[i] = number of known-empty cells in [0, i)
        int[] zeros = new int[n + 1];
        for (int i = 0; i < n; i++) zeros[i + 1] = zeros[i] + (known[i] == EMPTY ? 1 : 0);
        // P[i][j]: cells [0,i) can hold the first j blocks, cell i-1 not needed as a gap yet.
        boolean[][] P = new boolean[n + 1][k + 1];
        P[0][0] = true;
        for (int i = 1; i <= n; i++) {
            for (int j = 0; j <= k; j++) {
                boolean ok = known[i - 1] != FILLED && P[i - 1][j];
                if (!ok && j >= 1) {
                    int L = clue[j - 1], s = i - L;
                    if (s >= 0 && zeros[i] - zeros[s] == 0) {
                        if (s == 0) ok = j - 1 == 0;
                        else ok = known[s - 1] != FILLED && P[s - 1][j - 1];
                    }
                }
                P[i][j] = ok;
            }
        }
        // S[i][j]: cells [i,n) can hold blocks j..k-1.
        boolean[][] S = new boolean[n + 2][k + 1];
        S[n][k] = true;
        S[n + 1][k] = true;
        for (int i = n - 1; i >= 0; i--) {
            for (int j = k; j >= 0; j--) {
                boolean ok = known[i] != FILLED && S[i + 1][j];
                if (!ok && j < k) {
                    int L = clue[j], e = i + L;
                    if (e <= n && zeros[e] - zeros[i] == 0) {
                        if (e == n) ok = j + 1 == k;
                        else ok = known[e] != FILLED && S[e + 1][j + 1];
                    }
                }
                S[i][j] = ok;
            }
        }
        if (!P[n][k]) return null;
        boolean[] canEmpty = new boolean[n];
        int[] fillDiff = new int[n + 1];
        for (int i = 0; i < n; i++) {
            if (known[i] == FILLED) continue;
            for (int j = 0; j <= k; j++) if (P[i][j] && S[i + 1][j]) { canEmpty[i] = true; break; }
        }
        for (int j = 0; j < k; j++) {
            int L = clue[j];
            for (int s = 0; s + L <= n; s++) {
                if (zeros[s + L] - zeros[s] != 0) continue;
                boolean pre = s == 0 ? j == 0 : (known[s - 1] != FILLED && P[s - 1][j]);
                if (!pre) continue;
                int e = s + L;
                boolean post = e == n ? j + 1 == k : (known[e] != FILLED && S[e + 1][j + 1]);
                if (!post) continue;
                fillDiff[s]++;
                fillDiff[e]--;
            }
        }
        int[] out = new int[n];
        int run = 0;
        for (int i = 0; i < n; i++) {
            run += fillDiff[i];
            boolean canFill = run > 0;
            if (!canFill && !canEmpty[i]) return null;
            out[i] = canFill && canEmpty[i] ? UNKNOWN : canFill ? FILLED : EMPTY;
            if (known[i] != UNKNOWN && out[i] != known[i]) {
                if (out[i] == UNKNOWN) out[i] = known[i];
                else return null;
            }
        }
        return out;
    }

    public static String text(int[] g, int rows, int cols) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = g[r * cols + c];
                sb.append(v == FILLED ? '#' : v == EMPTY ? '.' : '?');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
