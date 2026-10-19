package com.nickrocks.pixelflowbot.core.flow;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact solver for Flow Free puzzles: connect each pair of same-coloured dots with a pipe so that
 * pipes never cross and every cell is filled.
 *
 * Depth-first search that always extends the most constrained pipe (fewest legal steps), with the
 * standard prunings: no empty cell may become a dead end, every unfinished pipe's head must still
 * reach its other dot through empty cells, every empty region must be usable by some pipe, and
 * (first pass) a pipe never runs alongside itself. If the first pass finds nothing, it retries
 * without that last rule. Pure Java, unit-tested.
 */
public final class FlowSolver {

    public static final class Result {
        public boolean solved;
        /** Per colour, the cells in order from one dot to the other (cell = row*cols+col). */
        public List<int[]> paths = new ArrayList<>();
        public long nodes, ms;
        public String problem;
    }

    private final int rows, cols, n, colors;
    private final int[] grid;              // colour per cell, -1 empty
    private final int[] head, target;      // per colour
    private final boolean[] done;
    private final int[][] nbr;
    private boolean noSelfTouch = true;
    private long nodes, deadline;
    private int[][] pathStack;             // per colour, cells from the start dot
    private int[] pathLen;

    private FlowSolver(int rows, int cols, int[][] dots) {
        this.rows = rows;
        this.cols = cols;
        this.n = rows * cols;
        this.colors = dots.length;
        grid = new int[n];
        java.util.Arrays.fill(grid, -1);
        head = new int[colors];
        target = new int[colors];
        done = new boolean[colors];
        pathStack = new int[colors][n];
        pathLen = new int[colors];
        for (int c = 0; c < colors; c++) {
            head[c] = dots[c][0];
            target[c] = dots[c][1];
            grid[head[c]] = c;
            grid[target[c]] = c;
            pathStack[c][0] = head[c];
            pathLen[c] = 1;
        }
        nbr = new int[n][];
        for (int i = 0; i < n; i++) {
            int r = i / cols, c = i % cols;
            List<Integer> l = new ArrayList<>(4);
            if (r > 0) l.add(i - cols);
            if (r < rows - 1) l.add(i + cols);
            if (c > 0) l.add(i - 1);
            if (c < cols - 1) l.add(i + 1);
            nbr[i] = new int[l.size()];
            for (int k = 0; k < l.size(); k++) nbr[i][k] = l.get(k);
        }
    }

