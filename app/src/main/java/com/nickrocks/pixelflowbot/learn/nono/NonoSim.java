package com.nickrocks.pixelflowbot.learn.nono;

import com.nickrocks.pixelflowbot.learn.Env;
import com.nickrocks.pixelflowbot.nono.NonoSolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Nonogram simulator: random picture-like puzzles that have exactly one solution (checked with
 * the exact solver, which is only used here to validate puzzles — never by the agent). Behaves
 * like Nonogram.com: a correct fill stays, a wrong fill is marked with an X and costs a heart,
 * three mistakes end the game.
 */
public final class NonoSim implements Env<NonoState> {
    public int[] sizes = {5, 10, 10, 15};

    @Override
    public NonoState reset(Random rnd) {
        int n = sizes[rnd.nextInt(sizes.length)];
        for (int attempt = 0; attempt < 60; attempt++) {
            boolean[] img = picture(rnd, n, n);
            int[][] rc = new int[n][], cc = new int[n][];
            for (int r = 0; r < n; r++) {
                boolean[] line = new boolean[n];
                for (int c = 0; c < n; c++) line[c] = img[r * n + c];
                rc[r] = clue(line);
            }
            for (int c = 0; c < n; c++) {
                boolean[] line = new boolean[n];
                for (int r = 0; r < n; r++) line[r] = img[r * n + c];
                cc[c] = clue(line);
            }
            NonoSolver.Result res = new NonoSolver().solve(rc, cc, null, 500);
            if (res.status != NonoSolver.Status.SOLVED) continue;
            NonoState s = new NonoState(rc, cc);
            s.solution = img;
            return s;
        }
        // Fallback: a tiny always-unique puzzle.
        return reset5(rnd);
    }

    private NonoState reset5(Random rnd) {
        boolean[] img = new boolean[25];
        for (int i = 0; i < 25; i++) img[i] = i / 5 == 2 || i % 5 == 2;
        int[][] rc = new int[5][], cc = new int[5][];
        for (int r = 0; r < 5; r++) { boolean[] l = new boolean[5]; for (int c = 0; c < 5; c++) l[c] = img[r * 5 + c]; rc[r] = clue(l); }
        for (int c = 0; c < 5; c++) { boolean[] l = new boolean[5]; for (int r = 0; r < 5; r++) l[r] = img[r * 5 + c]; cc[c] = clue(l); }
        NonoState s = new NonoState(rc, cc);
        s.solution = img;
        return s;
    }

    /** Blobby random image (noise + one smoothing pass), like the game's pictures. */
    static boolean[] picture(Random rnd, int rows, int cols) {
        float p = 0.5f + rnd.nextFloat() * 0.15f;
        boolean[] a = new boolean[rows * cols];
        for (int i = 0; i < a.length; i++) a[i] = rnd.nextFloat() < p;
        boolean[] b = new boolean[a.length];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int on = 0, tot = 0;
                for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                    int rr = r + dr, cc = c + dc;
                    if (rr < 0 || cc < 0 || rr >= rows || cc >= cols) continue;
                    tot++;
                    if (a[rr * cols + cc]) on++;
                }
                b[r * cols + c] = on * 2 > tot || (on * 2 == tot && a[r * cols + c]);
            }
        }
        return b;
    }

    /** Like the game: when a row or column is complete, its remaining cells get crossed. */
    static void autoCross(NonoState s, int i) {
        int r = i / s.cols, c = i % s.cols;
        boolean rowDone = true, colDone = true;
        for (int k = 0; k < s.cols; k++) if (s.solution[r * s.cols + k] && s.cells[r * s.cols + k] != NonoState.FILLED) rowDone = false;
        for (int k = 0; k < s.rows; k++) if (s.solution[k * s.cols + c] && s.cells[k * s.cols + c] != NonoState.FILLED) colDone = false;
        if (rowDone) for (int k = 0; k < s.cols; k++) if (s.cells[r * s.cols + k] == NonoState.EMPTY) s.cells[r * s.cols + k] = NonoState.CROSS;
        if (colDone) for (int k = 0; k < s.rows; k++) if (s.cells[k * s.cols + c] == NonoState.EMPTY) s.cells[k * s.cols + c] = NonoState.CROSS;
    }

    static int[] clue(boolean[] line) {
        List<Integer> o = new ArrayList<>();
        int run = 0;
        for (boolean v : line) { if (v) run++; else if (run > 0) { o.add(run); run = 0; } }
        if (run > 0) o.add(run);
        int[] a = new int[o.size()];
        for (int i = 0; i < a.length; i++) a[i] = o.get(i);
        return a;
    }

    @Override
    public Outcome<NonoState> step(NonoState s, Object action) {
        int i = (Integer) action;
        Outcome<NonoState> o = new Outcome<>();
        NonoState n = s.copy();
        o.next = n;
        if (n.solution[i]) {
            n.cells[i] = NonoState.FILLED;
            o.reward = NonoAdapter.R_CORRECT;
            o.note = "filled";
            boolean solved = true;
            for (int k = 0; k < n.cells.length; k++) if (n.solution[k] && n.cells[k] != NonoState.FILLED) { solved = false; break; }
            if (solved) { o.done = true; o.win = true; o.reward += NonoAdapter.R_SOLVED; o.note = "solved"; }
            else autoCross(n, i);
        } else {
            n.cells[i] = NonoState.CROSS;   // the game marks the mistake
            n.mistakes++;
            o.reward = NonoAdapter.R_MISTAKE;
            o.note = "mistake";
            if (n.mistakes >= n.maxMistakes) { o.done = true; o.reward += NonoAdapter.R_FAILED; o.note = "out of hearts"; }
        }
        return o;
    }
}
