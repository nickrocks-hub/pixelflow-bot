package com.nickrocks.pixelflowbot.candy;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes Candy Crush board detection independent of the board's shape.
 *
 * The original detector ({@link CandyVision#detect}, tuned on real screens) finds the lattice from
 * screen rows holding at least 4 separate candies and keeps the biggest cluster of candies (plus
 * neighbours at most 2 cells away). That misses
 *  - narrow boards (fewer than 4 candies across), and
 *  - boards made of separate islands further apart than 2 cells.
 *
 * {@link #expand} keeps the original grid and extends it over the same lattice to every other
 * island of candies, so islands anywhere on the board are included (the gaps become holes).
 * {@link #find} is a fallback for when the original finds nothing: it measures the lattice from
 * all candy pixels, with no minimum width, and takes every island on it.
 *
 * Safety: both stay inside the band of the screen where Candy Crush draws its board (between the
 * goal bar at the top and the booster bar at the bottom), so HUD icons can't become "cells", and
 * cells only count as candies when they look like one candy of one colour.
 */
public final class BoardShape {
    private BoardShape() { }

    /** The board area of the screen (fractions of the height). */
    public static final float BAND_TOP = 0.17f, BAND_BOTTOM = 0.88f;
    public static final int MAX_COLS = 10, MAX_ROWS = 12;

    public static String lastNote = "";

    // ------------------------------------------------------------------ candy cells

    static int R(int p) { return (p >> 16) & 0xff; }
    static int G(int p) { return (p >> 8) & 0xff; }
    static int B(int p) { return p & 0xff; }

    /** Same pixel rule as the original detector: bright and clearly coloured. */
    static boolean candyPixel(int p) {
        int r = R(p), g = G(p), b = B(p);
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return mx >= 160 && (mx - mn) >= 0.45f * mx;
    }

    /** 0 red, 1 orange, 2 yellow, 3 green, 4 blue, 5 purple. */
    static int hueClass(int p) {
        int r = R(p), g = G(p), b = B(p);
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (mx == mn) return -1;
        float h;
        if (mx == r) h = 60f * (((g - b) / (float) (mx - mn)) % 6f);
        else if (mx == g) h = 60f * ((b - r) / (float) (mx - mn) + 2f);
        else h = 60f * ((r - g) / (float) (mx - mn) + 4f);
        if (h < 0) h += 360f;
        if (h < 15 || h >= 335) return 0;
        if (h < 40) return 1;
        if (h < 70) return 2;
        if (h < 165) return 3;
        if (h < 255) return 4;
        return 5;
    }

    /** A frame reduced to about 360 px wide, as a candy-pixel mask with an integral image. */
    static final class Mask {
        final int s, w, h;        // scale, reduced size
        final byte[] m;
        final int[] integral;
        final int[] px;
        final int fw, fh;

        Mask(int[] px, int fw, int fh) {
            this.px = px;
            this.fw = fw;
            this.fh = fh;
            s = Math.max(1, Math.round(fw / 360f));
            w = fw / s;
            h = fh / s;
            m = new byte[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if (candyPixel(px[(y * s + s / 2) * fw + x * s + s / 2])) m[y * w + x] = 1;
            }
            integral = new int[(w + 1) * (h + 1)];
            for (int y = 0; y < h; y++) {
                int run = 0;
                for (int x = 0; x < w; x++) {
                    run += m[y * w + x];
                    integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + run;
                }
            }
        }

        /** Mean of the mask over a box in reduced coordinates. */
        float mean(float x0, float y0, float x1, float y1) {
            int a = Math.max(0, Math.round(x0)), b = Math.max(0, Math.round(y0));
            int c = Math.min(w, Math.round(x1)), d = Math.min(h, Math.round(y1));
            if (c <= a || d <= b) return 0;
            int sum = integral[d * (w + 1) + c] - integral[b * (w + 1) + c] - integral[d * (w + 1) + a] + integral[b * (w + 1) + a];
            return sum / (float) ((c - a) * (d - b));
        }

        /**
         * Does the cell centred at full-resolution (cx, cy) with size {@code cell} hold one candy?
         * Centre mostly candy pixels, the ring around it clearly less (same thresholds as the
         * original detector), and the candy pixels mostly one colour.
         */
        boolean candyCell(float cx, float cy, float cell) {
            float x = cx / s, y = cy / s, c = cell / s;
            float box = mean(x - 0.22f * c, y - 0.22f * c, x + 0.22f * c, y + 0.22f * c);
            if (box < 0.28f) return false;
            float outer = 0.5f * c, inner = 0.4f * c;
            float aO = 4 * outer * outer, aI = 4 * inner * inner;
            float ring = Math.max(0f, (mean(x - outer, y - outer, x + outer, y + outer) * aO - mean(x - inner, y - inner, x + inner, y + inner) * aI) / (aO - aI));
            if (ring > box - 0.18f) return false;
            int[] count = new int[6];
            int total = 0;
            int r = Math.max(2, Math.round(0.25f * cell));
            int step = Math.max(1, r / 6);
            for (int yy = Math.round(cy) - r; yy <= Math.round(cy) + r; yy += step) {
                for (int xx = Math.round(cx) - r; xx <= Math.round(cx) + r; xx += step) {
                    if (xx < 0 || yy < 0 || xx >= fw || yy >= fh) continue;
                    int p = px[yy * fw + xx];
                    if (!candyPixel(p)) continue;
                    int k = hueClass(p);
                    if (k < 0) continue;
                    count[k]++;
                    total++;
                }
            }
            if (total < 6) return false;
            int best = 0;
            for (int v : count) best = Math.max(best, v);
            return best >= 0.55f * total;
        }
    }

    // ------------------------------------------------------------------ islands on a lattice

    /** Candy cells of a lattice (centres x0 + k·cell, y0 + j·cell) inside the board band. */
    private static final class Lattice {
        float x0, y0, cell;
        int kMin, kMax, jMin, jMax;   // lattice index range covering the band
        boolean[] candy;              // [(j - jMin) * nk + (k - kMin)]
        int nk, nj;

        Lattice(Mask mk, float x0, float y0, float cell) {
            this.x0 = x0; this.y0 = y0; this.cell = cell;
            kMin = (int) Math.ceil((cell * 0.5f - x0) / cell);
            kMax = (int) Math.floor((mk.fw - cell * 0.5f - x0) / cell);
            jMin = (int) Math.ceil((mk.fh * BAND_TOP + cell * 0.5f - y0) / cell);
            jMax = (int) Math.floor((mk.fh * BAND_BOTTOM - cell * 0.5f - y0) / cell);
            nk = Math.max(0, kMax - kMin + 1);
            nj = Math.max(0, jMax - jMin + 1);
            candy = new boolean[nk * nj];
            for (int j = 0; j < nj; j++) for (int k = 0; k < nk; k++) {
                candy[j * nk + k] = mk.candyCell(x0 + (k + kMin) * cell, y0 + (j + jMin) * cell, cell);
            }
        }

        /** Connected groups of candy cells: {size, kLo, kHi, jLo, jHi} in lattice indices. */
        List<int[]> islands() {
            List<int[]> out = new ArrayList<>();
            int[] lab = new int[candy.length];
            int[] stack = new int[candy.length];
            for (int i = 0; i < candy.length; i++) {
                if (!candy[i] || lab[i] != 0) continue;
                int id = out.size() + 1, sp = 0, n = 0;
                int kLo = Integer.MAX_VALUE, kHi = Integer.MIN_VALUE, jLo = Integer.MAX_VALUE, jHi = Integer.MIN_VALUE;
                stack[sp++] = i;
                lab[i] = id;
                while (sp > 0) {
                    int c = stack[--sp], k = c % nk, j = c / nk;
                    n++;
                    kLo = Math.min(kLo, k); kHi = Math.max(kHi, k); jLo = Math.min(jLo, j); jHi = Math.max(jHi, j);
                    int[] nb = {k > 0 ? c - 1 : -1, k < nk - 1 ? c + 1 : -1, j > 0 ? c - nk : -1, j < nj - 1 ? c + nk : -1};
                    for (int q : nb) if (q >= 0 && candy[q] && lab[q] == 0) { lab[q] = id; stack[sp++] = q; }
                }
                out.add(new int[]{n, kLo + kMin, kHi + kMin, jLo + jMin, jHi + jMin});
            }
            return out;
        }
    }

    /**
     * The original grid, grown to include every other island of at least 3 candies on the same
     * lattice inside the board band (keeps the board within {@link #MAX_COLS}×{@link #MAX_ROWS}).
     * Returns the input unchanged when there is nothing to add.
     */
    public static CandyVision.Grid expand(int[] px, int w, int h, CandyVision.Grid g) {
        if (g == null || g.cell < 8) return g;
        Mask mk = new Mask(px, w, h);
        Lattice lt = new Lattice(mk, g.x0, g.y0, g.cell);
        int kLo = 0, kHi = g.cols - 1, jLo = 0, jHi = g.rows - 1;
        int added = 0;
        List<int[]> isl = lt.islands();
        isl.sort((a, b) -> b[0] - a[0]);
        for (int[] is : isl) {
            if (is[0] < 3) continue;
            boolean inside = is[1] >= kLo && is[2] <= kHi && is[3] >= jLo && is[4] <= jHi;
            if (inside) continue;
            int nkLo = Math.min(kLo, is[1]), nkHi = Math.max(kHi, is[2]), njLo = Math.min(jLo, is[3]), njHi = Math.max(jHi, is[4]);
            if (nkHi - nkLo + 1 > MAX_COLS || njHi - njLo + 1 > MAX_ROWS) continue;
            kLo = nkLo; kHi = nkHi; jLo = njLo; jHi = njHi;
            added++;
        }
        if (added == 0) { lastNote = "shape: nothing to add"; return g; }
        CandyVision.Grid out = new CandyVision.Grid(g.x0 + kLo * g.cell, g.y0 + jLo * g.cell, g.cell, jHi - jLo + 1, kHi - kLo + 1, g.source + "+islands");
        out.quality = g.quality;
        lastNote = "shape: added " + added + " island(s) → " + out;
        return out;
    }

    /**
     * Fallback when the original detector finds no board (e.g. a board only 2–3 candies wide):
     * lattice from the comb of all candy pixels in the board band, then all islands on it.
     */
    public static CandyVision.Grid find(int[] px, int w, int h) {
        Mask mk = new Mask(px, w, h);
        int y0 = Math.round(mk.h * BAND_TOP), y1 = Math.round(mk.h * BAND_BOTTOM);
        float[] colProf = new float[mk.w];
        for (int y = y0; y < y1; y++) for (int x = 0; x < mk.w; x++) colProf[x] += mk.m[y * mk.w + x];
        float[] cx = comb(colProf, mk.w / 11.5f, mk.w / 4.5f, 0.25f);
        if (cx == null) { lastNote = "shape: no lattice (x)"; return null; }
        float period = cx[0];
        float[] rowProf = new float[mk.h];
        for (int y = y0; y < y1; y++) for (int x = 0; x < mk.w; x++) rowProf[y] += mk.m[y * mk.w + x];
        float[] cy = comb(rowProf, 0.94f * period, 1.06f * period, 0.1f);
        if (cy == null) { lastNote = "shape: no lattice (y)"; return null; }
        float cell = (cx[0] + cy[0]) * 0.5f * mk.s;
        float fx0 = (cx[1] + 0.5f) * mk.s, fy0 = (cy[1] + 0.5f) * mk.s;
        Lattice lt = new Lattice(mk, fx0, fy0, cell);
        List<int[]> isl = lt.islands();
        isl.sort((a, b) -> b[0] - a[0]);
        if (isl.isEmpty() || isl.get(0)[0] < 6) { lastNote = "shape: no cluster of 6+ candies"; return null; }
        int[] b = isl.get(0).clone();
        for (int i = 1; i < isl.size(); i++) {
            int[] is = isl.get(i);
            if (is[0] < 3) continue;
            int kLo = Math.min(b[1], is[1]), kHi = Math.max(b[2], is[2]), jLo = Math.min(b[3], is[3]), jHi = Math.max(b[4], is[4]);
            if (kHi - kLo + 1 > MAX_COLS || jHi - jLo + 1 > MAX_ROWS) continue;
            b[1] = kLo; b[2] = kHi; b[3] = jLo; b[4] = jHi;
        }
        int cols = b[2] - b[1] + 1, rows = b[4] - b[3] + 1;
        if (cols < 2 || rows < 2) { lastNote = "shape: board too small"; return null; }
        CandyVision.Grid g = new CandyVision.Grid(fx0 + b[1] * cell, fy0 + b[3] * cell, cell, rows, cols, "shape");
        lastNote = "shape: found " + g;
        return g;
    }

    /** Best (period, phase) where profile peaks at phase + k·period and dips half-way between. */
    static float[] comb(float[] prof, float pMin, float pMax, float pStep) {
        int n = prof.length;
        float best = 0;
        float[] out = null;
        for (float p = pMin; p <= pMax; p += pStep) {
            for (float ph = 0; ph < p; ph += 0.5f) {
                float sum = 0;
                int cnt = 0;
                for (float t = ph; t + 0.5f * p < n; t += p) {
                    sum += interp(prof, t) - interp(prof, t + 0.5f * p);
                    cnt++;
                }
                if (cnt < 2) continue;
                float sc = sum / (float) Math.sqrt(cnt);
                if (sc > best) { best = sc; out = new float[]{p, ph}; }
            }
        }
        return out;
    }

    static float interp(float[] a, float t) {
        int i = (int) Math.floor(t);
        if (i < 0) return a[0];
        if (i >= a.length - 1) return a[a.length - 1];
        float f = t - i;
        return a[i] * (1 - f) + a[i + 1] * f;
    }
}
