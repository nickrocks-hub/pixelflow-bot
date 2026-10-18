package com.nickrocks.pixelflowbot.candy;

public final class BoardCheck {
    private BoardCheck() {
    }

    public static String problem(Board board, Board board2, float f) {
        if (board == null) {
            return "no board";
        }
        int count = (board.rows * board.cols) - board.count(0);
        int count2 = board.count(2);
        if (count2 < 8 || count2 < 0.45f * count) {
            return "board not readable (" + count2 + " candies of " + count + " cells)";
        }
        if (board.count(3) > count * 0.35f) {
            return "too much of the board is covered";
        }
        if (f > 0.0f && board.candyBrightness < 0.8f * f) {
            return "board looks dimmed (popup?)";
        }
        if (board2 != null && board2.rows == board.rows && board2.cols == board.cols) {
            int i = 0;
            for (int i2 = 0; i2 < board.kind.length; i2++) {
                boolean z = board2.kind[i2] == 0;
                boolean z2 = board.kind[i2] == 0;
                boolean z3 = board2.kind[i2] == 2;
                boolean z4 = board.kind[i2] == 2;
                if (z != z2 || (z3 && !z4 && board.kind[i2] != 3)) {
                    i++;
                }
            }
            if (i > Math.max(6.0f, 0.25f * board.kind.length)) {
                return "board shape changed (" + i + " cells) — covered by something?";
            }
        }
        return null;
    }
}