    /** {@code dots[c] = {cellA, cellB}}. */
    public static Result solve(int rows, int cols, int[][] dots, long timeLimitMs) {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        for (int pass = 0; pass < 2; pass++) {
            FlowSolver s = new FlowSolver(rows, cols, dots);
            s.noSelfTouch = pass == 0;
            // The first pass (no pipe alongside itself) gets half the time; the general pass the rest.
            s.deadline = pass == 0 ? t0 + timeLimitMs / 2 : t0 + timeLimitMs;
            for (int c = 0; c < s.colors; c++) {
                // Start each pipe from its more constrained dot.
                if (s.openCount(s.target[c]) < s.openCount(s.head[c])) {
                    int t = s.head[c]; s.head[c] = s.target[c]; s.target[c] = t;
                    s.pathStack[c][0] = s.head[c];
                }
            }
            boolean ok;
            try {
                ok = s.search();
            } catch (TimeoutException e) {
                res.nodes += s.nodes;
                if (pass == 0) continue;
                res.problem = "took longer than " + timeLimitMs / 1000 + " s";
                res.ms = System.currentTimeMillis() - t0;
                return res;
            }
            res.nodes += s.nodes;
            if (ok) {
                res.solved = true;
                for (int c = 0; c < s.colors; c++) res.paths.add(java.util.Arrays.copyOf(s.pathStack[c], s.pathLen[c]));
                res.ms = System.currentTimeMillis() - t0;
                return res;
            }
        }
        res.problem = "no solution — the board was probably misread";
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    private static final class TimeoutException extends RuntimeException {
        TimeoutException() { super(null, null, false, false); }
    }

    private boolean adjacent(int a, int b) {
        for (int m : nbr[a]) if (m == b) return true;
        return false;
    }

    private int openCount(int cell) {
        int k = 0;
        for (int m : nbr[cell]) if (grid[m] < 0) k++;
        return k;
    }

    /** Legal next cells for colour c (target first if adjacent). */
    private int moves(int c, int[] out) {
        int h = head[c], k = 0;
        for (int m : nbr[h]) {
            if (m == target[c]) { out[k++] = m; continue; }
            if (grid[m] >= 0) continue;
            if (noSelfTouch) {
                boolean touch = false;
                for (int q : nbr[m]) if (q != h && q != target[c] && grid[q] == c) { touch = true; break; }
                if (touch) continue;
            }
            out[k++] = m;
        }
        return k;
    }

    private boolean search() {
        if ((++nodes & 1023) == 0 && System.currentTimeMillis() > deadline) throw new TimeoutException();
        // Pick the unfinished colour with the fewest moves.
        int best = -1, bestK = Integer.MAX_VALUE;
        int[] buf = new int[4];
        boolean any = false;
        for (int c = 0; c < colors; c++) {
            if (done[c]) continue;
            any = true;
            int k = moves(c, buf);
            if (k == 0) return false;
            if (k < bestK) { bestK = k; best = c; if (k == 1) break; }
        }
        if (!any) {
            for (int i = 0; i < n; i++) if (grid[i] < 0) return false;
            return true;
        }
        int c = best;
        int[] mv = new int[4];
        int k = moves(c, mv);
        // Try the target first, then cells with fewer open neighbours (hug walls and other pipes).
        sortMoves(c, mv, k);
        int h = head[c];
        for (int i = 0; i < k; i++) {
            int m = mv[i];
            if (m == target[c]) {
                done[c] = true;
                pathStack[c][pathLen[c]++] = m;
                if (consistent() && search()) return true;
                pathLen[c]--;
                done[c] = false;
            } else {
                grid[m] = c;
                head[c] = m;
                pathStack[c][pathLen[c]++] = m;
                if (consistent() && search()) return true;
                pathLen[c]--;
                head[c] = h;
                grid[m] = -1;
            }
        }
        return false;
    }

    private void sortMoves(int c, int[] mv, int k) {
        int[] key = new int[k];
        for (int i = 0; i < k; i++) key[i] = mv[i] == target[c] ? -1 : openCount(mv[i]);
        for (int i = 1; i < k; i++)
            for (int j = i; j > 0 && key[j] < key[j - 1]; j--) {
                int t = key[j]; key[j] = key[j - 1]; key[j - 1] = t;
                t = mv[j]; mv[j] = mv[j - 1]; mv[j - 1] = t;
            }
    }

    /** Dead ends and stranded pipes/regions. */
    private boolean consistent() {
        // Dead ends: an empty cell needs two open sides (empty, or an unfinished pipe's head or dot).
        for (int i = 0; i < n; i++) {
            if (grid[i] >= 0) continue;
            int open = 0;
            for (int m : nbr[i]) {
                int g = grid[m];
                if (g < 0 || (!done[g] && (m == head[g] || m == target[g]))) open++;
            }
            if (open < 2) return false;
        }
        // Regions of empty cells.
        int[] region = new int[n];
        java.util.Arrays.fill(region, -1);
        int regions = 0;
        int[] stack = new int[n];
        for (int i = 0; i < n; i++) {
            if (grid[i] >= 0 || region[i] >= 0) continue;
            int sp = 0;
            stack[sp++] = i;
            region[i] = regions;
            while (sp > 0) {
                int v = stack[--sp];
                for (int m : nbr[v]) if (grid[m] < 0 && region[m] < 0) { region[m] = regions; stack[sp++] = m; }
            }
            regions++;
        }
        boolean[] regionUsed = new boolean[regions];
        for (int c = 0; c < colors; c++) {
            if (done[c]) continue;
            if (adjacent(head[c], target[c])) {
                // Can finish right away; it may also still detour through a region it touches on both ends.
                markShared(c, region, regionUsed);
                continue;
            }
            if (!markShared(c, region, regionUsed)) return false;   // can't reach its other dot any more
        }
        for (int r = 0; r < regions; r++) if (!regionUsed[r]) return false;   // nobody can fill this region
        return true;
    }

    /** Marks the regions touched by both ends of colour c; false if there are none. */
    private boolean markShared(int c, int[] region, boolean[] used) {
        boolean found = false;
        for (int a : nbr[head[c]]) {
            if (grid[a] >= 0) continue;
            int ra = region[a];
            for (int b : nbr[target[c]]) {
                if (grid[b] < 0 && region[b] == ra) { used[ra] = true; found = true; break; }
            }
        }
        return found;
    }
}
