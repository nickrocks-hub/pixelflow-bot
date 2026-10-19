package com.nickrocks.pixelflowbot.nono;

import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Nonogram.com puzzle from a screen frame: the grid, every cell, and the clue numbers.
 * Plain Java (no Android), so it can be tested on a computer against saved frames.
 *
 * Layout facts it relies on (measured on the app, 1080x2520):
 *  - the grid has a black outer border and black lines every 5 cells; thin grey lines between cells
 *  - filled cells are navy (50,70,96); crosses are thin navy strokes
 *  - column clues sit above the grid (numbers stacked), row clues to its left (numbers in a line)
 *  - clue digits are dark indigo, or light grey once that line is satisfied
 *  - the fill/cross toggle is below the grid: a navy square when "fill" is on, a grey square when not
 */
public final class NonoVision {
    public static final int EMPTY = 0, FILLED = 1, CROSS = 2;

    /** Where the grid is and how big it is. Cell centres are per block, so thick lines don't add drift. */
    public static final class Grid {
        public int left, top, right, bottom;
        public int rows, cols;
        public float[] colX0, colX1, rowY0, rowY1;   // cell edges (inside the lines)
        public float cell;

        public float cx(int c) { return (colX0[c] + colX1[c]) / 2f; }
        public float cy(int r) { return (rowY0[r] + rowY1[r]) / 2f; }

        @Override public String toString() {
            return cols + "x" + rows + " @" + left + "," + top + " cell " + Math.round(cell) + "px";
        }
    }

    public static final class Reading {
        public Grid grid;
        public int[][] rowClues, colClues;
        public boolean[] rowGrey, colGrey;      // clue drawn grey = that line is already satisfied
        public int frozen;                       // lines whose clue is hidden under ice (clue = null)
        public int[] cells;                     // rows*cols, EMPTY/FILLED/CROSS
        public float worstDigit = 0f;           // worst digit match distance (lower is better)
        public String worstWhere = "";
        public float toggleX = -1, toggleY = -1; // centre of the fill (square) icon
        public int toggle = -1;                  // 1 = fill mode on, 0 = cross mode on, -1 = not found
        public int redCells;                     // cells showing the game's red "mistake" mark
        public int hearts = -1;                  // red hearts (lives) under the title, -1 if not seen
        public String splitAt;                   // where a number looked split into separate digits                     // cells showing the game's red "mistake" mark
        public String error;                     // non-null when the frame couldn't be read
        public long ms;
        public int frameW, frameH;
        public Frame frame;                      // the screenshot this was read from (for saving misreads)

        public int cell(int r, int c) { return cells[r * grid.cols + c]; }

        public String cluesText() {
            StringBuilder sb = new StringBuilder("cols:");
            for (int[] c : colClues) sb.append(' ').append(c == null ? "❄" : join(c, "."));
            sb.append("\nrows:");
            for (int[] r : rowClues) sb.append(' ').append(r == null ? "❄" : join(r, "."));
            return sb.toString();
        }

        public String boardText() {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < grid.rows; r++) {
                for (int c = 0; c < grid.cols; c++) {
                    int v = cell(r, c);
                    sb.append(v == FILLED ? '#' : v == CROSS ? 'x' : '.');
                }
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    static String join(int[] a, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(sep); sb.append(a[i]); }
        return sb.length() == 0 ? "0" : sb.toString();
    }

    // ---------------- pixel classes ----------------

    static int lum(int p) {
        return (((p >> 16) & 0xff) * 3 + ((p >> 8) & 0xff) * 6 + (p & 0xff)) / 10;
    }

    /** Pure black: the grid border and the 5-cell lines. Filled cells / digits are bluish, not black. */
    static boolean black(int p) {
        return ((p >> 16) & 0xff) < 60 && ((p >> 8) & 0xff) < 60 && (p & 0xff) < 62;
    }

    /** Clue ink: dark or grey, never reddish (hearts) . */
    static boolean ink(int p) {
        int r = (p >> 16) & 0xff, b = p & 0xff;
        return lum(p) < 205 && b >= r - 12;
    }

    /** Navy cell paint (filled cells, cross strokes, the fill icon). */
    static boolean navy(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return b < 150 && b > r + 15 && lum(p) < 120;
    }

    // ---------------- main entry ----------------

