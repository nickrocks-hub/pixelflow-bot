package com.aigame.minesweeper.core;

/**
 * Reads a classic-style Minesweeper screen (grey raised tiles, coloured numbers, red 7-segment
 * mine counter, smiley reset button) from a screen frame. Pure Java, no Android, so the same code
 * runs in the desktop tests.
 *
 * Pixels are 0xRRGGBB, row-major, any resolution (the tests use full-size screenshots, the phone a
 * 540-px-wide capture).
 */
public final class MineVision {
    private MineVision() {}

    // cell values
    public static final int UNKNOWN = -1;    // raised, not opened
    public static final int FLAG = -2;       // raised with a flag on it
    public static final int UNREADABLE = -3; // something on top of it, or a picture we don't know
    public static final int MINE = 9;        // an uncovered mine (game lost)
    public static final int BOOM = 10;       // the mine that was hit (red background)

    /** Where the grid is on the screen. */
    public static final class Geometry {
        public final float x0, y0, pitchX, pitchY;
        public final int cols, rows;

        public Geometry(float x0, float y0, float pitchX, float pitchY, int cols, int rows) {
            this.x0 = x0; this.y0 = y0; this.pitchX = pitchX; this.pitchY = pitchY;
            this.cols = cols; this.rows = rows;
        }

        public float cx(int c) { return x0 + (c + 0.5f) * pitchX; }
        public float cy(int r) { return y0 + (r + 0.5f) * pitchY; }
        public float left() { return x0; }
        public float top() { return y0; }
        public float right() { return x0 + cols * pitchX; }
        public float bottom() { return y0 + rows * pitchY; }

        @Override public String toString() {
            return cols + "x" + rows + " at (" + Math.round(x0) + "," + Math.round(y0) + ") cell "
                    + Math.round(pitchX) + "x" + Math.round(pitchY);
        }
    }

    /** One reading of the screen. */
    public static final class Reading {
        public Geometry g;
        public int[] cell;           // rows*cols, row-major
        public int mines = -1;       // the red counter (mines minus flags), -1 if not read
        public float smileX = -1, smileY = -1;
        public int unknown, flags, opened, unreadable, minesShown;

        public int at(int c, int r) { return cell[r * g.cols + c]; }

        public boolean sameCells(Reading o) {
            return o != null && o.g == g && java.util.Arrays.equals(cell, o.cell);
        }

        public String grid() {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < g.rows; r++) {
                for (int c = 0; c < g.cols; c++) sb.append(sym(at(c, r)));
                sb.append('\n');
            }
            return sb.toString();
        }

