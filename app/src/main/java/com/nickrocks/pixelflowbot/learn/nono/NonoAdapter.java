package com.nickrocks.pixelflowbot.learn.nono;

import com.nickrocks.pixelflowbot.learn.GameAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nonogram adapter. Action = "fill this empty cell". Each candidate cell is described by
 * features of its row and its column computed from the clues and the cells already known —
 * classic human heuristics, NOT the exact solver. The agent has to learn how much to trust each
 * one from the rewards it gets.
 *
 * Rewards (shared by the simulator and the real game, so both teach the same thing):
 *   correct fill            +1
 *   mistake (wrong cell)    −5      (costs a heart in the real game)
 *   puzzle solved           +10     (on the final fill)
 *   out of hearts           −10     (on the third mistake)
 * Only empty cells are actions, so there is nothing to gain by repeating moves, and every
 * episode ends (solved or out of hearts) within rows×cols steps.
 */
public final class NonoAdapter implements GameAdapter<NonoState> {
    public static final float R_CORRECT = 1f, R_MISTAKE = -5f, R_SOLVED = 10f, R_FAILED = -10f;

    private static final String[] LINE = {
            "overlap (blocks always cover this cell)",
            "overlap given crosses",
            "line already complete",
            "line has no blocks (clue 0)",
            "line fill progress",
            "next to a filled cell",
            "distance from edge",
            "line density (clue length / size)",
            "clue hidden (frozen)",
            "slack small (≤1)",
            "line logic: must be filled",
            "line logic: must stay empty"};

    private static final String[] NAMES;
    static {
        List<String> n = new ArrayList<>();
        n.add("bias");
        for (String s : LINE) n.add("row: " + s);
        for (String s : LINE) n.add("col: " + s);
        n.add("both lines say overlap");
        n.add("either line complete");
        n.add("row × col density");
        n.add("both lines' logic: must be filled");
        n.add("either line's logic: must stay empty");
        NAMES = n.toArray(new String[0]);
    }

    @Override public String id() { return "nonogram"; }
    @Override public String title() { return "Nonogram"; }
    @Override public String[] featureNames() { return NAMES; }

    @Override
    public List<Candidate> candidates(NonoState s) {
        // Per-line analysis once, then combine per cell.
        float[][] rowF = new float[s.rows][], colF = new float[s.cols][];
        float[][][] rowCell = new float[s.rows][][], colCell = new float[s.cols][][];
        for (int r = 0; r < s.rows; r++) {
            int[] line = new int[s.cols];
            for (int c = 0; c < s.cols; c++) line[c] = s.cells[r * s.cols + c];
            rowCell[r] = lineFeatures(s.rowClues[r], line);
        }
        for (int c = 0; c < s.cols; c++) {
            int[] line = new int[s.rows];
            for (int r = 0; r < s.rows; r++) line[r] = s.cells[r * s.cols + c];
            colCell[c] = lineFeatures(s.colClues[c], line);
        }
        List<Candidate> out = new ArrayList<>();
        for (int r = 0; r < s.rows; r++) {
            for (int c = 0; c < s.cols; c++) {
                int i = r * s.cols + c;
                if (s.cells[i] != NonoState.EMPTY) continue;
                float[] a = rowCell[r][c], b = colCell[c][r];
                float[] f = new float[NAMES.length];
                int k = 0;
                f[k++] = 1f;
                for (float v : a) f[k++] = v;
                for (float v : b) f[k++] = v;
                f[k++] = Math.max(a[0], a[1]) * Math.max(b[0], b[1]);
                f[k++] = Math.max(a[2], b[2]);
                f[k++] = a[7] * b[7];
                f[k++] = a[10] * b[10];
                f[k++] = Math.max(a[11], b[11]);
                out.add(new Candidate(String.format(Locale.US, "fill r%d c%d", r + 1, c + 1), f, i));
            }
        }
        return out;
    }