    public Reading read(Frame f) {
        long t0 = System.currentTimeMillis();
        Reading out = new Reading();
        out.frameW = f.w; out.frameH = f.h;
        out.frame = f;
        try {
            Grid g = findGrid(f);
            if (g == null) { out.error = lastNote; return out; }
            out.grid = g;
            out.cells = readCells(f, g);
            out.redCells = countRed(f, g);
            out.hearts = countHearts(f, g);
            readClues(f, g, out);
            findToggle(f, g, out);
            if (out.error == null) out.error = checkClues(out);
        } catch (Throwable t) {
            out.error = "read error: " + t;
        } finally {
            out.ms = System.currentTimeMillis() - t0;
        }
        return out;
    }

    /** Grid, cells and the mode switch only (no clues): for quick checks while filling. */
    public Reading readBoard(Frame f) {
        long t0 = System.currentTimeMillis();
        Reading out = new Reading();
        out.frameW = f.w; out.frameH = f.h;
        try {
            Grid g = findGrid(f);
            if (g == null) { out.error = lastNote; return out; }
            out.grid = g;
            out.cells = readCells(f, g);
            out.redCells = countRed(f, g);
            out.hearts = countHearts(f, g);
            findToggle(f, g, out);
        } catch (Throwable t) {
            out.error = "read error: " + t;
        } finally {
            out.ms = System.currentTimeMillis() - t0;
        }
        return out;
    }

    public String lastNote = "";
    public static boolean DEBUG = false;
    /** Real clue digits match a template within ~0.03; tutorial art, hands and popups score 0.15+. */
    static final float MAX_DIGIT_DIST = 0.08f;
    /** When set, every glyph's features are appended here in reading order (for building templates). */
    public List<float[]> glyphLog;

    // ---------------- grid ----------------

    /** A dark straight line: [start, end] across, and its extent [from, to] along. */
    static final class Line {
        int a0, a1, from, to;
        int mid() { return (a0 + a1) / 2; }
        int len() { return to - from; }
    }

    Grid findGrid(Frame f) {
        int w = f.w, h = f.h;
        int[] px = f.px;
        // Horizontal black lines.
        List<Line> hl = new ArrayList<>();
        Line cur = null;
        int minH = (int) (w * 0.25f);
        for (int y = (int) (h * 0.05f); y < (int) (h * 0.97f); y++) {
            int best = 0, bestFrom = 0, run = 0, runFrom = 0;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (black(px[row + x])) {
                    if (run == 0) runFrom = x;
                    run++;
                    if (run > best) { best = run; bestFrom = runFrom; }
                } else run = 0;
            }
            if (best >= minH) {
                if (cur != null && cur.a1 == y - 1 && Math.abs(cur.from - bestFrom) < w / 50) {
                    cur.a1 = y;
                    cur.from = Math.min(cur.from, bestFrom);
                    cur.to = Math.max(cur.to, bestFrom + best);
                } else {
                    cur = new Line();
                    cur.a0 = cur.a1 = y; cur.from = bestFrom; cur.to = bestFrom + best;
                    hl.add(cur);
                }
            }
        }
        if (hl.size() < 2) { lastNote = "no grid (found " + hl.size() + " long black lines)"; return null; }
        // Keep the lines that share the longest line's span.
        Line longest = hl.get(0);
        for (Line l : hl) if (l.len() > longest.len()) longest = l;
        List<Line> hs = new ArrayList<>();
        for (Line l : hl) {
            if (Math.abs(l.from - longest.from) < w / 40 && l.len() >= longest.len() * 0.8f && l.a1 - l.a0 < w / 60) hs.add(l);
        }
        if (hs.size() < 2) { lastNote = "no grid (only one full-width line)"; return null; }
        int gx0 = longest.from, gx1 = longest.to;
        int gy0 = hs.get(0).a0, gy1 = hs.get(hs.size() - 1).a1;

        // Vertical black lines inside that box.
        List<Line> vs = new ArrayList<>();
        cur = null;
        int need = (int) ((gy1 - gy0) * 0.6f);
        for (int x = Math.max(0, gx0 - 2); x <= Math.min(w - 1, gx1 + 2); x++) {
            int cnt = 0;
            for (int y = gy0; y <= gy1; y++) if (black(px[y * w + x])) cnt++;
            if (cnt >= need) {
                if (cur != null && cur.a1 == x - 1) cur.a1 = x;
                else { cur = new Line(); cur.a0 = cur.a1 = x; cur.from = gy0; cur.to = gy1; vs.add(cur); }
            }
        }
        // Some screens (e.g. Challenge mode) draw the left or right border grey with a shadow
        // instead of black. The black horizontal borders still span the whole board, so if they
        // reach well past the outermost black vertical line, the board edge is where they end.
        int edgeGap = Math.max(6, w / 60);
        if (vs.isEmpty() || vs.get(0).a0 - gx0 > edgeGap) {
            Line l = new Line();
            l.a0 = l.a1 = edgeLine(f, gx0, gy0, gy1);
            l.from = gy0; l.to = gy1;
            vs.add(0, l);
        }
        if (vs.get(vs.size() - 1).a1 < gx1 - 1 - edgeGap) {
            Line l = new Line();
            l.a0 = l.a1 = edgeLine(f, gx1 - 1, gy0, gy1);
            l.from = gy0; l.to = gy1;
            vs.add(l);
        }
        if (vs.size() < 2) { lastNote = "no grid (no vertical border)"; return null; }

