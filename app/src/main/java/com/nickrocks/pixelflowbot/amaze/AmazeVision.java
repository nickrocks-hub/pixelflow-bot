package com.nickrocks.pixelflowbot.amaze;

import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads an Amaze GO arrows board from a screen frame.
 *
 * The board is a square lattice. Each arrow is a brown polyline through lattice points ending in
 * an arrowhead (the triangle pokes ~1/4 cell past the head point; tails end in a round cap).
 * Tapping an arrow slides it out of the board in the head's direction; it can only leave if no
 * other arrow lies on the straight line ahead of its head.
 *
 * Plain Java (no Android), tested on a computer against frames from a screen recording.
 */
public final class AmazeVision {
    public static final int UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3;
    static final int[] DX = {0, 1, 0, -1}, DY = {-1, 0, 1, 0};

    public static final class Arrow {
        public int id;
        public int[] cells;     // lattice indices from tail to head
        public int dir;         // direction the head points, or -1 if the head is off screen
        public boolean partial; // part of it runs off the screen
        public int head() { return cells[cells.length - 1]; }
    }

    public static final class Board {
        public float pitch, x0, y0;      // lattice point (c, r) is at (x0 + c*pitch, y0 + r*pitch)
        public int cols, rows;
        public List<Arrow> arrows = new ArrayList<>();
        public int[] owner;              // lattice index -> arrow id, or -1
        public int lives = -1;           // blue drops in the header
        public boolean cutOff;           // arrows run off the screen edge: zoom out first
        public boolean[] cut = new boolean[4]; // which sides (UP/RIGHT/DOWN/LEFT) run off the screen
        public int top, bottom, left, right; // ink bounding box (screen px)
        public String error;
        public long ms;
        public int frameW, frameH;

        public float px(int idx) { return x0 + (idx % cols) * pitch; }
        public float py(int idx) { return y0 + (idx / cols) * pitch; }

        public String summary() {
            if (error != null && arrows.isEmpty()) return error;
            return arrows.size() + " arrows on a " + cols + "x" + rows + " grid (" + Math.round(pitch) + " px)"
                    + (lives >= 0 ? " · drops " + lives : "") + (cutOff ? " · board runs off screen" : "");
        }
    }

    public String lastNote = "";
    public static boolean DEBUG = false;

    // ------------------------------------------------------------------ pixels

    /** The brown arrow paint (113,79,47) and its anti-aliased edge, never the cream background or grey dots. */
    static boolean ink(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return r >= 70 && r <= 185 && g >= 40 && g <= 150 && b <= 120 && r - b >= 45 && r - g >= 18;
    }

    static boolean drop(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return b > 190 && r < 140 && g > 120 && b - r > 80;
    }

    // ------------------------------------------------------------------ main

    public Board read(Frame f) {
        long t0 = System.currentTimeMillis();
        Board bd = new Board();
        bd.frameW = f.w; bd.frameH = f.h;
        try {
            bd.lives = countDrops(f);
            if (!readBoard(f, bd) && bd.error == null) bd.error = lastNote;
        } catch (Throwable t) {
            bd.error = "read error: " + t;
        } finally {
            bd.ms = System.currentTimeMillis() - t0;
        }
        return bd;
    }

    /** The board area: below the header (back arrow, level name, drops) and above the bottom edge. */
    static int boardTop(Frame f) { return (int) (f.h * 0.185f); }
    static int boardBottom(Frame f) { return (int) (f.h * 0.975f); }

