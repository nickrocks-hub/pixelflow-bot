package com.nickrocks.pixelflowbot.game.blocks;

/** Immutable-by-convention snapshot of a Block Puzzle game. Everything here is visible to the player. */
public final class BlockState {
    public final boolean[] board;   // N*N, true = filled
    public final int[] tray;        // 3 piece shapes, −1 = already placed
    public final int score, placed, lines;
    public final boolean over;

    public BlockState(boolean[] board, int[] tray, int score, int placed, int lines, boolean over) {
        this.board = board;
        this.tray = tray;
        this.score = score;
        this.placed = placed;
        this.lines = lines;
        this.over = over;
    }

    public int filled() {
        int n = 0;
        for (boolean b : board) if (b) n++;
        return n;
    }

    /** Action encoding: tray slot × 64 + row × 8 + col. */
    public static int encode(int slot, int r, int c) { return slot * 64 + r * Blocks.N + c; }
}