        // Same for the top or bottom border (Challenge mode can draw it grey with a shadow): if the
        // black vertical lines run well past the outermost black horizontal line, the board edge is
        // where they end.
        int up = gy0, down = gy1;
        for (Line v : vs) {
            if (v.a1 - v.a0 > w / 60) continue;
            int y = gy0;
            while (y - 1 > h * 0.05f && blackAcross(px, w, v.a0, v.a1, y - 1)) y--;
            up = Math.min(up, y);
            y = gy1;
            while (y + 1 < h * 0.97f && blackAcross(px, w, v.a0, v.a1, y + 1)) y++;
            down = Math.max(down, y);
        }
        if (gy0 - up > edgeGap * 2) {
            Line l = new Line();
            l.a0 = l.a1 = edgeRow(f, up, gx0, gx1);
            l.from = gx0; l.to = gx1;
            hs.add(0, l);
            gy0 = l.a0;
        }
        if (down - gy1 > edgeGap * 2) {
            Line l = new Line();
            l.a0 = l.a1 = edgeRow(f, down, gx0, gx1);
            l.from = gx0; l.to = gx1;
            hs.add(l);
            gy1 = l.a1;
        }

        Grid g = new Grid();
        g.left = vs.get(0).a0; g.right = vs.get(vs.size() - 1).a1;
        g.top = gy0; g.bottom = gy1;
        if (g.right - g.left < w * 0.3f || g.bottom - g.top < w * 0.3f) { lastNote = "grid too small"; return null; }

        float[][] cx = splitBlocks(f, vs, true, gy0, gy1);
        float[][] cy = splitBlocks(f, hs, false, g.left, g.right);
        if (cx == null || cy == null) return null;
        g.colX0 = cx[0]; g.colX1 = cx[1];
        g.rowY0 = cy[0]; g.rowY1 = cy[1];
        g.cols = g.colX0.length; g.rows = g.rowY0.length;
        g.cell = ((g.colX1[0] - g.colX0[0]) + (g.rowY1[0] - g.rowY0[0])) / 2f;
        if (g.cols < 2 || g.rows < 2 || g.cols > 40 || g.rows > 40) { lastNote = "odd grid size " + g.cols + "x" + g.rows; return null; }
        if (g.cell < 12) { lastNote = "cells too small (" + g.cell + "px)"; return null; }
        lastNote = "grid " + g;
        return g;
    }

    /** Any black pixel in [x0, x1] on row y. */
    private static boolean blackAcross(int[] px, int w, int x0, int x1, int y) {
        for (int x = x0; x <= x1; x++) if (black(px[y * w + x])) return true;
        return false;
    }

    /** The darkest row within a few pixels of y over [x0, x1] (a grey board edge). */
    private static int edgeRow(Frame f, int y, int x0, int x1) {
        int best = y;
        long bestSum = Long.MAX_VALUE;
        for (int yy = Math.max(0, y - 4); yy <= Math.min(f.h - 1, y + 4); yy++) {
            long sum = 0;
            for (int x = x0; x <= x1; x += 2) sum += lum(f.px[yy * f.w + x]);
            if (sum < bestSum) { bestSum = sum; best = yy; }
        }
        return best;
    }

    /** The darkest column within a few pixels of x over [y0, y1] (a grey board edge). */
    private static int edgeLine(Frame f, int x, int y0, int y1) {
        int best = x;
        long bestSum = Long.MAX_VALUE;
        for (int xx = Math.max(0, x - 4); xx <= Math.min(f.w - 1, x + 4); xx++) {
            long sum = 0;
            for (int y = y0; y <= y1; y += 2) sum += lum(f.px[y * f.w + xx]);
            if (sum < bestSum) { bestSum = sum; best = xx; }
        }
        return best;
    }

