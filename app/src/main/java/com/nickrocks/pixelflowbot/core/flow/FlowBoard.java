package com.nickrocks.pixelflowbot.core.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a Flow Free board from a screenshot: the grid (thin lines on a dark background, any line
 * colour) and the coloured dots, paired by colour.
 *
 * Grid lines are found as thin ridges (a pixel clearly brighter than the pixels a few px either
 * side), counted per row and per column; the longest evenly spaced set of ridges is the lattice.
 * A dot is a cell whose centre and eight points around it (at ~0.22 cell) are all the same bright
 * colour — a pipe through a cell never covers all four sides. Pure Java (no Android), unit-tested.
 */
public final class FlowBoard {
    public int rows, cols;
    public float[] xs, ys;           // grid line positions (cols+1, rows+1), screen pixels
    public int[] dotColor;           // per cell: colour index or -1
    public final List<int[]> rgb = new ArrayList<>();       // colour index → mean RGB
    public int[][] pairs;            // colour → {cellA, cellB}
    public String problem;

    public float cellW() { return (xs[cols] - xs[0]) / cols; }
    public float cellH() { return (ys[rows] - ys[0]) / rows; }
    public float cx(int cell) { int c = cell % cols; return (xs[c] + xs[c + 1]) / 2f; }
    public float cy(int cell) { int r = cell / cols; return (ys[r] + ys[r + 1]) / 2f; }
    public float top() { return ys[0]; }
    public float bottom() { return ys[rows]; }

    public static FlowBoard read(int[] px, int w, int h) {
        FlowBoard b = new FlowBoard();
        b.parse(px, w, h);
        return b;
    }

    private static int lum(int p) { return (((p >> 16) & 255) * 3 + ((p >> 8) & 255) * 6 + (p & 255)) / 10; }

