package com.nickrocks.pixelflowbot.core.vlm;

import java.util.Locale;

/**
 * A labelled grid drawn over each screenshot before the on-device model sees it.
 *
 * Small phone-sized vision models are poor at giving exact pixel coordinates but good at reading a
 * label printed on the picture. So the screenshot is divided into square-ish cells named like a
 * spreadsheet — columns A, B, C… left to right, rows 1, 2, 3… top to bottom — and the model answers
 * "tap C7" or "drag B12 to F12". This class is the pure geometry (no Android), so it is unit-tested.
 */
public final class ScreenGrid {
    public final int cols, rows;
    public final float cellW, cellH;
    public final int imgW, imgH;

    public ScreenGrid(int imgW, int imgH, int cols) {
        this.imgW = imgW;
        this.imgH = imgH;
        this.cols = Math.max(2, Math.min(26, cols));
        this.cellW = imgW / (float) this.cols;
        this.rows = Math.max(2, Math.round(imgH / cellW));
        this.cellH = imgH / (float) rows;
    }

    /** "C7" for row index 6, column index 2 (both from 0). */
    public String label(int row, int col) {
        return String.valueOf((char) ('A' + col)) + (row + 1);
    }

    /** Centre of a cell in screenshot pixels, or null if the name isn't a cell of this grid. */
    public float[] center(String name) {
        int[] rc = parse(name);
        if (rc == null) return null;
        return new float[]{(rc[1] + 0.5f) * cellW, (rc[0] + 0.5f) * cellH};
    }

    /** Top-left of a cell, in screenshot pixels. */
    public float[] topLeft(String name) {
        int[] rc = parse(name);
        return rc == null ? null : new float[]{rc[1] * cellW, rc[0] * cellH};
    }

    /** Bottom-right of a cell, in screenshot pixels. */
    public float[] bottomRight(String name) {
        int[] rc = parse(name);
        return rc == null ? null : new float[]{(rc[1] + 1) * cellW, (rc[0] + 1) * cellH};
    }

    /** {row, col} from 0, or null. Accepts "c7", " C07 ", "C-7". */
    public int[] parse(String name) {
        if (name == null) return null;
        String s = name.trim().toUpperCase(Locale.US).replace("-", "").replace(" ", "");
        if (s.length() < 2) return null;
        char c = s.charAt(0);
        if (c < 'A' || c > 'Z') return null;
        int col = c - 'A';
        int row;
        try { row = Integer.parseInt(s.substring(1)) - 1; } catch (NumberFormatException e) { return null; }
        if (col >= cols || row < 0 || row >= rows) return null;
        return new int[]{row, col};
    }

    /** One line for the prompt. */
    public String describe() {
        return String.format(Locale.US, "The screenshot has a grid drawn on it: columns A–%c from left to right, rows 1–%d from top to bottom; "
                + "each cell's name (like C7) is printed in its top-left corner.", (char) ('A' + cols - 1), rows);
    }
}