    /**
     * Splits each block between two thick lines into cells. Usually 5 per block; the count is
     * checked against the thin lines so other sizes work too.
     */
    float[][] splitBlocks(Frame f, List<Line> lines, boolean vertical, int across0, int across1) {
        int blocks = lines.size() - 1;
        int n = bestCellsPerBlock(f, lines, vertical, across0, across1);
        if (n <= 0) { lastNote = "can't count the cells (" + (vertical ? "columns" : "rows") + ")"; return null; }
        float[] e0 = new float[blocks * n], e1 = new float[blocks * n];
        for (int b = 0; b < blocks; b++) {
            float s = (lines.get(b).a0 + lines.get(b).a1) / 2f, e = (lines.get(b + 1).a0 + lines.get(b + 1).a1) / 2f;
            float step = (e - s) / n;
            for (int i = 0; i < n; i++) {
                e0[b * n + i] = s + i * step;
                e1[b * n + i] = s + (i + 1) * step;
            }
        }
        return new float[][]{e0, e1};
    }

    /**
     * How many cells per block: the largest n for which every predicted cell border shows a thin
     * line (checked on scanlines where both neighbouring cells are white).
     */
    int bestCellsPerBlock(Frame f, List<Line> lines, boolean vertical, int across0, int across1) {
        int blocks = lines.size() - 1;
        float avgBlock = 0;
        for (int b = 0; b < blocks; b++) avgBlock += lines.get(b + 1).a0 - lines.get(b).a1 - 1;
        avgBlock /= blocks;
        int pick = -1;
        float pickScore = 0;
        for (int n = 2; n <= 12; n++) {
            if (avgBlock / n < 10) break;
            float sc = borderScore(f, lines, vertical, across0, across1, n);
            if (DEBUG) System.out.println("  n=" + n + (vertical ? " cols" : " rows") + " score " + sc);
            if (sc >= 0.7f) { pick = n; pickScore = sc; }
        }
        if (pick < 0) return avgBlock / 5 >= 10 ? 5 : -1;   // board too painted to see thin lines
        return pick;
    }

    /** Worst (over borders) fraction of clean scanlines where the predicted border is darker than both cells. */
    float borderScore(Frame f, List<Line> lines, boolean vertical, int across0, int across1, int n) {
        int span = across1 - across0;
        float worst = 1f;
        int borders = 0;
        for (int b = 0; b + 1 < lines.size(); b++) {
            float s = (lines.get(b).a0 + lines.get(b).a1) / 2f, e = (lines.get(b + 1).a0 + lines.get(b + 1).a1) / 2f;
            float step = (e - s) / n;
            for (int i = 1; i < n; i++) {
                float border = s + i * step;
                int clean = 0, dips = 0;
                for (int k = 1; k <= 47; k++) {
                    int a = across0 + span * k / 48;
                    int l1 = lumAt(f, vertical, border - step * 0.3f, a), l2 = lumAt(f, vertical, border + step * 0.3f, a);
                    if (l1 < 235 || l2 < 235) continue;
                    clean++;
                    int lb = 255;
                    for (int d = -2; d <= 2; d++) lb = Math.min(lb, lumAt(f, vertical, border + d, a));
                    if (lb < Math.min(l1, l2) - 12) dips++;
                }
                if (clean < 3) continue;
                borders++;
                worst = Math.min(worst, dips / (float) clean);
            }
        }
        return borders == 0 ? 0 : worst;
    }

    static int lumAt(Frame f, boolean vertical, float along, int across) {
        int x = vertical ? Math.round(along) : across;
        int y = vertical ? across : Math.round(along);
        if (x < 0 || y < 0 || x >= f.w || y >= f.h) return 255;
        return lum(f.px[y * f.w + x]);
    }

    // ---------------- cells ----------------

    int[] readCells(Frame f, Grid g) {
        int[] cells = new int[g.rows * g.cols];
        for (int r = 0; r < g.rows; r++) {
            for (int c = 0; c < g.cols; c++) {
                float x0 = g.colX0[c], x1 = g.colX1[c], y0 = g.rowY0[r], y1 = g.rowY1[r];
                float mx = (x1 - x0) * 0.22f, my = (y1 - y0) * 0.22f;
                int tot = 0, dark = 0;
                for (int y = Math.round(y0 + my); y < Math.round(y1 - my); y++) {
                    for (int x = Math.round(x0 + mx); x < Math.round(x1 - mx); x++) {
                        tot++;
                        int p = f.px[y * f.w + x];
                        if (lum(p) < 150) dark++;
                    }
                }
                float frac = tot == 0 ? 0 : dark / (float) tot;
                cells[r * g.cols + c] = frac > 0.7f ? FILLED : frac > 0.06f ? CROSS : EMPTY;
            }
        }
        return cells;
    }

