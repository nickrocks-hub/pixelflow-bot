package com.nickrocks.pixelflowbot.vision;

/**
 * Finds the cube grid and reads each cube's colour.
 *
 * The grid is measured from the spacing of the gaps between cubes (a frequency fit,
 * then a fine "comb" search), not from the board's outline. Tested on recordings, this
 * gives the same grid at the start, middle and end of a level, so the bot can be
 * started mid-level. The grid covers the whole board panel; cells with no cube read EMPTY.
 */
public final class BoardReader {
    // Inside of the board panel's light frame, in 1080x2520 layout coordinates.
    // Same on Levels 24, 25 and 26; only the board inside it changes size.
    static final float RX0 = 93, RX1 = 987, RY0 = 387, RY1 = 1398;
    static final float CORNER = 40;

    public static final class Calibration {
        public final float x0, y0, cellW, cellH;   // x0,y0 = top-left corner of cell (0,0)
        public final int cols, rows;
        Calibration(float x0, float y0, float cellW, float cellH, int cols, int rows) {
            this.x0 = x0; this.y0 = y0; this.cellW = cellW; this.cellH = cellH; this.cols = cols; this.rows = rows;
        }
        public float cellW() { return cellW; }
        public float cellH() { return cellH; }
        /** Screen centre of a cell. */
        public float cx(int c) { return x0 + (c + 0.5f) * cellW; }
        public float cy(int r) { return y0 + (r + 0.5f) * cellH; }
        boolean closeTo(Calibration o) {
            return o != null && Math.abs(cellW - o.cellW) < 0.15f && Math.abs(cellH - o.cellH) < 0.15f
                    && Math.abs(x0 - o.x0) < 1.5f && Math.abs(y0 - o.y0) < 1.5f;
        }
        @Override public String toString() {
            return String.format(java.util.Locale.US, "pitch %.2fx%.2f", cellW, cellH);
        }
    }

    static float lum(Frame f, int x, int y) { return (f.r(x, y) + f.g(x, y) + f.b(x, y)) / 3f; }

    /** Strongest repeat distance in a profile (15..45 px, scaled) and the offset of its peaks. */
    static double[] spectralPeak(float[] p, double lo, double hi) {
        double mean = 0;
        for (float v : p) mean += v;
        mean /= p.length;
        double best = -1, bp = 0, bph = 0;
        for (double P = lo; P < hi; P += 0.02) {
            double re = 0, im = 0, w = 2 * Math.PI / P;
            for (int k = 0; k < p.length; k++) {
                double v = p[k] - mean;
                re += v * Math.cos(w * k);
                im += v * Math.sin(w * k);
            }
            double s = re * re + im * im;
            if (s > best) { best = s; bp = P; bph = Math.atan2(im, re); }
        }
        double off = bph / (2 * Math.PI) * bp;
        if (off < 0) off += bp;
        return new double[]{bp, off, best};
    }

    /** Fine search: pitch within +-3% and offset, maximising mean gradient at the predicted gaps. */
    static double[] comb(float[] g, double P0) {
        double best = -1, bp = P0, bo = 0;
        for (double P = P0 * 0.97; P <= P0 * 1.03; P += 0.005) {
            for (double o = 0; o < P; o += 0.25) {
                double s = 0;
                int n = 0;
                for (double x = o; x < g.length - 1; x += P) {
                    int i = (int) x;
                    double fr = x - i;
                    s += g[i] * (1 - fr) + g[i + 1] * fr;
                    n++;
                }
                s /= n;
                if (s > best) { best = s; bp = P; bo = o; }
            }
        }
        return new double[]{bp, bo};
    }

    /** Measures the grid. Returns null if no cube grid is visible. */
    public static Calibration calibrate(Frame f) {
        int x0 = f.X(RX0), x1 = f.X(RX1), y0 = f.Y(RY0), y1 = f.Y(RY1);
        float[] gx = new float[x1 - x0], gy = new float[y1 - y0];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                float l = lum(f, x, y);
                gx[x - x0] += Math.abs(lum(f, x + 1, y) - l);
                gy[y - y0] += Math.abs(lum(f, x, y + 1) - l);
            }
        }
        double lo = 15 * f.sx, hi = 45 * f.sx;
        double[] sx = spectralPeak(gx, lo, hi), sy = spectralPeak(gy, lo, hi);
        double[] cx = comb(gx, sx[0]), cy = comb(gy, sy[0]);
        double pw = cx[0], ph = cy[0];
        if (ph / pw < 0.8 || ph / pw > 1.1) return null;   // not a cube grid
        // Cell (0,0) starts at the first gap inside the region, or one cell earlier if that cell's
        // centre is still inside (a board can start right at the frame).
        float gx0 = (float) (x0 + cx[1]), gy0 = (float) (y0 + cy[1]);
        if (gx0 - pw * 0.65 >= x0) gx0 -= pw;
        if (gy0 - ph * 0.65 >= y0) gy0 -= ph;
        int cols = (int) Math.floor((x1 - gx0 - 0.65 * pw) / pw) + 1;
        int rows = (int) Math.floor((y1 - gy0 - 0.65 * ph) / ph) + 1;
        if (cols < 5 || rows < 5) return null;
        Calibration c = new Calibration(gx0, gy0, (float) pw, (float) ph, cols, rows);
        // A real board has plenty of cubes.
        int filled = 0;
        for (GameColor[] row : read(f, c)) for (GameColor g : row) if (g != GameColor.EMPTY) filled++;
        return filled >= 10 ? c : null;
    }

    /** Colour of every cell, [row][col]. */
    public static GameColor[][] read(Frame f, Calibration c) {
        GameColor[][] g = new GameColor[c.rows][c.cols];
        float rx0 = f.X(RX0), rx1 = f.X(RX1), ry0 = f.Y(RY0), ry1 = f.Y(RY1), R = CORNER * f.sx;
        for (int r = 0; r < c.rows; r++) {
            for (int col = 0; col < c.cols; col++) {
                float cx = c.cx(col), cy = c.cy(r);
                // The frame's rounded corners poke into the region; never a cube there.
                boolean nearX = cx < rx0 + R || cx > rx1 - R, nearY = cy < ry0 + R || cy > ry1 - R;
                if (nearX && nearY) { g[r][col] = GameColor.EMPTY; continue; }
                int[] m = f.medianRgb((int) (cx - c.cellW * .15f), (int) (cy - c.cellH * .15f),
                        (int) (cx + c.cellW * .15f), (int) (cy + c.cellH * .15f));
                g[r][col] = GameColor.classify(m[0], m[1], m[2]);
            }
        }
        return g;
    }
}
