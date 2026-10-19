package com.aigame.subwaysurfers.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pure-Java image analysis for Subway Surfers. No Android imports, so it runs
 * the same on the phone and in the desktop replay tool.
 *
 * Input frames are packed 0xRRGGBB ints, already downscaled (the capture
 * service records about 360 px wide). Distances are scaled by the frame width.
 *
 * Idea: every world in the game has the same three railway tracks. The steel
 * rails show up as thin bright lines, and they disappear exactly where a train
 * or barrier stands on them. So:
 *   1. mark "ridge" pixels (brighter than the pixels a few px left and right),
 *   2. find the vanishing point the rails meet at (the point that lines them up sharpest),
 *   3. fit a regular grid of the three tracks (left, middle, right),
 *   4. walk each track's rails from the runner towards the horizon; where both
 *      rails vanish is the nearest obstacle in that lane.
 *
 * tracks[0..2] are relative to the runner: the lane to its left, its own lane (the
 * track nearest the screen centre) and the lane to its right. The camera only partly
 * follows the runner, so how far its track sits from the centre tells which real lane
 * it is in (see ownOffset).
 */
public final class TrackVision {

    // ---- layout, as fractions of the frame height (portrait phone)
    /** Top of the region searched for rails (above it: HUD, sky, horizon). */
    public double bandTop = 0.30;
    /** Bottom of the searched region (below it: power-up bar, inspector). */
    public double bandBottom = 0.92;
    /** Hough reference rows. */
    public double rowNear = 0.86, rowFar = 0.42;
    /** Screen row of the runner's feet. */
    public double runnerFeet = 0.84;
    /** Rows from here up are checked for obstacles (just above the runner's head). */
    public double scanStart = 0.66;

    /** One rail line: x at the near and far reference rows, plus its vote count. */
    public static final class Line {
        public double xn, xf;
        public int votes;
        public double xAt(double y, double yn, double yf) {
            return xn + (xf - xn) * (yn - y) / (yn - yf);
        }
    }

    /** One track (lane) found this frame. */
    public static final class Track {
        public Line left, right;
        /** Track centre at the runner's feet row. */
        public double centerAtFeet;
        /** Nearest blocked row (screen y) above scanStart, or -1 when clear up to the horizon. */
        public int obstacleY = -1;
        /** How many rows the blockage spans before rails show again (tall = train). */
        public int obstacleSpan;
        /** Rows still visible beyond the blockage (0 when it reaches the horizon). */
        public boolean railsResume;
        /** The rails are hidden right in front of the runner (on a train roof, or about to hit). */
        public boolean blockedAtStart;
        /** Neighbour lanes: a train alongside the runner hides this lane's rail nearest to us. */
        public boolean sideBlocked;
        /** The left / right rail is hidden next to the runner's position (a train alongside). */
        public boolean leftRailHidden, rightRailHidden;
        /** Fraction of rows next to the runner where the left / right rail was seen. */
        public double leftSeen, rightSeen;
    }

    public static final class Result {
        public boolean ok;
        public double vpX, vpY;
        public final List<Line> lines = new ArrayList<>();
        public final List<Track> tracks = new ArrayList<>();
        public int ridgeCount;
        public double vpSharpness;
        /** Rail evidence for the fitted grid: [1..3] = its three tracks, [0]/[4] = a 4th track either side. */
        public final double[] slotScore = new double[5];
        /**
         * Runner's track relative to the screen centre, in lane widths, at the runner's feet.
         * The camera only partly follows the runner: about -0.29 in the left lane, 0 in the
         * middle lane, +0.29 in the right lane.
         */
        public double ownOffset;
        /** Runner's track centre at the near reference row. */
        public double ownNear;
        /** Coins / power-ups ahead in each lane (left, own, right), nearer = worth more. */
        public final double[] reward = new double[3];
        public String why = "";
    }

    private int w, h;
    private boolean[] ridge = new boolean[0];
    private int[] lum = new int[0];