    /** Red hearts (255,39,47) in the band between the title and the grid; lost hearts turn grey. */
    static int countHearts(Frame f, Grid g) {
        int y0 = (int) (f.h * 0.07f), y1 = Math.min((int) (f.h * 0.16f), g.top - 10);
        // Count on the row with the most red: the hearts' widest line (the top rows split each
        // heart into two lobes).
        int bestRow = -1, bestRed = 0;
        for (int y = Math.max(0, y0); y < y1; y += 2) {
            int red = 0;
            for (int x = 0; x < f.w; x += 2) if (heartRed(f.px[y * f.w + x])) red++;
            if (red > bestRed) { bestRed = red; bestRow = y; }
        }
        if (bestRow < 0 || bestRed < 10) return 0;
        int count = 0, last = -100, start = -1;
        for (int x = 0; x <= f.w; x++) {
            boolean red = x < f.w && heartRed(f.px[bestRow * f.w + x]);
            if (red) {
                if (x - last > 6) { if (start >= 0 && last - start > f.w / 60) count++; start = x; }
                last = x;
            }
        }
        if (start >= 0 && last - start > f.w / 60) count++;
        return count;
    }

    static boolean heartRed(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return r > 200 && g < 100 && b < 110;
    }

    /** Cells containing clearly red ink (the game's mistake marker). */
    int countRed(Frame f, Grid g) {
        int n = 0;
        for (int r = 0; r < g.rows; r++) {
            for (int c = 0; c < g.cols; c++) {
                int red = 0;
                for (int y = Math.round(g.rowY0[r] + 3); y < Math.round(g.rowY1[r] - 3); y += 2) {
                    for (int x = Math.round(g.colX0[c] + 3); x < Math.round(g.colX1[c] - 3); x += 2) {
                        int p = f.px[y * f.w + x];
                        int rr = (p >> 16) & 0xff, gg = (p >> 8) & 0xff, bb = p & 0xff;
                        if (rr > 170 && gg < 120 && bb < 130 && rr - gg > 80) red++;
                    }
                }
                if (red >= 6) n++;
            }
        }
        return n;
    }

    // ---------------- clues ----------------

    /** A run of ink: [a0,a1] along, and for glyphs the vertical extent. */
    static final class Glyph {
        int x0, x1, y0, y1;
        boolean grey;
    }

    void readClues(Frame f, Grid g, Reading out) {
        int w = f.w;
        out.colClues = new int[g.cols][];
        out.colGrey = new boolean[g.cols];
        out.rowClues = new int[g.rows][];
        out.rowGrey = new boolean[g.rows];
        // Column clue area: from the grid top upward until a long gap with no ink at all.
        int maxGap = (int) Math.max(g.cell * 1.3f, w * 0.03f);
        int top = g.top - 3, gap = 0;
        for (int y = g.top - 3; y > f.h * 0.06f; y--) {
            boolean any = false;
            for (int c = 0; c < g.cols && !any; c++) {
                int x0 = Math.round(g.colX0[c] + g.cell * 0.1f), x1 = Math.round(g.colX1[c] - g.cell * 0.1f);
                for (int x = x0; x < x1; x++) if (ink(f.px[y * w + x])) { any = true; break; }
            }
            if (any) { top = y; gap = 0; } else if (++gap > maxGap) break;
        }
        // Right edge of the row-clue area. Some screens (Challenge mode) put a grey shadow strip
        // along the board's left edge; it runs the full board height, which digits never do, so skip it.
        int rowRight = g.left - 3;
        int maxSkip = Math.round(g.cell * 0.6f);
        while (rowRight > 0 && g.left - 3 - rowRight < maxSkip) {
            int cnt = 0, tot = 0;
            for (int y = g.top; y <= g.bottom; y += 2) { tot++; if (ink(f.px[y * w + rowRight])) cnt++; }
            if (cnt < tot * 0.9f) break;
            rowRight--;
        }
        rowRight -= 2;
        int left = rowRight;
        gap = 0;
        for (int x = rowRight; x >= 0; x--) {
            boolean any = false;
            for (int r = 0; r < g.rows && !any; r++) {
                int y0 = Math.round(g.rowY0[r] + g.cell * 0.1f), y1 = Math.round(g.rowY1[r] - g.cell * 0.1f);
                for (int y = y0; y < y1; y++) if (ink(f.px[y * w + x])) { any = true; break; }
            }
            if (any) { left = x; gap = 0; } else if (++gap > maxGap) break;
        }
        for (int c = 0; c < g.cols; c++) {
            int x0 = Math.round(g.colX0[c] + g.cell * 0.08f), x1 = Math.round(g.colX1[c] - g.cell * 0.08f);
            if (frozenTab(f, x0, x1, Math.max(top, g.top - (int) (g.cell * 1.6f)), g.top - 4)) {
                out.colClues[c] = null;   // frozen: clue hidden
                out.frozen++;
                continue;
            }
            List<Integer> nums = new ArrayList<>();
            boolean[] grey = {false};
            for (int[] line : inkRuns(f, x0, x1, top, g.top - 3, false)) {
                nums.addAll(readNumbers(f, x0, x1, line[0], line[1], out, "col " + (c + 1), grey));
            }
            out.colClues[c] = toArray(nums);
            out.colGrey[c] = grey[0];
        }
        for (int r = 0; r < g.rows; r++) {
            int y0 = Math.round(g.rowY0[r] + g.cell * 0.08f), y1 = Math.round(g.rowY1[r] - g.cell * 0.08f);
            if (frozenTab(f, Math.max(left, rowRight - (int) (g.cell * 1.6f)), rowRight - 1, y0, y1)) {
                out.rowClues[r] = null;
                out.frozen++;
                continue;
            }
            List<Integer> nums = new ArrayList<>();
            boolean[] grey = {false};
            List<int[]> lines = inkRuns(f, left, rowRight, y0, y1, false);
            if (!lines.isEmpty()) {
                int ly0 = lines.get(0)[0], ly1 = lines.get(lines.size() - 1)[1];
                nums.addAll(readNumbers(f, left, rowRight, ly0, ly1, out, "row " + (r + 1), grey));
            }
            out.rowClues[r] = toArray(nums);
            out.rowGrey[r] = grey[0];
        }
    }