    private void parse(int[] px, int w, int h) {
        int d = Math.max(4, w / 180);   // ridge half-width test distance
        // Horizontal ridges per row (whole width), then the lattice rows.
        // (Beyond the screen edge counts as black: boards can touch the edges.)
        int[] rowCount = new int[h];
        for (int y = 0; y < h; y++) {
            int k = 0;
            for (int x = 0; x < w; x += 2) {
                int l = lum(px[y * w + x]);
                int up = y - d >= 0 ? lum(px[(y - d) * w + x]) : 0, dn = y + d < h ? lum(px[(y + d) * w + x]) : 0;
                if (l - up > 12 && l - dn > 12 && l < 200) k++;
            }
            rowCount[y] = k;
        }
        float[] hy = lattice(rowCount, (w / 2) * 0.35f);
        if (hy == null) { problem = "no grid found"; return; }
        int y0 = Math.round(hy[0]), y1 = Math.round(hy[hy.length - 1]);
        int[] colCount = new int[w];
        for (int x = 0; x < w; x++) {
            int k = 0;
            for (int y = y0; y <= y1; y += 2) {
                int l = lum(px[y * w + x]);
                int lf = x - d >= 0 ? lum(px[y * w + x - d]) : 0, rt = x + d < w ? lum(px[y * w + x + d]) : 0;
                if (l - lf > 12 && l - rt > 12 && l < 200) k++;
            }
            colCount[x] = k;
        }
        float[] vx = lattice(colCount, ((y1 - y0) / 2) * 0.35f);
        if (vx == null) { problem = "no grid columns found"; return; }
        xs = vx;
        ys = hy;
        cols = xs.length - 1;
        rows = ys.length - 1;
        if (cols < 2 || rows < 2 || cols > 20 || rows > 20) { problem = "odd grid " + cols + "×" + rows; return; }
        float cw = cellW(), ch = cellH();
        if (Math.abs(cw - ch) > Math.max(cw, ch) * 0.1f) { problem = String.format(Locale.US, "cells aren't square (%.0f×%.0f)", cw, ch); return; }

        // Dots.
        dotColor = new int[rows * cols];
        int[][] samples = new int[9][3];
        float rad = Math.min(cw, ch) * 0.22f;
        float[][] off = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0.7071f, 0.7071f}, {-0.7071f, 0.7071f}, {0.7071f, -0.7071f}, {-0.7071f, -0.7071f}};
        List<int[]> dotRgb = new ArrayList<>();
        List<Integer> dotCell = new ArrayList<>();
        for (int cell = 0; cell < rows * cols; cell++) {
            dotColor[cell] = -1;
            float cx = cx(cell), cy = cy(cell);
            int sr = 0, sg = 0, sb = 0;
            for (int i = 0; i < 9; i++) {
                int x = Math.round(cx + off[i][0] * rad), y = Math.round(cy + off[i][1] * rad);
                int p = avg(px, w, h, x, y);
                samples[i][0] = (p >> 16) & 255; samples[i][1] = (p >> 8) & 255; samples[i][2] = p & 255;
                sr += samples[i][0]; sg += samples[i][1]; sb += samples[i][2];
            }
            int[] m = {sr / 9, sg / 9, sb / 9};
            if (Math.max(m[0], Math.max(m[1], m[2])) < 100) continue;           // dark: empty or pipe glow
            boolean same = true;
            for (int[] s : samples) if (dist(s, m) > 60) { same = false; break; }
            if (!same) continue;
            dotRgb.add(m);
            dotCell.add(cell);
        }
        // Pair dots by colour.
        for (int i = 0; i < dotRgb.size(); i++) {
            int[] m = dotRgb.get(i);
            int found = -1;
            for (int k = 0; k < rgb.size(); k++) if (dist(rgb.get(k), m) < 50) { found = k; break; }
            if (found < 0) { found = rgb.size(); rgb.add(m); }
            dotColor[dotCell.get(i)] = found;
        }
        if (rgb.isEmpty()) { problem = "no dots found"; return; }
        pairs = new int[rgb.size()][2];
        int[] count = new int[rgb.size()];
        for (int cell = 0; cell < rows * cols; cell++) {
            int c = dotColor[cell];
            if (c < 0) continue;
            if (count[c] < 2) pairs[c][count[c]] = cell;
            count[c]++;
        }
        for (int c = 0; c < count.length; c++) {
            if (count[c] != 2) {
                int[] m = rgb.get(c);
                problem = String.format(Locale.US, "colour #%02x%02x%02x has %d dot%s, not 2", m[0], m[1], m[2], count[c], count[c] == 1 ? "" : "s");
                return;
            }
        }
    }

    /** 3×3 average around (x, y). */
    private static int avg(int[] px, int w, int h, int x, int y) {
        int r = 0, g = 0, b = 0, n = 0;
        for (int j = -1; j <= 1; j++)
            for (int i = -1; i <= 1; i++) {
                int xx = Math.max(0, Math.min(w - 1, x + i)), yy = Math.max(0, Math.min(h - 1, y + j));
                int p = px[yy * w + xx];
                r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
            }
        return 0xFF000000 | ((r / n) << 16) | ((g / n) << 8) | (b / n);
    }

    private static int dist(int[] a, int[] b) {
        return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
    }

    /**
     * Ridge lines from a per-position count: positions above {@code min} are grouped into lines;
     * the longest run of evenly spaced lines (spacing within 8%) is the lattice. Null if fewer than 3.
     */
    static float[] lattice(int[] count, float min) {
        List<Float> lines = new ArrayList<>();
        int i = 0;
        while (i < count.length) {
            if (count[i] < min) { i++; continue; }
            double sw = 0, sx = 0;
            while (i < count.length && count[i] >= min) { sw += count[i]; sx += count[i] * (double) i; i++; }
            lines.add((float) (sx / sw));
        }
        if (lines.size() < 3) return null;
        // Merge lines closer than 8 px (double-drawn borders).
        List<Float> merged = new ArrayList<>();
        for (float v : lines) {
            if (!merged.isEmpty() && v - merged.get(merged.size() - 1) < 8) merged.set(merged.size() - 1, (merged.get(merged.size() - 1) + v) / 2f);
            else merged.add(v);
        }
        int bestA = 0, bestB = -1;
        for (int a = 0; a + 1 < merged.size(); a++) {
            float sp = merged.get(a + 1) - merged.get(a);
            int b = a + 1;
            while (b + 1 < merged.size() && Math.abs((merged.get(b + 1) - merged.get(b)) - sp) < sp * 0.08f) b++;
            if (b - a > bestB - bestA) { bestA = a; bestB = b; }
        }
        if (bestB - bestA < 2) return null;
        List<Float> out = new ArrayList<>(merged.subList(bestA, bestB + 1));
        // A border can be partly hidden (a phone's edge-panel tab, a notch, a banner): accept a weaker
        // line exactly one spacing beyond either end.
        float sp = (out.get(out.size() - 1) - out.get(0)) / (out.size() - 1);
        for (int side = 0; side < 2; side++) {
            while (true) {
                float want = side == 0 ? out.get(0) - sp : out.get(out.size() - 1) + sp;
                int c = Math.round(want), tol = Math.max(3, Math.round(sp * 0.04f));
                double sw = 0, sx = 0;
                for (int x = Math.max(0, c - tol); x <= Math.min(count.length - 1, c + tol); x++) {
                    if (count[x] >= min * 0.25f) { sw += count[x]; sx += count[x] * (double) x; }
                }
                if (sw <= 0) break;
                float pos = (float) (sx / sw);
                if (side == 0) out.add(0, pos); else out.add(pos);
            }
        }
        float[] arr = new float[out.size()];
        for (int k = 0; k < arr.length; k++) arr[k] = out.get(k);
        return arr;
    }

    public String summary() {
        if (problem != null) return problem + (cols > 0 ? " (" + cols + "×" + rows + " grid)" : "");
        return String.format(Locale.US, "%d×%d grid, %d colours", cols, rows, rgb.size());
    }
}