    public boolean isRidge(int x, int y) { return ridge[y * w + x]; }

    public Result analyse(int[] px, int width, int height) {
        Result res = new Result();
        w = width; h = height;
        final double s = w / 360.0;
        if (ridge.length != w * h) { ridge = new boolean[w * h]; lum = new int[w * h]; }
        Arrays.fill(ridge, false);

        final int y0 = (int) (h * bandTop), y1 = (int) (h * bandBottom);
        final int d = Math.max(2, (int) Math.round(3 * s));
        final int thr = 12;
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[row + x];
                lum[row + x] = (((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF)) / 3;
            }
        }
        int count = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = d; x < w - d; x++) {
                int L = lum[row + x];
                int side = Math.max(lum[row + x - d], lum[row + x + d]);
                if (L - side > thr && L >= lum[row + x - 1] && L >= lum[row + x + 1]) {
                    ridge[row + x] = true;
                    count++;
                }
            }
        }
        res.ridgeCount = count;

        final double yn = h * rowNear, yf = h * rowFar;
        if (count < 200) { res.why = "no rails"; return res; }
        collectRidge(y0, y1);
        // ---- vanishing point: the point that makes the rails line up sharpest.
        double[] vp;
        if (!Double.isNaN(hintX)) {
            vp = searchVp(hintX - 24 * s, hintX + 24 * s, hintY - 18 * s, hintY + 18 * s, 4 * s, 3, yn);
        } else {
            vp = searchVp(w * vpXMin, w * vpXMax, h * vpYMin, h * vpYMax, 6 * s, 4, yn);
        }
        vp = searchVp(vp[0] - 3 * s, vp[0] + 3 * s, vp[1] - 3 * s, vp[1] + 3 * s, 1 * s, 2, yn);
        if (vp[1] <= h * vpYMin + 1 || vp[1] >= h * vpYMax - 1) { res.why = "no vanishing point"; return res; }
        res.vpX = vp[0]; res.vpY = vp[1];
        res.vpSharpness = vp[2];
        fitGrid(res, yn, yf, s, y0, y1);
        if (res.lines.size() < 2) { res.why = "no rail grid"; return res; }
        centreOnRunner(res, yn, yf);
        pairTracks(res, yn, yf);
        if (res.tracks.isEmpty()) { res.why = "no tracks"; return res; }
        if (res.tracks.size() == 3) {
            double c0 = res.tracks.get(0).centerAtFeet, c1 = res.tracks.get(1).centerAtFeet, c2 = res.tracks.get(2).centerAtFeet;
            res.ownOffset = (c1 - w * (0.5 + runnerOffset)) / ((c2 - c0) / 2);
        }
        for (Track t : res.tracks) scanObstacle(t, yn, yf, s, res);
        if (res.tracks.size() == 3) {
            // the neighbour's rail nearest us must be visible level with the runner
            for (int k = 0; k < 3; k++) checkSides(res.tracks.get(k), yn, yf, s);
            res.tracks.get(0).sideBlocked = res.tracks.get(0).rightRailHidden;
            res.tracks.get(2).sideBlocked = res.tracks.get(2).leftRailHidden;
        }
        if (res.tracks.size() == 3) scanRewards(px, res, s);
        res.ok = true;
        return res;
    }

    // ---- vanishing point search
    /** Where the vanishing point was last frame (NaN = search the whole likely area). */
    public double hintX = Double.NaN, hintY = Double.NaN;
    private int[] rxs = new int[0], rys = new int[0];
    private int rn;
    private int[] vhist = new int[0];

    private void collectRidge(int y0, int y1) {
        rn = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (!ridge[row + x]) continue;
                if (rn == rxs.length) { rxs = Arrays.copyOf(rxs, rn * 2 + 1024); rys = Arrays.copyOf(rys, rn * 2 + 1024); }
                rxs[rn] = x; rys[rn] = y; rn++;
            }
        }
    }

    /** Grid search; score = sum of squared counts of the near-row histogram (sharp rails = high). */
    /** Where the vanishing point may be, as fractions of the frame (walls and trains have
     *  vertical edges that would otherwise pull it up to infinity). */
    public double vpXMin = 0.25, vpXMax = 0.75, vpYMin = 0.15, vpYMax = 0.30;

    private int[] stamp = new int[0];
    private int epoch = 0;
    private float[] rowK = new float[0];

    private double[] searchVp(double xa, double xb, double ya, double yb, double step, int sub, double yn) {
        xa = Math.max(xa, w * vpXMin); xb = Math.min(xb, w * vpXMax);
        ya = Math.max(ya, h * vpYMin); yb = Math.min(yb, h * vpYMax);
        final int off = 2 * w, len = 5 * w;
        if (vhist.length != len) { vhist = new int[len]; stamp = new int[len]; }
        if (rowK.length != h) rowK = new float[h];
        final int[] xs = rxs, ys = rys, hist = vhist, st = stamp;
        final float[] rk = rowK;
        double best = -1, bx = (xa + xb) / 2, by = (ya + yb) / 2;
        for (double vy = ya; vy <= yb + 1e-9; vy += step) {
            for (int y = 0; y < h; y++) {
                double dy = y - vy;
                rk[y] = dy < 6 ? 0f : (float) ((yn - vy) / dy);
            }
            for (double vx = xa; vx <= xb + 1e-9; vx += step) {
                final float fvx = (float) vx;
                final int ep = ++epoch;
                long sc = 0; int used = 0;
                for (int i = 0; i < rn; i += sub) {
                    float k = rk[ys[i]];
                    if (k == 0f) continue;
                    int j = (int) (fvx + (xs[i] - fvx) * k + 0.5f) + off;
                    if (j < 0 || j >= len) continue;
                    int c;
                    if (st[j] != ep) { st[j] = ep; c = 0; } else c = hist[j];
                    hist[j] = c + 1;
                    sc += 2L * c + 1;        // running sum of squares
                    used++;
                }
                double score = used == 0 ? 0 : (double) sc / used;
                if (score > best) { best = score; bx = vx; by = vy; }
            }
        }
        return new double[]{bx, by, best};
    }

    // ---- rail grid
    /** Gauge (gap inside a track) as a fraction of the lane pitch, measured from recordings. */
    public double gaugeRatio = 0.45;
    /** Lane pitch at the near row, as a fraction of (near row - vanishing point). */
    public double pitchMin = 0.38, pitchMax = 0.72;
    private float[] hist = new float[0], sm = new float[0], rt = new float[0];
    /** Lane pitch last frame (NaN = unknown); narrows the search. */
    public double pitchHint = Double.NaN;
    /** Runner's usual horizontal position, fraction of width from centre. */
    public double runnerOffset = 0;
    /** How much rails where a 4th track would be count against a grid fit. */
    public double outsidePenalty = 0.6;
    /** Fitted grid at the near row: centre of the middle track and lane pitch. */
    public double gridC, gridP;
    /** Rail evidence: [1..3] = left, middle, right track, [0] / [4] = where a 4th track would be. */
    public final double[] trackScore = new double[5];

    /**
     * Projects every ridge pixel along its ray from the vanishing point down to the
     * near row and histograms the x there. Rails become sharp peaks. Then fits a
     * regular grid (pitch P, gauge 0.45 P) to the peaks.
     */
    private void fitGrid(Result res, double yn, double yf, double s, int y0, int y1) {
        final int off = 2 * w, len = 5 * w;          // near-row x in [-2w, 3w)
        if (hist.length != len) hist = new float[len];
        Arrays.fill(hist, 0);
        double vx = res.vpX, vy = res.vpY;
        int yStart = (int) Math.max(y0, vy + 8 * s);
        if (rowK.length != h) rowK = new float[h];
        for (int y = 0; y < h; y++) rowK[y] = y < yStart ? 0f : (float) ((yn - vy) / (y - vy));
        final float fvx = (float) vx;
        for (int i = 0; i < rn; i++) {
            float k = rowK[rys[i]];
            if (k == 0f) continue;
            int j = (int) (fvx + (rxs[i] - fvx) * k + 0.5f) + off;
            if (j >= 0 && j < len) hist[j] += 1f;
        }
        // smooth [1 2 3 2 1]
        if (sm.length != len) sm = new float[len];
        for (int i = 2; i < len - 2; i++)
            sm[i] = hist[i - 2] + 2 * hist[i - 1] + 3 * hist[i] + 2 * hist[i + 1] + hist[i + 2];
        double bestScore = -1, bestC = 0, bestP = 0, bestG = 0;
        double pMin = pitchMin * (yn - vy), pMax = pitchMax * (yn - vy);
        if (!Double.isNaN(pitchHint)) {
            pMin = Math.max(pMin, pitchHint * 0.88);
            pMax = Math.min(pMax, pitchHint * 1.12);
            if (pMin > pMax) { pMin = pitchMin * (yn - vy); pMax = pitchMax * (yn - vy); }
        }
        int tol = Math.max(2, (int) Math.round(4 * s));
        // sqrt per rail: a fit that explains all six rails beats one that explains two huge ones
        if (rt.length != len) rt = new float[len];
        for (int i = 0; i < len; i++) {
            float m = 0;
            for (int d = -tol; d <= tol; d++) { int j = i + d; if (j >= 0 && j < len && sm[j] > m) m = sm[j]; }
            rt[i] = (float) Math.sqrt(m);
        }
        // Fit the three real tracks. There are only three, so rails where a 4th track
        // would be count against the fit: this stops the grid slipping one lane sideways.
        // coarse pass, then a fine pass around the best coarse fit
        for (int pass = 0; pass < 2; pass++) {
            double pa, pb, pStep, ca, cb, cStep;
            if (pass == 0) { pa = pMin; pb = pMax; pStep = 4 * s; ca = -0.5 * w; cb = 1.5 * w; cStep = 2; }
            else {
                if (bestScore <= 0) break;
                pa = Math.max(pMin, bestP - 4 * s); pb = Math.min(pMax, bestP + 4 * s); pStep = 1;
                ca = bestC - 3; cb = bestC + 3; cStep = 1;
            }
            for (double P = pa; P <= pb + 1e-9; P += pStep) {
                for (double gr = gaugeRatio - 0.03; gr <= gaugeRatio + 0.031; gr += 0.03) {
                    double g = gr * P;
                    for (double c = ca; c <= cb + 1e-9; c += cStep) {
                        double sc = 0;
                        for (int k = -1; k <= 1; k++) sc += at(rt, c + k * P - g / 2, off) + at(rt, c + k * P + g / 2, off);
                        sc -= outsidePenalty * (at(rt, c - 2 * P - g / 2, off) + at(rt, c - 2 * P + g / 2, off)
                                + at(rt, c + 2 * P - g / 2, off) + at(rt, c + 2 * P + g / 2, off));
                        if (sc > bestScore) { bestScore = sc; bestC = c; bestP = P; bestG = g; }
                    }
                }
            }
        }
        if (bestScore <= 0) return;
        gridC = bestC; gridP = bestP;
        java.util.Arrays.fill(res.slotScore, 0);
        double g = bestG;
        for (int k = -2; k <= 2; k++) {
            trackScore[k + 2] = peak(sm, bestC + k * bestP - g / 2, off) + peak(sm, bestC + k * bestP + g / 2, off);
            res.slotScore[k + 2] = trackScore[k + 2];
        }
        for (int k = -1; k <= 1; k++) {
            for (int side = -1; side <= 1; side += 2) {
                Line l = new Line();
                l.xn = bestC + k * bestP + side * g / 2;
                // the same ray at the far row
                l.xf = vx + (l.xn - vx) * (yf - vy) / (yn - vy);
                l.votes = (int) peak(sm, l.xn, off);
                res.lines.add(l);
            }
        }
    }

    private static double at(float[] a, double x, int off) {
        int i = (int) Math.round(x) + off;
        return (i >= 0 && i < a.length) ? a[i] : 0;
    }

    private static double peak(float[] sm, double x, int off) {
        int i = (int) Math.round(x) + off;
        double best = 0;
        for (int d = -2; d <= 2; d++) {
            int j = i + d;
            if (j >= 0 && j < sm.length && sm[j] > best) best = sm[j];
        }
        return best;
    }

    /** Rebuild the six rail lines around the fitted track nearest the screen centre. */
    private void centreOnRunner(Result res, double yn, double yf) {
        double vx = res.vpX, vy = res.vpY;
        double feet = h * runnerFeet;
        double xc = w * (0.5 + runnerOffset);
        double g = res.lines.get(1).xn - res.lines.get(0).xn;
        double best = Double.MAX_VALUE, own = gridC;
        for (int k = -2; k <= 2; k++) {
            double cn = gridC + k * gridP;
            double cf = vx + (cn - vx) * (feet - vy) / (yn - vy);
            double d = Math.abs(cf - xc);
            if (d < best) { best = d; own = cn; }
        }
        res.lines.clear();
        for (int k = -1; k <= 1; k++) {
            for (int side = -1; side <= 1; side += 2) {
                Line l = new Line();
                l.xn = own + k * gridP + side * g / 2;
                l.xf = vx + (l.xn - vx) * (yf - vy) / (yn - vy);
                res.lines.add(l);
            }
        }
        res.ownNear = own;
    }

    private void pairTracks(Result res, double yn, double yf) {
        double feet = h * runnerFeet;
        for (int i = 0; i + 1 < res.lines.size(); i += 2) {
            Track t = new Track();
            t.left = res.lines.get(i); t.right = res.lines.get(i + 1);
            t.centerAtFeet = (t.left.xAt(feet, yn, yf) + t.right.xAt(feet, yn, yf)) / 2;
            res.tracks.add(t);
        }
    }

    // ---- coins and power-ups
    /** Reward is counted between these depths ahead of the runner (closer can't be reached in time). */
    public double rewardNear = 0.45, rewardFar = 3.0;

    /** Coin gold, also the glow around power-ups. */
    public static boolean isGold(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return r > 190 && g > 110 && b < 100 && r - b > 120;
    }

    private void scanRewards(int[] px, Result res, double s) {
        double feet = h * runnerFeet;
        double vx = res.vpX, vy = res.vpY;
        int yTop = (int) Math.ceil(vy + (feet - vy) / (1 + rewardFar));
        int yBot = (int) Math.floor(vy + (feet - vy) / (1 + rewardNear));
        yTop = Math.max(yTop, (int) (h * 0.12));
        double c0 = res.tracks.get(0).centerAtFeet, c1 = res.tracks.get(1).centerAtFeet, c2 = res.tracks.get(2).centerAtFeet;
        double half = Math.abs(c2 - c0) / 4;              // half the lane spacing
        double norm = 1.0 / (s * s);
        for (int y = yTop; y <= yBot && y < h; y++) {
            double k = (feet - vy) / (y - vy);
            double depth = k - 1;
            double wgt = norm / (0.5 + depth);
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (!isGold(px[row + x])) continue;
                double xf = vx + (x - vx) * k;               // where this pixel's track line is at the feet row
                double d0 = Math.abs(xf - c0), d1 = Math.abs(xf - c1), d2 = Math.abs(xf - c2);
                int lane = d0 < d1 ? (d0 < d2 ? 0 : 2) : (d1 < d2 ? 1 : 2);
                double dm = Math.min(d0, Math.min(d1, d2));
                if (dm > half) continue;                     // between lanes or outside the tracks
                res.reward[lane] += wgt;
            }
        }
    }

    /** Is there a rail pixel within +-tol px of x on row y? */
    private boolean railAt(double x, int y, int tol) {
        int xi = (int) Math.round(x);
        if (xi < 0 || xi >= w) return false;
        int row = y * w;
        for (int dx = -tol; dx <= tol; dx++) {
            int xx = xi + dx;
            if (xx >= 0 && xx < w && ridge[row + xx]) return true;
        }
        return false;
    }

    /** Rows beside the runner checked for a train alongside. */
    public double sideTop = 0.60, sideBottom = 0.82;

    /**
     * Before changing lane, the target lane's rail nearest the runner must be visible
     * level with the runner; a train alongside hides it.
     */
    private void checkSides(Track t, double yn, double yf, double s) {
        t.leftSeen = railSeen(t.left, yn, yf, s);
        t.rightSeen = railSeen(t.right, yn, yf, s);
        t.leftRailHidden = t.leftSeen < 0.35;
        t.rightRailHidden = t.rightSeen < 0.35;
    }

    private double railSeen(Line l, double yn, double yf, double s) {
        int tol = Math.max(1, (int) Math.round(2 * s));
        int y0 = (int) (h * sideTop), y1 = (int) (h * sideBottom);
        int margin = Math.max(2, (int) Math.round(3 * s));
        int rows = 0, seen = 0;
        for (int y = y0; y < y1; y += 2) {
            double x = l.xAt(y, yn, yf);
            if (x < margin || x >= w - margin) continue;
            rows++;
            boolean hit = false;
            for (int k = 0; k < 3 && !hit; k++) {
                int yy = y + k;
                if (yy < h && railAt(l.xAt(yy, yn, yf), yy, tol)) hit = true;
            }
            if (hit) seen++;
        }
        return rows < 3 ? 0 : seen / (double) rows;
    }

    private void scanObstacle(Track t, double yn, double yf, double s, Result res) {
        int tol = Math.max(1, (int) Math.round(2 * s));
        int start = (int) (h * scanStart);
        int horizon = (int) Math.max(h * bandTop, res.vpY + (h * scanStart - res.vpY) * 0.18);
        // A row counts as "seen" when either rail shows within a small window of rows (rails
        // are sometimes broken by sleepers and coins). Track missing runs.
        int win = Math.max(2, (int) Math.round(4 * s));
        int missRun = 0, firstMiss = -1;
        int needMiss = Math.max(4, (int) Math.round(10 * s));
        boolean found = false;
        int margin = Math.max(2, (int) Math.round(3 * s));
        // Behind a barrier the rails carry on; behind a train front they don't, though its lit
        // windows can look like rail for a row or two. So "rails resume" needs a solid run.
        int resumeNeed = Math.max(6, (int) Math.round(12 * s));
        int seenRun = 0, lastMissY = -1;
        for (int y = start; y > horizon; y--) {
            double xl = t.left.xAt(y, yn, yf), xr = t.right.xAt(y, yn, yf);
            boolean lOn = xl >= margin && xl < w - margin, rOn = xr >= margin && xr < w - margin;
            if (!lOn && !rOn) continue;              // off screen here: no evidence either way
            boolean seen = false;
            for (int k = 0; k < win && !seen; k++) {
                int yy = y - k;
                if (yy <= 0) break;
                if ((lOn && railAt(t.left.xAt(yy, yn, yf), yy, tol)) || (rOn && railAt(t.right.xAt(yy, yn, yf), yy, tol))) seen = true;
            }
            if (!found) {
                if (!seen) {
                    if (missRun == 0) firstMiss = y;
                    missRun++;
                    if (missRun >= needMiss) {
                        found = true;
                        t.obstacleY = firstMiss;
                        t.blockedAtStart = firstMiss >= start - win;
                        lastMissY = y;
                    }
                } else missRun = 0;
            } else {
                if (seen) {
                    seenRun++;
                    if (seenRun >= resumeNeed) { t.railsResume = true; t.obstacleSpan = firstMiss - lastMissY; return; }
                } else { seenRun = 0; lastMissY = y; }
            }
        }
        if (found) { t.obstacleSpan = firstMiss - Math.max(horizon, lastMissY - 1); t.railsResume = false; }
    }
}