    static int[] toArray(List<Integer> l) {
        int n = 0;
        for (int v : l) if (v > 0) n++;
        int[] a = new int[n];
        int i = 0;
        for (int v : l) if (v > 0) a[i++] = v;
        return a;
    }

    /**
     * Rows (byColumn=false) of [y0,y1] inside the box that contain ink, joined across 1px gaps and
     * ignoring specks.
     */
    static List<int[]> inkRuns(Frame f, int x0, int x1, int y0, int y1, boolean unused) {
        List<int[]> runs = new ArrayList<>();
        int start = -1, last = -1;
        for (int y = Math.max(0, y0); y <= Math.min(f.h - 1, y1); y++) {
            int cnt = 0;
            for (int x = Math.max(0, x0); x < Math.min(f.w, x1); x++) if (ink(f.px[y * f.w + x])) cnt++;
            if (cnt > 0) {
                if (start < 0 || y - last > 2) {
                    if (start >= 0) runs.add(new int[]{start, last});
                    start = y;
                }
                last = y;
            }
        }
        if (start >= 0) runs.add(new int[]{start, last});
        List<int[]> keep = new ArrayList<>();
        int tallest = 0;
        for (int[] r : runs) tallest = Math.max(tallest, r[1] - r[0] + 1);
        for (int[] r : runs) if (r[1] - r[0] + 1 >= Math.max(4, tallest * 0.5f)) keep.add(r);
        return keep;
    }