    boolean readBoard(Frame f, Board bd) {
        int w = f.w, h = f.h;
        int y0 = boardTop(f), y1 = boardBottom(f);
        boolean[] m = new boolean[w * h];
        int n = 0, minX = w, maxX = -1, minY = h, maxY = -1;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < w; x++) {
                if (ink(f.px[y * w + x])) {
                    m[y * w + x] = true;
                    n++;
                    if (x < minX) minX = x; if (x > maxX) maxX = x;
                    if (y < minY) minY = y; if (y > maxY) maxY = y;
                }
            }
        }
        if (n < 200) { lastNote = "no arrows on screen"; bd.error = lastNote; return false; }
        bd.left = minX; bd.right = maxX; bd.top = minY; bd.bottom = maxY;
        bd.cut[LEFT] = minX <= 3;
        bd.cut[RIGHT] = maxX >= w - 4;
        bd.cut[DOWN] = maxY >= y1 - 2;
        bd.cut[UP] = minY <= y0 + 1;
        bd.cutOff = bd.cut[0] || bd.cut[1] || bd.cut[2] || bd.cut[3];

        // Line thickness: median length of short vertical ink runs (crossing horizontal lines).
        int t = thickness(m, w, y0, y1, minX, maxX);
        if (t < 2) { lastNote = "lines too thin to read"; bd.error = lastNote; return false; }

        // Centres of horizontal lines (rows) and vertical lines (columns).
        float[] rowsY = lineCentres(m, w, h, y0, y1, minX, maxX, t, true);
        float[] colsX = lineCentres(m, w, h, y0, y1, minX, maxX, t, false);
        if (DEBUG) System.out.println("  t=" + t + " rows " + Arrays.toString(rowsY) + "\n  cols " + Arrays.toString(colsX));
        float p = pitch(rowsY, colsX, t);
        if (p <= 0) { lastNote = "can't find the grid spacing"; bd.error = lastNote; return false; }
        float ox = phase(colsX, p), oy = phase(rowsY, p);
        // Lattice covering the ink (with half a cell of margin for arrowheads).
        int c0 = (int) Math.floor((minX - ox) / p + 0.3f), c1 = (int) Math.ceil((maxX - ox) / p - 0.3f);
        int r0 = (int) Math.floor((minY - oy) / p + 0.3f), r1 = (int) Math.ceil((maxY - oy) / p - 0.3f);
        bd.pitch = p;
        bd.x0 = ox + c0 * p;
        bd.y0 = oy + r0 * p;
        bd.cols = c1 - c0 + 1;
        bd.rows = r1 - r0 + 1;
        if (bd.cols < 2 || bd.rows < 2 || bd.cols * bd.rows > 40000) { lastNote = "odd grid " + bd.cols + "x" + bd.rows; bd.error = lastNote; return false; }
        if (DEBUG) System.out.println("  thickness " + t + " pitch " + p + " lattice " + bd.cols + "x" + bd.rows + " @" + bd.x0 + "," + bd.y0);

        int N = bd.cols * bd.rows;
        // Edges between neighbouring lattice points: ink along the middle of the segment.
        boolean[] eR = new boolean[N], eD = new boolean[N];
        for (int r = 0; r < bd.rows; r++) {
            for (int c = 0; c < bd.cols; c++) {
                float x = bd.x0 + c * p, y = bd.y0 + r * p;
                if (c + 1 < bd.cols) eR[r * bd.cols + c] = segment(m, w, h, x, y, x + p, y, t);
                if (r + 1 < bd.rows) eD[r * bd.cols + c] = segment(m, w, h, x, y, x, y + p, t);
            }
        }
        int[] deg = new int[N];
        for (int i = 0; i < N; i++) {
            if (eR[i]) { deg[i]++; deg[i + 1]++; }
            if (eD[i]) { deg[i]++; deg[i + bd.cols]++; }
        }
        // Single-cell arrows (no edges) aren't used by the game; points with ink but no edge are noise.
        bd.owner = new int[N];
        Arrays.fill(bd.owner, -1);
        boolean[] seen = new boolean[N];
        for (int i = 0; i < N; i++) {
            if (deg[i] > 2) { lastNote = "lines meet in a T at " + (i % bd.cols) + "," + (i / bd.cols) + " — misread"; bd.error = lastNote; return false; }
        }
        for (int i = 0; i < N; i++) {
            if (seen[i] || deg[i] != 1) continue;
            // Walk the chain from this end.
            List<Integer> chain = new ArrayList<>();
            int prev = -1, cur = i;
            while (true) {
                chain.add(cur);
                seen[cur] = true;
                int next = -1;
                for (int d = 0; d < 4; d++) {
                    int nb = neighbour(bd, cur, d);
                    if (nb < 0 || nb == prev || !edge(bd, eR, eD, cur, d)) continue;
                    next = nb;
                }
                if (next < 0 || seen[next]) break;
                prev = cur;
                cur = next;
            }
            if (chain.size() < 2) continue;
            int a = chain.get(0), b = chain.get(chain.size() - 1);
            boolean cutA = runsOffScreen(m, w, y0, y1, bd, a, chain.get(1));
            boolean cutB = runsOffScreen(m, w, y0, y1, bd, b, chain.get(chain.size() - 2));
            float ha = cutA ? 0f : headScore(m, w, h, bd, a, chain.get(1), t);
            float hb = cutB ? 0f : headScore(m, w, h, bd, b, chain.get(chain.size() - 2), t);
            boolean headAtB = hb > ha;
            float best = Math.max(ha, hb), other = Math.min(ha, hb);
            boolean partial = cutA || cutB;
            boolean headHidden = partial && best < 0.5f;   // the visible end is a tail: head is off screen
            if (!headHidden && (best < 0.5f || other > 0.5f)) {
                lastNote = "can't tell which end is the arrowhead at " + (a % bd.cols) + "," + (a / bd.cols) + " (" + ha + "/" + hb + ")";
                bd.error = lastNote;
                return false;
            }
            Arrow ar = new Arrow();
            ar.id = bd.arrows.size();
            ar.cells = new int[chain.size()];
            for (int k = 0; k < chain.size(); k++) ar.cells[k] = headAtB ? chain.get(k) : chain.get(chain.size() - 1 - k);
            int hd = ar.cells[ar.cells.length - 1], before = ar.cells[ar.cells.length - 2];
            ar.dir = headHidden ? -1 : dirOf(bd, before, hd);
            ar.partial = partial;
            for (int cidx : ar.cells) bd.owner[cidx] = ar.id;
            bd.arrows.add(ar);
        }
        for (int i = 0; i < N; i++) {
            if (deg[i] > 0 && !seen[i]) { lastNote = "a closed loop of lines — misread"; bd.error = lastNote; return false; }
        }
        if (bd.arrows.isEmpty()) { lastNote = "no arrows found"; bd.error = lastNote; return false; }
        lastNote = bd.summary();
        return true;
    }

    /** True if the line leaves the end point and continues straight to the edge of the screen. */
    static boolean runsOffScreen(boolean[] m, int w, int y0, int y1, Board bd, int end, int inner) {
        int d = dirOf(bd, inner, end);
        float x = bd.px(end), y = bd.py(end);
        float edge = d == LEFT ? x : d == RIGHT ? (w - 1) - x : d == UP ? y - y0 : (y1 - 1) - y;
        if (edge > bd.pitch * 1.25f) return false;
        for (float s = 2; s < edge - 2; s += 2) {
            float xx = x + DX[d] * s, yy = y + DY[d] * s;
            if (!near(m, w, y1, xx, yy, 1)) return false;
        }
        return true;
    }

    static int neighbour(Board bd, int i, int d) {
        int c = i % bd.cols + DX[d], r = i / bd.cols + DY[d];
        if (c < 0 || r < 0 || c >= bd.cols || r >= bd.rows) return -1;
        return r * bd.cols + c;
    }

    static boolean edge(Board bd, boolean[] eR, boolean[] eD, int i, int d) {
        switch (d) {
            case RIGHT: return eR[i];
            case LEFT: return i % bd.cols > 0 && eR[i - 1];
            case DOWN: return eD[i];
            default: return i >= bd.cols && eD[i - bd.cols];
        }
    }

    static int dirOf(Board bd, int from, int to) {
        int dc = to % bd.cols - from % bd.cols, dr = to / bd.cols - from / bd.cols;
        if (dc == 1) return RIGHT;
        if (dc == -1) return LEFT;
        if (dr == 1) return DOWN;
        return UP;
    }

    /** Ink fraction across the middle 60% of a segment, sampled on the line and checked to be thin. */
    static boolean segment(boolean[] m, int w, int h, float xa, float ya, float xb, float yb, int t) {
        int hits = 0, n = 0;
        for (int k = 3; k <= 7; k++) {
            float x = xa + (xb - xa) * k / 10f, y = ya + (yb - ya) * k / 10f;
            n++;
            if (near(m, w, h, x, y, Math.max(1, t / 2))) hits++;
        }
        return hits >= 4;
    }

    static boolean near(boolean[] m, int w, int h, float x, float y, int r) {
        int cx = Math.round(x), cy = Math.round(y);
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int xx = cx + dx, yy = cy + dy;
                if (xx >= 0 && yy >= 0 && xx < w && yy < h && m[yy * w + xx]) return true;
            }
        }
        return false;
    }

    /**
     * How arrowhead-like the end at {@code end} is: the triangle reaches ~0.27 cell past the point
     * and is ~2-3x wider than the line just before it. A tail's round cap reaches only ~0.1 cell.
     */
    static float headScore(boolean[] m, int w, int h, Board bd, int end, int inner, int t) {
        int d = dirOf(bd, inner, end);
        float p = bd.pitch;
        float ex = bd.px(end), ey = bd.py(end);
        // Reach: ink at 0.2 cell beyond the end point.
        float rx = ex + DX[d] * p * 0.2f, ry = ey + DY[d] * p * 0.2f;
        boolean reach = near(m, w, h, rx, ry, Math.max(1, t / 3));
        // Width just behind the end point, perpendicular to the line.
        float bx = ex - DX[d] * p * 0.06f, by = ey - DY[d] * p * 0.06f;
        int px = DY[d] != 0 ? 1 : 0, py = DX[d] != 0 ? 1 : 0;   // perpendicular unit
        int width = 0;
        for (int s = -(int) (p * 0.45f); s <= (int) (p * 0.45f); s++) {
            int xx = Math.round(bx + px * s), yy = Math.round(by + py * s);
            if (xx >= 0 && yy >= 0 && xx < w && yy < h && m[yy * w + xx]) width++;
        }
        float wide = width / (float) Math.max(2, t);
        return (reach ? 0.5f : 0f) + (wide >= 1.9f ? 0.5f : wide >= 1.5f ? 0.25f : 0f);
    }

    static int thickness(boolean[] m, int w, int y0, int y1, int x0, int x1) {
        int[] hist = new int[64];
        int step = Math.max(1, (x1 - x0) / 300);
        for (int x = x0; x <= x1; x += step) {
            int run = 0;
            for (int y = y0; y < y1; y++) {
                if (m[y * w + x]) run++;
                else { if (run > 0 && run < 64) hist[run]++; run = 0; }
            }
        }
        int total = 0;
        for (int v : hist) total += v;
        int acc = 0;
        for (int i = 1; i < 64; i++) { acc += hist[i]; if (acc * 2 >= total) return i; }
        return -1;
    }

    /** Centre lines of long straight strokes (rows when horizontal=true). */
    static float[] lineCentres(boolean[] m, int w, int h, int y0, int y1, int x0, int x1, int t, boolean horizontal) {
        int len = horizontal ? h : w;
        float[] score = new float[len];
        int minRun = Math.max(4, t * 3);
        if (horizontal) {
            for (int y = y0; y < y1; y++) {
                int run = 0, tot = 0;
                for (int x = x0; x <= x1 + 1; x++) {
                    if (x <= x1 && m[y * w + x]) run++;
                    else { if (run >= minRun) tot += run; run = 0; }
                }
                score[y] = tot;
            }
        } else {
            for (int x = x0; x <= x1; x++) {
                int run = 0, tot = 0;
                for (int y = y0; y <= y1; y++) {
                    if (y < y1 && m[y * w + x]) run++;
                    else { if (run >= minRun) tot += run; run = 0; }
                }
                score[x] = tot;
            }
        }
        // Peaks: contiguous bands of score > 0, centre weighted; weak peaks (arrowhead bases,
        // anti-aliasing) are dropped and near-duplicates merged.
        List<float[]> peaks = new ArrayList<>();
        int i = 0;
        while (i < len) {
            if (score[i] <= 0) { i++; continue; }
            double sum = 0, wsum = 0;
            while (i < len && score[i] > 0) { sum += score[i] * i; wsum += score[i]; i++; }
            peaks.add(new float[]{(float) (sum / wsum), (float) wsum});
        }
        float[] ws = new float[peaks.size()];
        for (int k = 0; k < ws.length; k++) ws[k] = peaks.get(k)[1];
        float[] sorted = ws.clone();
        Arrays.sort(sorted);
        float med = sorted.length == 0 ? 0 : sorted[sorted.length / 2];
        List<float[]> keep = new ArrayList<>();
        for (float[] pk : peaks) {
            if (pk[1] < med * 0.2f) continue;
            if (!keep.isEmpty() && pk[0] - keep.get(keep.size() - 1)[0] < t * 1.6f) {
                float[] last = keep.get(keep.size() - 1);
                if (pk[1] > last[1]) keep.set(keep.size() - 1, pk);
                continue;
            }
            keep.add(pk);
        }
        float[] a = new float[keep.size()];
        for (int k = 0; k < a.length; k++) a[k] = keep.get(k)[0];
        return a;
    }

    static float[] diffs(float[] a) {
        float[] d = new float[Math.max(0, a.length - 1)];
        for (int i = 1; i < a.length; i++) d[i - 1] = a[i] - a[i - 1];
        return d;
    }

    static float[] merge(float[] a, float[] b) {
        float[] o = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    /** Largest spacing p such that (almost) every line centre sits on a multiple of p. */
    static float pitch(float[] ys, float[] xs, int t) {
        float maxP = 0;
        for (float d : diffs(ys)) maxP = Math.max(maxP, d);
        for (float d : diffs(xs)) maxP = Math.max(maxP, d);
        maxP = Math.min(maxP, 400);
        float best = -1;
        for (float p = Math.max(3f * t, 8f); p <= maxP + 0.5f; p += Math.max(0.01f, p * 0.0015f)) {
            float fy = fit(ys, p), fx = fit(xs, p);
            if (fy >= 0.85f && fx >= 0.85f) best = p;
        }
        if (best < 0) return -1;
        // Refine: least squares over all centres for this pitch.
        return refine(ys, xs, best);
    }

    static float fit(float[] a, float p) {
        if (a.length == 0) return 1f;
        float ph = phase(a, p);
        int ok = 0;
        for (float v : a) {
            float k = (v - ph) / p;
            if (Math.abs(k - Math.round(k)) * p <= Math.max(1.5f, p * 0.1f)) ok++;
        }
        return ok / (float) a.length;
    }

    /** Offset of the lattice: circular mean of positions modulo p. */
    static float phase(float[] a, float p) {
        double s = 0, c = 0;
        for (float v : a) { double ang = 2 * Math.PI * v / p; s += Math.sin(ang); c += Math.cos(ang); }
        double ang = Math.atan2(s, c);
        if (ang < 0) ang += 2 * Math.PI;
        return (float) (ang / (2 * Math.PI) * p);
    }

    static float refine(float[] ys, float[] xs, float p) {
        // Fit v = off + k*p for each axis jointly in p.
        double num = 0, den = 0;
        for (float[] a : new float[][]{ys, xs}) {
            if (a.length < 2) continue;
            float ph = phase(a, p);
            double mk = 0, mv = 0;
            int n = a.length;
            long[] k = new long[n];
            for (int i = 0; i < n; i++) { k[i] = Math.round((a[i] - ph) / p); mk += k[i]; mv += a[i]; }
            mk /= n; mv /= n;
            for (int i = 0; i < n; i++) { num += (k[i] - mk) * (a[i] - mv); den += (k[i] - mk) * (k[i] - mk); }
        }
        return den > 0 ? (float) (num / den) : p;
    }

    // ------------------------------------------------------------------ header

    /** Blue water drops (lives) in the header, left side. */
    static int countDrops(Frame f) {
        int w = f.w;
        int y0 = (int) (f.h * 0.09f), y1 = (int) (f.h * 0.18f);
        int bestRow = -1, bestCount = 0;
        int minLen = Math.max(4, w / 120);
        for (int y = y0; y < y1; y += 2) {
            // Runs of drop colour, joining anti-aliasing gaps of up to 3 px.
            int count = 0, runStart = -1, lastHit = -100;
            for (int x = 0; x <= w / 2; x++) {
                boolean d = x < w / 2 && drop(f.px[y * w + x]);
                if (d) {
                    if (x - lastHit > 4) {
                        if (runStart >= 0 && lastHit - runStart + 1 >= minLen) count++;
                        runStart = x;
                    }
                    lastHit = x;
                }
            }
            if (runStart >= 0 && lastHit - runStart + 1 >= minLen) count++;
            if (count > bestCount) { bestCount = count; bestRow = y; }
        }
        return bestRow < 0 ? -1 : bestCount;
    }
}