    /** Features for every cell of one line: [cell][LINE.length]. */
    static float[][] lineFeatures(int[] clue, int[] line) {
        int n = line.length;
        float[][] out = new float[n][LINE.length];
        boolean hidden = clue == null;
        int sum = 0, k = 0;
        if (!hidden) { for (int v : clue) sum += v; k = clue.length; }
        int filled = 0;
        for (int v : line) if (v == NonoState.FILLED) filled++;
        int used = hidden ? 0 : sum + Math.max(0, k - 1);
        int slack = n - used;
        boolean[] stat = hidden ? new boolean[n] : overlap(clue, n, null);
        boolean[] cross = hidden ? new boolean[n] : overlap(clue, n, line);
        boolean complete = !hidden && filled >= sum;
        boolean zero = !hidden && sum == 0;
        float progress = hidden || sum == 0 ? 0 : filled / (float) sum;
        float density = hidden ? 0.5f : used / (float) n;
        // Single-line logic (what a person deduces looking at this row or column alone, given its
        // current marks). Lines are never combined here — cross-line reasoning is left to the agent.
        int[] known = new int[n];
        for (int i = 0; i < n; i++) known[i] = line[i] == NonoState.FILLED ? 1 : line[i] == NonoState.CROSS ? 0 : -1;
        int[] logic = hidden ? null : com.nickrocks.pixelflowbot.nono.NonoSolver.solveLine(clue, known, n);
        for (int i = 0; i < n; i++) {
            float[] f = out[i];
            f[0] = stat[i] ? 1 : 0;
            f[1] = cross[i] ? 1 : 0;
            f[2] = complete ? 1 : 0;
            f[3] = zero ? 1 : 0;
            f[4] = progress;
            f[5] = (i > 0 && line[i - 1] == NonoState.FILLED) || (i + 1 < n && line[i + 1] == NonoState.FILLED) ? 1 : 0;
            f[6] = n > 1 ? Math.min(i, n - 1 - i) / (float) ((n - 1) / 2f) : 0;
            f[7] = density;
            f[8] = hidden ? 1 : 0;
            f[9] = !hidden && slack <= 1 && sum > 0 ? 1 : 0;
            f[10] = logic != null && logic[i] == 1 ? 1 : 0;
            f[11] = logic != null && logic[i] == 0 ? 1 : 0;
        }
        return out;
    }

    /**
     * "Simple boxes": pack every block as far left and as far right as possible; cells covered by
     * the same block in both packings must be filled. With {@code line}, crossed cells are avoided
     * when packing (a sound but incomplete use of what's known). Returns all-false if packing fails.
     */
    static boolean[] overlap(int[] clue, int n, int[] line) {
        boolean[] out = new boolean[n];
        int k = clue.length;
        if (k == 0) return out;
        int[] left = new int[k], right = new int[k];
        int pos = 0;
        for (int j = 0; j < k; j++) {
            int p = fit(clue[j], pos, n, line, true);
            if (p < 0) return out;
            left[j] = p;
            pos = p + clue[j] + 1;
        }
        pos = n - 1;
        for (int j = k - 1; j >= 0; j--) {
            int end = fit(clue[j], pos, n, line, false);
            if (end < 0) return out;
            right[j] = end - clue[j] + 1;
            pos = right[j] - 2;
        }
        for (int j = 0; j < k; j++) {
            for (int i = right[j]; i < left[j] + clue[j]; i++) if (i >= 0 && i < n) out[i] = true;
        }
        return out;
    }

    /** Earliest start ≥ from (forward) or latest end ≤ from (backward) for a block avoiding crosses. */
    private static int fit(int len, int from, int n, int[] line, boolean forward) {
        if (forward) {
            for (int p = Math.max(0, from); p + len <= n; p++) {
                if (clear(p, len, line)) return p;
            }
        } else {
            for (int e = Math.min(n - 1, from); e - len + 1 >= 0; e--) {
                if (clear(e - len + 1, len, line)) return e;
            }
        }
        return -1;
    }

    private static boolean clear(int p, int len, int[] line) {
        if (line == null) return true;
        for (int i = p; i < p + len; i++) if (line[i] == NonoState.CROSS) return false;
        return true;
    }

    @Override
    public String describe(NonoState s) {
        return s.cols + "x" + s.rows + " filled " + s.filledCount() + "/" + s.clueTotal() + " mistakes " + s.mistakes;
    }
}