    /** Reads one line of text (y0..y1) inside x0..x1 as numbers separated by spaces. */
    List<Integer> readNumbers(Frame f, int x0, int x1, int y0, int y1, Reading out, String where, boolean[] grey) {
        List<Glyph> glyphs = new ArrayList<>();
        int h = y1 - y0 + 1;
        Glyph cur = null;
        int lastX = -10;
        for (int x = Math.max(0, x0); x < Math.min(f.w, x1); x++) {
            int top = -1, bot = -1;
            for (int y = y0; y <= y1; y++) {
                if (ink(f.px[y * f.w + x])) { if (top < 0) top = y; bot = y; }
            }
            if (top < 0) continue;
            if (cur == null || x - lastX > 1) {
                cur = new Glyph();
                cur.x0 = x; cur.y0 = top; cur.y1 = bot;
                glyphs.add(cur);
            }
            cur.x1 = x;
            cur.y0 = Math.min(cur.y0, top);
            cur.y1 = Math.max(cur.y1, bot);
            lastX = x;
        }
        // Drop specks (anti-aliasing from tab borders etc.).
        List<Glyph> gs = new ArrayList<>();
        for (Glyph g : glyphs) {
            if (g.y1 - g.y0 + 1 < h * 0.6f) continue;
            // Two digits drawn touching (e.g. "10" at small sizes) form one wide blob: no single digit
            // in this font is wider than ~0.8 x its height, so split it at the thinnest column.
            if (g.x1 - g.x0 + 1 > h * 0.95f) {
                Glyph[] parts = split(f, g);
                if (parts != null) { gs.add(parts[0]); gs.add(parts[1]); continue; }
            }
            gs.add(g);
        }
        List<Integer> nums = new ArrayList<>();
        int value = -1;
        Glyph prev = null;
        for (Glyph g : gs) {
            boolean[] gr = {false};
            float[] feat = Digits.features(f, g.x0, g.y0, g.x1, g.y1, gr);
            if (glyphLog != null) glyphLog.add(feat);
            Digits.Match m = Digits.classify(feat);
            m.grey = gr[0];
            if (m.dist > out.worstDigit) { out.worstDigit = m.dist; out.worstWhere = where + " '" + m.digit + "'"; }
            if (m.grey) grey[0] = true;
            // Inter: digits of one number sit 0.18-0.21 x height apart; separate numbers ~0.53.
            boolean sameNumber = prev != null && (g.x0 - prev.x1 - 1) < h * 0.36f;
            if (sameNumber && value >= 0) value = value * 10 + m.digit;
            else {
                if (value >= 0) nums.add(value);
                value = m.digit;
            }
            prev = g;
        }
        if (value >= 0) nums.add(value);
        // A clue can't contain a lone 0 next to other numbers: that means digits were split.
        if (nums.size() > 1 && nums.contains(0) && out.splitAt == null) out.splitAt = where;
        return nums;
    }

    /**
     * Splits a two-digit blob: tries every cut in the middle 20-80% and keeps the one where both
     * halves look most like real digits (the cut with the least ink can fall inside a "0").
     */
    static Glyph[] split(Frame f, Glyph g) {
        int w = g.x1 - g.x0 + 1;
        Glyph[] best = null;
        float bestScore = Float.MAX_VALUE;
        for (int x = g.x0 + (int) (w * 0.2f); x <= g.x0 + (int) (w * 0.8f); x++) {
            Glyph[] parts = cutAt(f, g, x);
            if (parts == null) continue;
            float score = 0;
            for (Glyph p : parts) score += Digits.classify(Digits.features(f, p.x0, p.y0, p.x1, p.y1, null)).dist;
            if (score < bestScore) { bestScore = score; best = parts; }
        }
        return best;
    }

    static Glyph[] cutAt(Frame f, Glyph g, int cut) {
        Glyph a = new Glyph(), b = new Glyph();
        a.x0 = g.x0; a.x1 = cut - 1; b.x0 = cut; b.x1 = g.x1;
        for (Glyph p : new Glyph[]{a, b}) {
            while (p.x1 > p.x0 && colEmpty(f, p.x1, g.y0, g.y1)) p.x1--;
            while (p.x0 < p.x1 && colEmpty(f, p.x0, g.y0, g.y1)) p.x0++;
            p.y0 = Integer.MAX_VALUE; p.y1 = -1;
            for (int x = p.x0; x <= p.x1; x++) {
                for (int y = g.y0; y <= g.y1; y++) {
                    if (ink(f.px[y * f.w + x])) { p.y0 = Math.min(p.y0, y); p.y1 = Math.max(p.y1, y); }
                }
            }
            // Each half must be a full-height digit.
            if (p.y1 < 0 || p.x1 - p.x0 < 2 || (p.y1 - p.y0 + 1) < (g.y1 - g.y0 + 1) * 0.85f) return null;
        }
        return new Glyph[]{a, b};
    }

    static boolean colEmpty(Frame f, int x, int y0, int y1) {
        for (int y = y0; y <= y1; y++) if (ink(f.px[y * f.w + x])) return false;
        return true;
    }

    // ---------------- toggle ----------------

