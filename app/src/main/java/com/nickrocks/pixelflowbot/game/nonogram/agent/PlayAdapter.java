package com.nickrocks.pixelflowbot.game.nonogram.agent;

import com.nickrocks.pixelflowbot.game.nonogram.Action;
import com.nickrocks.pixelflowbot.game.nonogram.Board;
import com.nickrocks.pixelflowbot.game.nonogram.Clues;
import com.nickrocks.pixelflowbot.game.nonogram.Observation;
import com.nickrocks.pixelflowbot.learn.GameAdapter;
import com.nickrocks.pixelflowbot.nono.NonoSolver;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an {@link Observation} into the learning agent's choices: every sensible action
 * (fill / mark empty for unknown cells, clear for cells in a line that visibly breaks its clue),
 * each described by features the linear Q-model scores. The agent learns the weights.
 *
 * Feature groups — kept separate and named so it is always clear what the agent was given:
 *  - BASIC: plain counting from what's on screen (clue density, progress, neighbours, edges…).
 *  - HEURISTIC: hand-written human tricks, computed here (not by the solver): "simple boxes"
 *    (block overlap) and "simple spaces" (cells no block can reach).
 *  - SOLVER-ASSISTED (only when {@code assisted}): the exact single-line solver's verdict for the
 *    cell. This IS constraint-solver output; models trained with it are stored separately and
 *    every move they make is labelled "solver-assisted".
 */
public final class PlayAdapter implements GameAdapter<Observation> {
    public final boolean assisted;

    private static final String[] BASIC = {
            "clue density", "fill progress", "clue count reached", "clue is 0",
            "next to a filled cell", "distance from edge", "unknown share of line", "line over-filled"};
    private static final String[] HEURISTIC = {
            "simple boxes (overlap)", "overlap given ✕ marks", "simple spaces (unreachable)"};
    private static final String[] SOLVER = {
            "[solver] line forces filled", "[solver] line forces empty", "[solver] line contradicts clue"};

    private final int lineLen;
    private final String[] names;
    private final int fillBase, markBase, clearBase;

    public PlayAdapter(boolean assisted) {
        this.assisted = assisted;
        this.lineLen = BASIC.length + HEURISTIC.length + (assisted ? SOLVER.length : 0);
        List<String> n = new ArrayList<>();
        fillBase = 0;
        addBlock(n, "fill");
        markBase = n.size();
        addBlock(n, "mark");
        clearBase = n.size();
        n.add("clear: bias");
        n.add("clear: cell is filled");
        n.add("clear: cell is marked ✕");
        n.add("clear: a line here is over-filled");
        n.add("clear: a line here has a run too long");
        if (assisted) n.add("clear: [solver] a line here contradicts its clue");
        names = n.toArray(new String[0]);
    }

    private void addBlock(List<String> n, String verb) {
        n.add(verb + ": bias");
        for (String p : new String[]{"row", "col"}) {
            for (String s : BASIC) n.add(verb + ": " + p + " " + s);
            for (String s : HEURISTIC) n.add(verb + ": " + p + " " + s);
            if (assisted) for (String s : SOLVER) n.add(verb + ": " + p + " " + s);
        }
        n.add(verb + ": row & col both overlap");
        n.add(verb + ": row or col count reached");
        n.add(verb + ": row or col unreachable");
        if (assisted) n.add(verb + ": [solver] both lines force filled");
        n.add(verb + ": I cleared this cell in my last moves");
    }

    /** Learning settings used for every model of this game (also after load/reset). */
    public static void configure(com.nickrocks.pixelflowbot.learn.Agent agent) {
        agent.model.normalize = true;
        agent.model.alpha = 0.1f;
        agent.model.gamma = 0.1f;
        agent.epsStart = 0.25f;
        agent.epsEnd = 0.02f;
        agent.epsDecayEpisodes = 600;
    }

    @Override public String id() { return assisted ? "nonogram-game-assisted" : "nonogram-game"; }
    @Override public String title() { return assisted ? "In-app Nonogram (solver-assisted)" : "In-app Nonogram"; }
    @Override public String[] featureNames() { return names; }

