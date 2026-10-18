package com.nickrocks.pixelflowbot.game.nonogram;

import java.util.ArrayList;
import java.util.List;

/**
 * Nonogram clue rules.
 *
 * A clue lists the lengths of the consecutive filled groups in a line, in order (left→right for
 * rows, top→bottom for columns). Groups are separated by at least one empty cell and may touch
 * the edges. A line with no filled cells has an EMPTY clue list (shown as "0").
 * Example: "3 1" = three filled in a row, at least one empty, then one filled.
 */
public final class Clues {
    private Clues() { }

    /** The clue a line of filled/not-filled cells produces. */
    public static int[] of(boolean[] line) {
        List<Integer> o = new ArrayList<>();
        int run = 0;
        for (boolean v : line) {
            if (v) run++;
            else if (run > 0) { o.add(run); run = 0; }
        }
        if (run > 0) o.add(run);
        int[] a = new int[o.size()];
        for (int i = 0; i < a.length; i++) a[i] = o.get(i);
        return a;
    }

    /** True when the filled cells of {@code line} produce exactly {@code clue}. */
    public static boolean matches(int[] clue, boolean[] line) {
        return java.util.Arrays.equals(clue, of(line));
    }

    /** Row clues of a picture stored row by row. */
    public static int[][] rows(boolean[] img, int rows, int cols) {
        int[][] out = new int[rows][];
        boolean[] line = new boolean[cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) line[c] = img[r * cols + c];
            out[r] = of(line);
        }
        return out;
    }

    public static int[][] cols(boolean[] img, int rows, int cols) {
        int[][] out = new int[cols][];
        boolean[] line = new boolean[rows];
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) line[r] = img[r * cols + c];
            out[c] = of(line);
        }
        return out;
    }

    public static int sum(int[] clue) {
        int s = 0;
        for (int v : clue) s += v;
        return s;
    }

    /** Minimum cells the clue needs: blocks plus one gap between each pair. */
    public static int minLength(int[] clue) {
        return clue.length == 0 ? 0 : sum(clue) + clue.length - 1;
    }

    /** "3 1", or "0" for an empty clue list. */
    public static String text(int[] clue) {
        if (clue.length == 0) return "0";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clue.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(clue[i]);
        }
        return sb.toString();
    }

    /** Parses "3 1" / "0" / "" back into a clue. */
    public static int[] parse(String s) {
        String t = s.trim();
        if (t.isEmpty() || t.equals("0")) return new int[0];
        String[] parts = t.split("[\\s,]+");
        int[] a = new int[parts.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = Integer.parseInt(parts[i]);
            if (a[i] <= 0) throw new IllegalArgumentException("clue groups must be positive: " + s);
        }
        return a;
    }
}
