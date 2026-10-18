package com.nickrocks.pixelflowbot.vision;

/**
 * Finds the cube grid and reads each cube's color.
 * Calibrate once on a full board (level start); the grid never moves during a level.
 */
public final class BoardReader {
    private static final int BG_R = 57, BG_G = 59, BG_B = 99;

    public static final class Calibration {
        public final int x0, x1, y0, y1, cols, rows;
        public final float fit;
        Calibration(int x0, int x1, int y0, int y1, int cols, int rows, float fit) {
            this.x0 = x0; this.x1 = x1; this.y0 = y0; this.y1 = y1;
            this.cols = cols; this.rows = rows; this.fit = fit;
        }
        public float cellW() { return (x1 - x0 + 1) / (float) cols; }
        public float cellH() { return (y1 - y0 + 1) / (float) rows; }
        /** Screen centre of a cell, for tapping or drawing. */
        public float cx(int c) { return x0 + (c + 0.5f) * cellW(); }
        public float cy(int r) { return y0 + (r + 0.5f) * cellH(); }
        public int area() { return (x1 - x0 + 1) * (y1 - y0 + 1); }
        @Override public String toString() { return cols + "x" + rows; }
    }

    /** Bounding box of everything that isn't navy background inside the board panel. Null if none. */
    public static int[] boardBox(Frame f) {
        int sx0 = f.X(100), sx1 = f.X(980), sy0 = f.Y(420), sy1 = f.Y(1320);
        int W = sx1 - sx0, H = sy1 - sy0;
        int[] colCount = new int[W], rowCount = new int[H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int X = sx0 + x, Y = sy0 + y;
                int d = Math.abs(f.r(X, Y) - BG_R) + Math.abs(f.g(X, Y) - BG_G) + Math.abs(f.b(X, Y) - BG_B);
                if (d > 60) { colCount[x]++; rowCount[y]++; }
            }
        }
        int cx0 = -1, cx1 = -1, cy0 = -1, cy1 = -1;
        for (int x = 0; x < W; x++) if (colCount[x] > 0.35f * H) { if (cx0 < 0) cx0 = x; cx1 = x; }
        for (int y = 0; y < H; y++) if (rowCount[y] > 0.35f * W) { if (cy0 < 0) cy0 = y; cy1 = y; }
        if (cx0 < 0 || cy0 < 0 || cx1 - cx0 < 100 || cy1 - cy0 < 100) return null;
        return new int[]{sx0 + cx0, sx0 + cx1, sy0 + cy0, sy0 + cy1};
    }

    /** Strongest repeat distance (pixels) in a 1-D profile, searched 15..45 px. */
    static float pitch(float[] p) {
        int n = p.length;
        double mean = 0;
        for (float v : p) mean += v;
        mean /= n;
        double best = -1; float bestP = 0;
        for (float P = 15f; P < 45f; P += 0.02f) {
            double re = 0, im = 0, w = 2 * Math.PI / P;
            for (int k = 0; k < n; k++) {
                double v = p[k] - mean;
                re += v * Math.cos(w * k);
                im -= v * Math.sin(w * k);
            }
            double s = re * re + im * im;
            if (s > best) { best = s; bestP = P; }
        }
        return bestP;
    }

    static float lum(Frame f, int x, int y) { return (f.r(x, y) + f.g(x, y) + f.b(x, y)) / 3f; }

    /** Mean per-cell colour spread for a candidate grid; lower = better aligned. */
    static float cellSpread(Frame f, int x0, int x1, int y0, int y1, int n, int m) {
        float cw = (x1 - x0 + 1) / (float) n, ch = (y1 - y0 + 1) / (float) m;
        double total = 0;
        for (int r = 0; r < m; r++) {
            for (int c = 0; c < n; c++) {
                float cx = x0 + (c + .5f) * cw, cy = y0 + (r + .5f) * ch;
                int ax = (int) (cx - cw * .2f), bx = (int) (cx + cw * .2f);
                int ay = (int) (cy - ch * .2f), by = (int) (cy + ch * .2f);
                double[] sum = new double[3], sq = new double[3];
                int k = 0;
                for (int y = ay; y < by; y++) for (int x = ax; x < bx; x++) {
                    int p = f.px[y * f.w + x];
                    int[] ch3 = {(p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff};
                    for (int i = 0; i < 3; i++) { sum[i] += ch3[i]; sq[i] += ch3[i] * ch3[i]; }
                    k++;
                }
                if (k == 0) continue;
                for (int i = 0; i < 3; i++) {
                    double mu = sum[i] / k;
                    total += Math.sqrt(Math.max(0, sq[i] / k - mu * mu));
                }
            }
        }
        return (float) (total / (n * m));
    }

    /** Run on a full board. Returns null if no board is visible. */
    public static Calibration calibrate(Frame f) {
        int[] box = boardBox(f);
        if (box == null) return null;
        int x0 = box[0], x1 = box[1], y0 = box[2], y1 = box[3];
        int W = x1 - x0 + 1, H = y1 - y0 + 1;
        float[] gx = new float[W - 1], gy = new float[H - 1];
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x < x1; x++) gx[x - x0] += Math.abs(lum(f, x + 1, y) - lum(f, x, y));
        for (int y = y0; y < y1; y++)
            for (int x = x0; x <= x1; x++) gy[y - y0] += Math.abs(lum(f, x, y + 1) - lum(f, x, y));
        float px = pitch(gx), py = pitch(gy);
        Calibration best = null;
        int[] ns = {(int) (W / px), Math.round(W / px), (int) (W / px) + 1};
        int[] ms = {(int) (H / py), Math.round(H / py), (int) (H / py) + 1};
        for (int n : ns) for (int m : ms) {
            if (n < 5 || m < 5) continue;
            float s = cellSpread(f, x0, x1, y0, y1, n, m);
            if (best == null || s < best.fit) best = new Calibration(x0, x1, y0, y1, n, m, s);
        }
        return best;
    }

    /** Colour of every cell, [row][col]. */
    public static GameColor[][] read(Frame f, Calibration c) {
        GameColor[][] g = new GameColor[c.rows][c.cols];
        float cw = c.cellW(), ch = c.cellH();
        for (int r = 0; r < c.rows; r++) {
            for (int col = 0; col < c.cols; col++) {
                float cx = c.x0 + (col + .5f) * cw, cy = c.y0 + (r + .5f) * ch;
                int[] m = f.medianRgb((int) (cx - cw * .15f), (int) (cy - ch * .15f),
                        (int) (cx + cw * .15f), (int) (cy + ch * .15f));
                g[r][col] = GameColor.classify(m[0], m[1], m[2]);
            }
        }
        return g;
    }
}