    @Override
    public List<Candidate> candidates(Observation s) {
        int rows = s.rows, cols = s.cols;
        float[][][] rowF = new float[rows][][];
        float[][][] colF = new float[cols][][];
        boolean[] rowBad = new boolean[rows], colBad = new boolean[cols];
        boolean[] rowLong = new boolean[rows], colLong = new boolean[cols];
        boolean[] rowContra = new boolean[rows], colContra = new boolean[cols];
        for (int r = 0; r < rows; r++) {
            int[] line = new int[cols];
            for (int c = 0; c < cols; c++) line[c] = s.cell(r, c);
            LineInfo li = analyse(s.rowClue(r), line);
            rowF[r] = li.cell;
            rowBad[r] = li.overfull;
            rowLong[r] = li.runTooLong;
            rowContra[r] = li.contradiction;
        }
        for (int c = 0; c < cols; c++) {
            int[] line = new int[rows];
            for (int r = 0; r < rows; r++) line[r] = s.cell(r, c);
            LineInfo li = analyse(s.colClue(c), line);
            colF[c] = li.cell;
            colBad[c] = li.overfull;
            colLong[c] = li.runTooLong;
            colContra[c] = li.contradiction;
        }
        // Loop guard from the agent's own recent actions: a fill/mark it made and then cleared
        // again is not offered again for a while (it already led nowhere).
        recentlyCleared = new boolean[rows * cols];
        boolean[] triedFill = new boolean[rows * cols], triedMark = new boolean[rows * cols];
        boolean[] noFill = new boolean[rows * cols], noMark = new boolean[rows * cols];
        for (Action a : s.recentActions()) {
            int i = a.row * cols + a.col;
            if (a.type == Action.Type.FILL) triedFill[i] = true;
            else if (a.type == Action.Type.MARK_EMPTY) triedMark[i] = true;
            else {
                recentlyCleared[i] = true;
                if (triedFill[i]) noFill[i] = true;
                if (triedMark[i]) noMark[i] = true;
            }
        }
        List<Candidate> out = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = s.cell(r, c);
                float[] a = rowF[r][c], b = colF[c][r];
                if (v == Board.UNKNOWN) {
                    boolean cleared = recentlyCleared[r * cols + c];
                    int i = r * cols + c;
                    if (!noFill[i]) out.add(new Candidate(Action.fill(r, c).toString(), block(fillBase, a, b, cleared), Action.fill(r, c)));
                    if (!noMark[i]) out.add(new Candidate(Action.markEmpty(r, c).toString(), block(markBase, a, b, cleared), Action.markEmpty(r, c)));
                } else {
                    boolean bad = rowBad[r] || colBad[c], tooLong = rowLong[r] || colLong[c];
                    boolean contra = assisted && (rowContra[r] || colContra[c]);
                    if (!bad && !tooLong && !contra) continue;   // clearing a consistent cell makes no progress
                    float[] f = new float[names.length];
                    int k = clearBase;
                    f[k++] = 1;
                    f[k++] = v == Board.FILLED ? 1 : 0;
                    f[k++] = v == Board.EMPTY ? 1 : 0;
                    f[k++] = bad ? 1 : 0;
                    f[k++] = tooLong ? 1 : 0;
                    if (assisted) f[k] = contra ? 1 : 0;
                    out.add(new Candidate(Action.clear(r, c).toString(), f, Action.clear(r, c)));
                }
            }
        }
        return out;
    }

    // Line feature layout (indices into LineInfo.cell[i]).
    private static final int B_DENSITY = 0, B_PROGRESS = 1, B_DONE = 2, B_ZERO = 3, B_NEIGHBOUR = 4,
            B_EDGE = 5, B_UNKNOWN = 6, B_OVER = 7, H_BOX = 8, H_OVERLAP = 9, H_SPACE = 10, S_FILL = 11, S_EMPTY = 12, S_CONTRA = 13;

    private boolean[] recentlyCleared;

    private float[] block(int base, float[] a, float[] b, boolean cleared) {
        float[] f = new float[names.length];
        int k = base;
        f[k++] = 1;
        for (int i = 0; i < lineLen; i++) f[k++] = a[i];
        for (int i = 0; i < lineLen; i++) f[k++] = b[i];
        f[k++] = Math.max(a[H_BOX], a[H_OVERLAP]) * Math.max(b[H_BOX], b[H_OVERLAP]);
        f[k++] = Math.max(a[B_DONE], b[B_DONE]);
        f[k++] = Math.max(a[H_SPACE], b[H_SPACE]);
        if (assisted) f[k++] = a[S_FILL] * b[S_FILL];
        f[k] = cleared ? 1 : 0;
        return f;
    }

    private static final class LineInfo {
        float[][] cell;
        boolean overfull, runTooLong, contradiction;
    }

    private LineInfo analyse(int[] clue, int[] line) {
        int n = line.length;
        LineInfo li = new LineInfo();
        li.cell = new float[n][14];
        int sum = Clues.sum(clue), used = Clues.minLength(clue);
        int filled = 0, unknown = 0, run = 0, maxRun = 0, maxClue = 0;
        for (int v : clue) maxClue = Math.max(maxClue, v);
        for (int v : line) {
            if (v == Board.FILLED) { filled++; run++; maxRun = Math.max(maxRun, run); } else run = 0;
            if (v == Board.UNKNOWN) unknown++;
        }
        li.overfull = filled > sum;
        li.runTooLong = maxRun > maxClue;
        boolean done = filled >= sum;
        boolean[] box = pack(clue, n, null, false);
        boolean[] overlap = pack(clue, n, line, false);
        boolean[] reach = pack(clue, n, line, true);
        int[] logic = null;
        if (assisted) {
            int[] known = new int[n];
            for (int i = 0; i < n; i++) known[i] = line[i] == Board.FILLED ? 1 : line[i] == Board.EMPTY ? 0 : -1;
            logic = NonoSolver.solveLine(clue, known, n);
            li.contradiction = logic == null;
        }
        for (int i = 0; i < n; i++) {
            float[] f = li.cell[i];
            f[B_DENSITY] = used / (float) n;
            f[B_PROGRESS] = sum == 0 ? 1 : Math.min(1f, filled / (float) sum);
            f[B_DONE] = done ? 1 : 0;
            f[B_ZERO] = sum == 0 ? 1 : 0;
            f[B_NEIGHBOUR] = (i > 0 && line[i - 1] == Board.FILLED) || (i + 1 < n && line[i + 1] == Board.FILLED) ? 1 : 0;
            f[B_EDGE] = n > 1 ? Math.min(i, n - 1 - i) / ((n - 1) / 2f) : 0;
            f[B_UNKNOWN] = unknown / (float) n;
            f[B_OVER] = li.overfull ? 1 : 0;
            f[H_BOX] = box != null && box[i] ? 1 : 0;
            f[H_OVERLAP] = overlap != null && overlap[i] ? 1 : 0;
            f[H_SPACE] = reach != null && !reach[i] ? 1 : 0;
            if (logic != null) {
                f[S_FILL] = logic[i] == 1 ? 1 : 0;
                f[S_EMPTY] = logic[i] == 0 ? 1 : 0;
            }
            f[S_CONTRA] = li.contradiction ? 1 : 0;
        }
        return li;
    }

    /**
     * Packs every block as far left and as far right as it can go (skipping ✕-marked cells when
     * {@code line} is given). Returns the cells covered in both packings ("overlap"), or with
     * {@code reach} the cells any block could cover at all. Null if the blocks don't fit.
     * Hand-written heuristic — the exact line solver is not used here.
     */
    static boolean[] pack(int[] clue, int n, int[] line, boolean reach) {
        boolean[] out = new boolean[n];
        int k = clue.length;
        if (k == 0) return out;
        int[] left = new int[k], right = new int[k];
        int pos = 0;
        for (int j = 0; j < k; j++) {
            int p = fitForward(clue[j], pos, n, line);
            if (p < 0) return null;
            left[j] = p;
            pos = p + clue[j] + 1;
        }
        pos = n - 1;
        for (int j = k - 1; j >= 0; j--) {
            int end = fitBackward(clue[j], pos, line);
            if (end < 0) return null;
            right[j] = end - clue[j] + 1;
            pos = right[j] - 2;
        }
        for (int j = 0; j < k; j++) {
            if (reach) { for (int i = left[j]; i < right[j] + clue[j] && i < n; i++) if (i >= 0) out[i] = true; }
            else { for (int i = right[j]; i < left[j] + clue[j]; i++) if (i >= 0 && i < n) out[i] = true; }
        }
        return out;
    }

    private static int fitForward(int len, int from, int n, int[] line) {
        for (int p = Math.max(0, from); p + len <= n; p++) if (clear(p, len, line)) return p;
        return -1;
    }

    private static int fitBackward(int len, int from, int[] line) {
        for (int e = from; e - len + 1 >= 0; e--) if (clear(e - len + 1, len, line)) return e;
        return -1;
    }

    private static boolean clear(int p, int len, int[] line) {
        if (line == null) return true;
        for (int i = p; i < p + len; i++) if (line[i] == Board.EMPTY) return false;
        return true;
    }

    @Override
    public String describe(Observation s) { return s.summary(); }
}
