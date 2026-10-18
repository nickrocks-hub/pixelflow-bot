package com.nickrocks.pixelflowbot.candy;

import java.util.Arrays;

public final class Board {
    public static final int BLOCKER = 1;
    public static final int BLUE = 4;
    public static final int BOMB = 4;
    public static final int CANDY = 2;
    public static final String COLOR_CODES = "ROYGBP";
    public static final int GREEN = 3;
    public static final int HOLE = 0;
    public static final int NONE = 0;
    public static final int NO_COLOR = -1;
    public static final int ORANGE = 1;
    public static final int PURPLE = 5;
    public static final int RED = 0;
    public static final int STRIPED_H = 1;
    public static final int STRIPED_V = 2;
    public static final int UNKNOWN = 3;
    public static final int WRAPPED = 3;
    public static final int YELLOW = 2;
    public float candyBrightness;
    public float cell;
    public final int[] color;
    public final int cols;
    public final boolean[] jelly;
    public final int[] kind;
    public final boolean[] onTile;
    public final int rows;
    public final int[] special;
    public float x0;
    public float y0;

    public Board(int i, int i2) {
        this.rows = i;
        this.cols = i2;
        int i3 = i * i2;
        this.kind = new int[i3];
        this.color = new int[i3];
        this.special = new int[i3];
        this.jelly = new boolean[i3];
        this.onTile = new boolean[i3];
        Arrays.fill(this.color, -1);
    }

    public int idx(int i, int i2) {
        return (this.cols * i) + i2;
    }

    public boolean in(int i, int i2) {
        return i >= 0 && i2 >= 0 && i < this.rows && i2 < this.cols;
    }

    public float cx(int i) {
        return this.x0 + (i * this.cell);
    }

    public float cy(int i) {
        return this.y0 + (i * this.cell);
    }

    public void set(int i, int i2, int i3, int i4, int i5) {
        int idx = idx(i, i2);
        this.kind[idx] = i3;
        this.color[idx] = i4;
        this.special[idx] = i5;
    }

    public int count(int i) {
        int i2 = 0;
        for (int i3 : this.kind) {
            if (i3 == i) {
                i2++;
            }
        }
        return i2;
    }

    public String signature() {
        StringBuilder sb = new StringBuilder((this.rows * this.cols) + this.rows);
        for (int i = 0; i < this.kind.length; i++) {
            sb.append(this.kind[i]).append(this.color[i] + 1).append(this.special[i]);
        }
        return sb.toString();
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.rows; i++) {
            for (int i2 = 0; i2 < this.cols; i2++) {
                sb.append(cellText(idx(i, i2)));
            }
            if (i < this.rows - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public String cellText(int i) {
        switch (this.kind[i]) {
            case 0:
                return "  ";
            case 1:
                return "##";
            case 2:
            default:
                if (this.special[i] == 4) {
                    return "**";
                }
                char charAt = this.color[i] >= 0 ? COLOR_CODES.charAt(this.color[i]) : '?';
                switch (this.special[i]) {
                    case 1:
                        return charAt + "-";
                    case 2:
                        return charAt + "|";
                    case 3:
                        return charAt + "W";
                    default:
                        return charAt + (this.jelly[i] ? "j" : ".");
                }
            case 3:
                return "??";
        }
    }

    public Board copy() {
        Board board = new Board(this.rows, this.cols);
        System.arraycopy(this.kind, 0, board.kind, 0, this.kind.length);
        System.arraycopy(this.color, 0, board.color, 0, this.color.length);
        System.arraycopy(this.special, 0, board.special, 0, this.special.length);
        System.arraycopy(this.jelly, 0, board.jelly, 0, this.jelly.length);
        System.arraycopy(this.onTile, 0, board.onTile, 0, this.onTile.length);
        board.x0 = this.x0;
        board.y0 = this.y0;
        board.cell = this.cell;
        board.candyBrightness = this.candyBrightness;
        return board;
    }

    public static Board parse(String str) {
        String str2;
        String[] split = str.split("\n");
        int length = split.length;
        int length2 = split.length;
        int i = 0;
        int i2 = 0;
        while (i < length2) {
            int max = Math.max(i2, (split[i].length() + 1) / 2);
            i++;
            i2 = max;
        }
        Board board = new Board(length, i2);
        for (int i3 = 0; i3 < length; i3++) {
            String str3 = split[i3];
            for (int i4 = 0; i4 < i2; i4++) {
                if ((i4 * 2) + 2 <= str3.length()) {
                    str2 = str3.substring(i4 * 2, (i4 * 2) + 2);
                } else {
                    str2 = i4 * 2 < str3.length() ? str3.substring(i4 * 2) + " " : "  ";
                }
                int idx = board.idx(i3, i4);
                char charAt = str2.charAt(0);
                char charAt2 = str2.charAt(1);
                if (str2.equals("  ")) {
                    board.kind[idx] = 0;
                } else if (str2.equals("##")) {
                    board.kind[idx] = 1;
                } else if (str2.equals("??")) {
                    board.kind[idx] = 3;
                } else if (str2.equals("**")) {
                    board.kind[idx] = 2;
                    board.special[idx] = 4;
                } else {
                    int indexOf = COLOR_CODES.indexOf(charAt);
                    if (indexOf < 0) {
                        throw new IllegalArgumentException("bad cell '" + str2 + "' at " + i3 + "," + i4);
                    }
                    board.kind[idx] = 2;
                    board.color[idx] = indexOf;
                    if (charAt2 == '-') {
                        board.special[idx] = 1;
                    } else if (charAt2 == '|') {
                        board.special[idx] = 2;
                    } else if (charAt2 == 'W') {
                        board.special[idx] = 3;
                    } else if (charAt2 == 'j') {
                        board.jelly[idx] = true;
                    }
                }
            }
        }
        board.cell = 100.0f;
        board.x0 = 50.0f;
        board.y0 = 50.0f;
        return board;
    }
}