    /** Finds the square fill icon under the grid and whether it is the active (navy) one. */
    void findToggle(Frame f, Grid g, Reading out) {
        int w = f.w;
        int y0 = g.bottom + 8, y1 = (int) (f.h * 0.97f);
        int step = 2;
        int bestArea = 0;
        for (int pass = 0; pass < 2; pass++) {
            // pass 0: navy square (fill mode on); pass 1: grey-blue square (fill mode off)
            int minX = w, maxX = -1, minY = f.h, maxY = -1, n = 0;
            // collect candidate pixels in rows; pick the densest square cluster with a simple scan
            for (int y = y0; y < y1; y += step) {
                for (int x = 0; x < w; x += step) {
                    int p = f.px[y * w + x];
                    if (pass == 0 ? isIconNavy(p) : isIconGrey(p)) {
                        // grow a box from this seed
                        int[] box = growBox(f, x, y, pass);
                        if (box != null) {
                            int bw = box[2] - box[0] + 1, bh = box[3] - box[1] + 1;
                            if (bw * bh > bestArea) {
                                bestArea = bw * bh;
                                out.toggle = pass == 0 ? 1 : 0;
                                out.toggleX = (box[0] + box[2]) / 2f;
                                out.toggleY = (box[1] + box[3]) / 2f;
                            }
                            x = box[2] + 1;
                        }
                    }
                }
            }
            if (out.toggle >= 0) return;
        }
    }

    static boolean isIconNavy(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return r >= 35 && r <= 75 && g >= 55 && g <= 90 && b >= 80 && b <= 115;
    }

    static boolean isIconGrey(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return r >= 110 && r <= 145 && g >= 125 && g <= 155 && b >= 145 && b <= 175 && b - r >= 20;
    }

    /** A solid square of the icon colour around (x,y), or null. */
    int[] growBox(Frame f, int x, int y, int pass) {
        int w = f.w;
        int l = x, r = x, t = y, b = y;
        while (l > 0 && match(f.px[y * w + l - 1], pass)) l--;
        while (r < w - 1 && match(f.px[y * w + r + 1], pass)) r++;
        int cx = (l + r) / 2;
        while (t > 0 && match(f.px[(t - 1) * w + cx], pass)) t--;
        while (b < f.h - 1 && match(f.px[(b + 1) * w + cx], pass)) b++;
        int bw = r - l + 1, bh = b - t + 1;
        if (bw < w * 0.025f || bw > w * 0.12f) return null;
        if (bh < bw * 0.8f || bh > bw * 1.25f) return null;
        int in = 0, tot = 0;
        for (int yy = t; yy <= b; yy += 2) for (int xx = l; xx <= r; xx += 2) { tot++; if (match(f.px[yy * w + xx], pass)) in++; }
        if (in < tot * 0.85f) return null;
        return new int[]{l, t, r, b};
    }

    static boolean match(int p, int pass) { return pass == 0 ? isIconNavy(p) : isIconGrey(p); }

    /**
     * Frozen clue tab (ice-blue (219-231, 247-249, 251) with a snowflake, number hidden), as
     * opposed to the normal lavender tab (234,238,248) or a white selected one.
     */
    static boolean frozenTab(Frame f, int x0, int x1, int y0, int y1) {
        int ice = 0, n = 0;
        for (int y = Math.max(0, y0); y < Math.min(f.h, y1); y += 2) {
            for (int x = Math.max(0, x0); x < Math.min(f.w, x1); x += 2) {
                int p = f.px[y * f.w + x];
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                n++;
                if (b >= 240 && g >= 235 && g - r >= 12 && r >= 190) ice++;
            }
        }
        return n > 0 && ice > n * 0.3f;
    }

    // ---------------- sanity ----------------

    static String checkClues(Reading out) {
        Grid g = out.grid;
        if (out.splitAt != null) return "a two-digit clue looked split at " + out.splitAt + " — misread";
        if (out.worstDigit > MAX_DIGIT_DIST) return "unclear clue digit at " + out.worstWhere + " (something covering the clues?)";
        long rs = 0, cs = 0;
        for (int r = 0; r < g.rows; r++) {
            if (out.rowClues[r] == null) continue;
            int need = 0;
            for (int v : out.rowClues[r]) need += v;
            need += Math.max(0, out.rowClues[r].length - 1);
            if (need > g.cols) return "row " + (r + 1) + " clue " + join(out.rowClues[r], " ") + " doesn't fit — misread?";
            for (int v : out.rowClues[r]) rs += v;
        }
        for (int c = 0; c < g.cols; c++) {
            if (out.colClues[c] == null) continue;
            int need = 0;
            for (int v : out.colClues[c]) need += v;
            need += Math.max(0, out.colClues[c].length - 1);
            if (need > g.rows) return "column " + (c + 1) + " clue " + join(out.colClues[c], " ") + " doesn't fit — misread?";
            for (int v : out.colClues[c]) cs += v;
        }
        if (out.frozen == 0 && rs != cs) return "row clues add to " + rs + " but column clues add to " + cs + " — a number was misread";
        if (rs == 0) return "no clue numbers found";
        return null;
    }
}
