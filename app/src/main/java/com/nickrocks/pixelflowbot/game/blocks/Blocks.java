package com.nickrocks.pixelflowbot.game.blocks;

/**
 * Block Puzzle rules (in the style of 1010!/Block Blast).
 *
 *  - 8×8 board. You get a tray of 3 pieces; place each anywhere it fits (no rotation).
 *    When all 3 are placed you get 3 new ones.
 *  - A full row or column is cleared at once (rows and columns together).
 *  - Score: +1 per cell placed, +10 per line cleared, +10 extra per line beyond the first in one move.
 *  - Game over when none of the pieces left in the tray fits anywhere.
 */
public final class Blocks {
    private Blocks() { }

    public static final int N = 8;

    /** Piece shapes as (row, col) cell lists. */
    public static final int[][][] SHAPES = {
            {{0, 0}},                                              // dot
            {{0, 0}, {0, 1}}, {{0, 0}, {1, 0}},                    // I2
            {{0, 0}, {0, 1}, {0, 2}}, {{0, 0}, {1, 0}, {2, 0}},    // I3
            {{0, 0}, {0, 1}, {0, 2}, {0, 3}}, {{0, 0}, {1, 0}, {2, 0}, {3, 0}},
            {{0, 0}, {0, 1}, {0, 2}, {0, 3}, {0, 4}}, {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {4, 0}},
            {{0, 0}, {0, 1}, {1, 0}, {1, 1}},                      // 2×2
            {{0, 0}, {0, 1}, {0, 2}, {1, 0}, {1, 1}, {1, 2}, {2, 0}, {2, 1}, {2, 2}},   // 3×3
            {{0, 0}, {1, 0}, {1, 1}}, {{0, 0}, {0, 1}, {1, 0}}, {{0, 0}, {0, 1}, {1, 1}}, {{0, 1}, {1, 0}, {1, 1}},  // small L
            {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}}, {{0, 0}, {0, 1}, {0, 2}, {1, 0}, {2, 0}},                   // big L
            {{0, 0}, {0, 1}, {0, 2}, {1, 2}, {2, 2}}, {{0, 2}, {1, 2}, {2, 0}, {2, 1}, {2, 2}},
    };

    public static int size(int shape) { return SHAPES[shape].length; }

    public static int height(int shape) {
        int h = 0;
        for (int[] c : SHAPES[shape]) h = Math.max(h, c[0] + 1);
        return h;
    }

    public static int width(int shape) {
        int w = 0;
        for (int[] c : SHAPES[shape]) w = Math.max(w, c[1] + 1);
        return w;
    }

    public static boolean fits(boolean[] board, int shape, int r, int c) {
        for (int[] d : SHAPES[shape]) {
            int rr = r + d[0], cc = c + d[1];
            if (rr < 0 || cc < 0 || rr >= N || cc >= N || board[rr * N + cc]) return false;
        }
        return true;
    }

    public static boolean fitsAnywhere(boolean[] board, int shape) {
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) if (fits(board, shape, r, c)) return true;
        return false;
    }

    public static int placements(boolean[] board, int shape) {
        int n = 0;
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) if (fits(board, shape, r, c)) n++;
        return n;
    }

    /** Result of placing a piece: the new board after clears, and how many lines cleared. */
    public static final class Placed {
        public boolean[] board;
        public int lines, cells;
        public int points;
    }

    public static Placed place(boolean[] board, int shape, int r, int c) {
        if (!fits(board, shape, r, c)) throw new IllegalArgumentException("piece doesn't fit there");
        boolean[] b = board.clone();
        for (int[] d : SHAPES[shape]) b[(r + d[0]) * N + c + d[1]] = true;
        boolean[] fullRow = new boolean[N], fullCol = new boolean[N];
        int lines = 0;
        for (int i = 0; i < N; i++) {
            boolean row = true, col = true;
            for (int k = 0; k < N; k++) { if (!b[i * N + k]) row = false; if (!b[k * N + i]) col = false; }
            fullRow[i] = row;
            fullCol[i] = col;
            if (row) lines++;
            if (col) lines++;
        }
        for (int i = 0; i < N; i++) for (int k = 0; k < N; k++) if (fullRow[i] || fullCol[k]) b[i * N + k] = false;
        Placed p = new Placed();
        p.board = b;
        p.lines = lines;
        p.cells = size(shape);
        p.points = p.cells + 10 * lines + (lines > 1 ? 10 * (lines - 1) : 0);
        return p;
    }
}