        /** Grid on one line, rows separated by '/', for the log. */
        public String compact() {
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < g.rows; r++) {
                if (r > 0) sb.append('/');
                for (int c = 0; c < g.cols; c++) sb.append(sym(at(c, r)));
            }
            return sb.toString();
        }

        public static char sym(int v) {
            switch (v) {
                case UNKNOWN: return '#';
                case FLAG: return 'F';
                case UNREADABLE: return '?';
                case MINE: return '*';
                case BOOM: return 'X';
                case 0: return '.';
                default: return (char) ('0' + v);
            }
        }
    }

    // --------------------------------------------------------------- colours

    static int lum(int p) { return (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255)) / 3; }

    static int sat(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    static boolean white(int p) { return lum(p) >= 232 && sat(p) < 30; }
    static boolean dark(int p) { return lum(p) <= 165 && sat(p) < 40; }

    /** Number colours of the classic game, index = number (0 unused). */
    static final int[][] NUM = {
            null,
            {0, 0, 255},      // 1 blue
            {0, 128, 0},      // 2 green
            {255, 0, 0},      // 3 red
            {0, 0, 128},      // 4 navy
            {128, 0, 0},      // 5 maroon
            {0, 128, 128},    // 6 teal
            {0, 0, 0},        // 7 black
            {128, 128, 128},  // 8 grey
    };

    // ------------------------------------------------------------- geometry

    /**
     * Finds the grid from the raised tiles' bevels: every raised tile starts with a white edge on its
     * left and top, so the white-after-darker transitions line up in an even comb. Works best on a new
     * game (all tiles raised), which is when the bot locks the geometry.
     */
    public static Geometry findGrid(int[] px, int w, int h) {
        int[] colCnt = new int[w];
        for (int y = 0; y < h; y += 2) {
            int o = y * w;
            for (int x = 1; x < w - 3; x++) {
                if (edge(px[o + x], px[o + x + 1], px[o + x - 1], x >= 2 ? px[o + x - 2] : px[o + x - 1])) colCnt[x]++;
            }
        }
        int[] cols = comb(colCnt, w, Math.max(3, h / 80));
        if (cols == null) return null;
        float px0 = cols[0], pitchX = (cols[1] - cols[0]) / (float) Math.max(1, cols[2] - 1);
        int nCols = cols[2];
        int xa = Math.round(px0), xb = Math.min(w - 1, Math.round(px0 + nCols * pitchX));

        int[] rowCnt = new int[h];
        for (int y = 1; y < h - 3; y++) {
            int o = y * w;
            for (int x = xa; x < xb; x += 2) {
                if (edge(px[o + x], px[o + w + x], px[o - w + x], y >= 2 ? px[o - 2 * w + x] : px[o - w + x])) rowCnt[y]++;
            }
        }
        int[] rows = comb(rowCnt, h, Math.max(3, w / 80));
        if (rows == null) return null;
        float py0 = rows[0], pitchY = (rows[1] - rows[0]) / (float) Math.max(1, rows[2] - 1);
        int nRows = rows[2];
        if (Math.abs(pitchX - pitchY) > 0.15f * Math.max(pitchX, pitchY)) return null;   // tiles are square
        // A comb can miss a blurred first tooth or catch a white frame line after the last tile:
        // grow over neighbouring lines of raised tiles, then drop end lines without any.
        float x0 = px0, y0 = py0;
        for (int guard = 0; guard < 4; guard++) {
            boolean changed = false;
            if (raisedShare(px, w, h, x0 - pitchX, y0, pitchX, pitchY, 1, nRows) >= 0.5f) { x0 -= pitchX; nCols++; changed = true; }
            if (raisedShare(px, w, h, x0 + nCols * pitchX, y0, pitchX, pitchY, 1, nRows) >= 0.5f) { nCols++; changed = true; }
            if (raisedShare(px, w, h, x0, y0 - pitchY, pitchX, pitchY, nCols, 1) >= 0.5f) { y0 -= pitchY; nRows++; changed = true; }
            if (raisedShare(px, w, h, x0, y0 + nRows * pitchY, pitchX, pitchY, nCols, 1) >= 0.5f) { nRows++; changed = true; }
            if (!changed) break;
        }
        while (nCols > 1 && raisedShare(px, w, h, x0, y0, pitchX, pitchY, 1, nRows) < 0.3f) { x0 += pitchX; nCols--; }
        while (nCols > 1 && raisedShare(px, w, h, x0 + (nCols - 1) * pitchX, y0, pitchX, pitchY, 1, nRows) < 0.3f) nCols--;
        while (nRows > 1 && raisedShare(px, w, h, x0, y0, pitchX, pitchY, nCols, 1) < 0.3f) { y0 += pitchY; nRows--; }
        while (nRows > 1 && raisedShare(px, w, h, x0, y0 + (nRows - 1) * pitchY, pitchX, pitchY, nCols, 1) < 0.3f) nRows--;
        if (nCols < 4 || nRows < 4) return null;
        if (x0 < -2 || y0 < -2 || x0 + nCols * pitchX > w + 2 || y0 + nRows * pitchY > h + 2) return null;
        return new Geometry(x0, y0, pitchX, pitchY, nCols, nRows);
    }

    /** Start of a white bevel: bright here and next, clearly darker just before. */
    static boolean edge(int p, int next, int prev, int prev2) {
        int l = lum(p);
        if (l < 232 || sat(p) > 30 || lum(next) < 232) return false;
        int lp = lum(prev);
        return lp < l - 8 && Math.min(lp, lum(prev2)) < 205;
    }

    /** Share of raised tiles in a block of cells (cells off the screen count as not raised). */
    static float raisedShare(int[] px, int w, int h, float x0, float y0, float sw, float sh, int nc, int nr) {
        int n = 0, raised = 0;
        for (int r = 0; r < nr; r++) {
            for (int c = 0; c < nc; c++) {
                n++;
                float l = x0 + c * sw, t = y0 + r * sh;
                if (l < -1 || t < -1 || l + sw > w + 1 || t + sh > h + 1) continue;
                int v = cell(px, w, h, l, t, sw, sh);
                if (v == UNKNOWN || v == FLAG) raised++;
            }
        }
        return n == 0 ? 0 : raised / (float) n;
    }

    /**
     * Best evenly spaced run of peaks in a transition histogram. Returns {first, last, count}.
     */
    static int[] comb(int[] cnt, int n, int minCount) {
        int max = 0;
        for (int v : cnt) max = Math.max(max, v);
        if (max < minCount) return null;
        int thr = Math.max(minCount, max / 4);
        // peaks, merging neighbours within 3 px
        int[] peak = new int[n];
        int[] wgt = new int[n];
        int np = 0;
        for (int x = 0; x < n; x++) {
            if (cnt[x] < thr) continue;
            if (np > 0 && x - peak[np - 1] <= 3) {
                if (cnt[x] > wgt[np - 1]) { peak[np - 1] = x; wgt[np - 1] = cnt[x]; }
                continue;
            }
            peak[np] = x; wgt[np] = cnt[x]; np++;
        }
        if (np < 3) return null;
        int minP = Math.max(6, n / 60), maxP = n / 3;
        int bestScore = -1, bestA = 0, bestB = 0, bestN = 0;
        for (int i = 0; i < np; i++) {
            for (int j = i + 1; j < np; j++) {
                int d = peak[j] - peak[i];
                if (d < minP) continue;
                if (d > maxP) break;
                // walk forward from i with pitch d (re-anchored on every hit)
                int last = peak[i], hits = 1, score = wgt[i];
                float pitch = d;
                int k = i + 1;
                while (true) {
                    float want = last + pitch;
                    float tol = Math.max(2, pitch * 0.03f);
                    int hit = -1;
                    for (int q = k; q < np && peak[q] <= want + tol; q++) {
                        if (Math.abs(peak[q] - want) <= tol) { hit = q; break; }
                    }
                    if (hit < 0) break;
                    hits++; score += wgt[hit];
                    last = peak[hit]; k = hit + 1;
                }
                // the run must not continue backwards (start at its true first tooth)
                if (hits < 3) continue;
                // prefer more teeth, then stronger ones
                int s = hits * 1_000_000 + Math.min(score, 999_999);
                if (s > bestScore) { bestScore = s; bestA = peak[i]; bestB = last; bestN = hits; }
            }
        }
        if (bestScore < 0) return null;
        return new int[]{bestA, bestB, bestN};
    }

    // ---------------------------------------------------------------- cells

    /** Reads every cell plus the counter and the smiley. */
    public static Reading read(int[] px, int w, int h, Geometry g) {
        Reading rd = new Reading();
        rd.g = g;
        rd.cell = new int[g.cols * g.rows];
        for (int r = 0; r < g.rows; r++) {
            for (int c = 0; c < g.cols; c++) {
                int v = cell(px, w, h, g.x0 + c * g.pitchX, g.y0 + r * g.pitchY, g.pitchX, g.pitchY);
                rd.cell[r * g.cols + c] = v;
                if (v == UNKNOWN) rd.unknown++;
                else if (v == FLAG) rd.flags++;
                else if (v == UNREADABLE) rd.unreadable++;
                else if (v == MINE || v == BOOM) rd.minesShown++;
                else rd.opened++;
            }
        }
        readHeader(px, w, h, g, rd);
        return rd;
    }

    /** Fraction of pixels in a box (relative to the cell) that pass a test. */
    private interface Test { boolean ok(int p); }

    private static float frac(int[] px, int w, int h, float left, float top, float sw, float sh,
                              float fx0, float fy0, float fx1, float fy1, Test t) {
        int xa = Math.round(left + fx0 * sw), xb = Math.round(left + fx1 * sw);
        int ya = Math.round(top + fy0 * sh), yb = Math.round(top + fy1 * sh);
        if (xb <= xa) xb = xa + 1;
        if (yb <= ya) yb = ya + 1;
        int n = 0, ok = 0;
        for (int y = ya; y < yb; y++) {
            if (y < 0 || y >= h) continue;
            for (int x = xa; x < xb; x++) {
                if (x < 0 || x >= w) continue;
                n++;
                if (t.ok(px[y * w + x])) ok++;
            }
        }
        return n == 0 ? 0 : ok / (float) n;
    }

    static boolean redish(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return r > 170 && g < 90 && b < 90;
    }

    static int cell(int[] px, int w, int h, float left, float top, float sw, float sh) {
        // raised tile: white bevel top and left, dark bevel bottom and right
        float wt = frac(px, w, h, left, top, sw, sh, 0.20f, 0.03f, 0.80f, 0.07f, MineVision::white);
        float wl = frac(px, w, h, left, top, sw, sh, 0.03f, 0.20f, 0.07f, 0.80f, MineVision::white);
        float db = frac(px, w, h, left, top, sw, sh, 0.20f, 0.93f, 0.80f, 0.97f, MineVision::dark);
        float dr = frac(px, w, h, left, top, sw, sh, 0.93f, 0.20f, 0.97f, 0.80f, MineVision::dark);
        int bevel = (wt > 0.6f ? 1 : 0) + (wl > 0.6f ? 1 : 0) + (db > 0.6f ? 1 : 0) + (dr > 0.6f ? 1 : 0);
        if (bevel >= 3) {
            float red = frac(px, w, h, left, top, sw, sh, 0.15f, 0.15f, 0.85f, 0.85f, MineVision::redish);
            return red > 0.01f ? FLAG : UNKNOWN;
        }
        if (bevel == 2 && (wt > 0.6f) == (wl > 0.6f)) return UNREADABLE;   // half a bevel: covered or animating

        // opened tile
        float redFill = frac(px, w, h, left, top, sw, sh, 0.08f, 0.08f, 0.92f, 0.92f, MineVision::redish);
        float black = frac(px, w, h, left, top, sw, sh, 0.15f, 0.15f, 0.85f, 0.85f, p -> lum(p) < 45);
        float core = frac(px, w, h, left, top, sw, sh, 0.42f, 0.42f, 0.58f, 0.58f, p -> lum(p) < 60 || white(p));
        if (redFill > 0.45f && black > 0.08f) return BOOM;
        if (black > 0.16f && core > 0.85f) return MINE;

        // count number colours in the middle of the tile
        int xa = Math.round(left + 0.15f * sw), xb = Math.round(left + 0.85f * sw);
        int ya = Math.round(top + 0.12f * sh), yb = Math.round(top + 0.88f * sh);
        int[] votes = new int[9];
        int n = 0, odd = 0;
        for (int y = ya; y < yb; y++) {
            if (y < 0 || y >= h) continue;
            for (int x = xa; x < xb; x++) {
                if (x < 0 || x >= w) continue;
                n++;
                int p = px[y * w + x];
                int r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
                int s = sat(p), l = lum(p);
                if (s < 28 && l > 150) continue;                 // background grey / white
                int best = -1, bd = Integer.MAX_VALUE;
                for (int k = 1; k <= 8; k++) {
                    int dr2 = r - NUM[k][0], dg = gg - NUM[k][1], db2 = b - NUM[k][2];
                    int d = dr2 * dr2 + dg * dg + db2 * db2;
                    if (d < bd) { bd = d; best = k; }
                }
                if (bd > 75 * 75) { odd++; continue; }
                // grey "8" must really be mid grey, not anti-aliased edge of something
                if (best == 8 && (l < 105 || l > 150 || s > 18)) continue;
                votes[best]++;
            }
        }
        if (n == 0) return UNREADABLE;
        int best = 0, bv = 0;
        for (int k = 1; k <= 8; k++) if (votes[k] > bv) { bv = votes[k]; best = k; }
        if (bv >= Math.max(3, n * 0.025f)) return best;
        if (odd > n * 0.08f) return UNREADABLE;                 // something else is drawn here
        return 0;
    }

    // ---------------------------------------------------------------- header

    static boolean lcdLit(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return r > 190 && g < 100 && b < 100;
    }

    static boolean lcdAny(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return (r < 40 && g < 40 && b < 40) || (r > 45 && r > g * 2 + 25 && r > b * 2 + 25);
    }

    static void readHeader(int[] px, int w, int h, Geometry g, Reading rd) {
        int yb = Math.max(0, Math.round(g.top() - g.pitchY * 0.15f));
        int ya = Math.max(0, Math.round(g.top() - Math.max(g.pitchY * 2.2f, w * 0.16f)));
        if (yb - ya < 8) return;
        // smiley: yellow blob
        long sx = 0, sy = 0; int sn = 0;
        for (int y = ya; y < yb; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                int r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
                if (r > 200 && gg > 190 && b < 110) { sx += x; sy += y; sn++; }
            }
        }
        if (sn > 20) { rd.smileX = sx / (float) sn; rd.smileY = sy / (float) sn; }
        rd.mines = readCounter(px, w, ya, yb, Math.round(g.left()), Math.round(g.left() + (g.right() - g.left()) * 0.45f));
    }

    /** Reads the red 7-segment number in the left part of the header. */
    static int readCounter(int[] px, int w, int ya, int yb, int xa, int xb) {
        xa = Math.max(0, xa - 4);
        xb = Math.min(w, xb);
        // bounding box of red (lit or dim) segment pixels
        int minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
        int[] colRed = new int[w];
        for (int y = ya; y < yb; y++) {
            for (int x = xa; x < xb; x++) {
                int p = px[y * w + x];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                if (r > 45 && r > g * 2 + 25 && r > b * 2 + 25) colRed[x]++;
            }
        }
        // take the first run of red columns from the left (the counter, not the timer)
        int run0 = -1, run1 = -1, gap = 0;
        for (int x = xa; x < xb; x++) {
            if (colRed[x] >= 2) {
                if (run0 < 0) run0 = x;
                run1 = x; gap = 0;
            } else if (run0 >= 0 && ++gap > (yb - ya) / 2) break;
        }
        if (run0 < 0) return -1;
        minX = run0; maxX = run1;
        for (int y = ya; y < yb; y++) {
            for (int x = minX; x <= maxX; x++) {
                int p = px[y * w + x];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                if (r > 45 && r > g * 2 + 25 && r > b * 2 + 25) { minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
            }
        }
        int dw = maxX - minX + 1, dh = maxY - minY + 1;
        if (dh < 8 || dw < 8) return -1;
        int nd = Math.max(1, Math.min(4, Math.round(dw / (dh * 0.62f))));
        int value = 0;
        float cw = dw / (float) nd;
        for (int d = 0; d < nd; d++) {
            float l = minX + d * cw;
            int v = digit(px, w, yb, minY, cw * 0.86f, dh, l + cw * 0.07f);
            if (v < 0) return -1;
            value = value * 10 + v;
        }
        return value;
    }

    private static final int[] SEG = {   // bits: a b c d e f g
            0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011,
            0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011};

    static int digit(int[] px, int w, int h, float top, float dw, float dh, float left) {
        float[][] z = {   // x0 y0 x1 y1 for a b c d e f g
                {0.30f, 0.00f, 0.70f, 0.10f},
                {0.82f, 0.18f, 1.00f, 0.38f},
                {0.82f, 0.62f, 1.00f, 0.82f},
                {0.30f, 0.90f, 0.70f, 1.00f},
                {0.00f, 0.62f, 0.18f, 0.82f},
                {0.00f, 0.18f, 0.18f, 0.38f},
                {0.30f, 0.45f, 0.70f, 0.55f},
        };
        int bits = 0;
        for (int s = 0; s < 7; s++) {
            float f = frac(px, w, h, left, top, dw, dh, z[s][0], z[s][1], z[s][2], z[s][3], MineVision::lcdLit);
            bits = (bits << 1) | (f > 0.30f ? 1 : 0);
        }
        for (int d = 0; d < 10; d++) if (SEG[d] == bits) return d;
        if (bits == 0) return 0;   // blank leading digit
        return -1;
    }
}
