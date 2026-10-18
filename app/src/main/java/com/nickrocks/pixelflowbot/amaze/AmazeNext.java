package com.nickrocks.pixelflowbot.amaze;

import com.nickrocks.pixelflowbot.vision.Frame;

/**
 * Finds what to press between Amaze GO levels:
 *  - the "Next Level" / "Continue" pill under the Flawless! card or the Daily Streak
 *    (solid brown-orange (190,116,55) or purple (133,73,170), white label, warm peach background);
 *  - the grey sun on the "Tap the Sun, Light up your streak" screen.
 * Faded (disabled) buttons are pale and are ignored, so it waits until the game enables them.
 */
public final class AmazeNext {
    private AmazeNext() { }

    static boolean brownBtn(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return r >= 165 && r <= 215 && g >= 95 && g <= 140 && b >= 30 && b <= 85 && r - b > 100;
    }

    static boolean purpleBtn(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return r >= 105 && r <= 165 && g >= 45 && g <= 105 && b >= 145 && b <= 210 && b - g > 70;
    }

    static boolean warm(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return r > 195 && r >= g && g >= b && r - b > 25;
    }

    static boolean white(int p) {
        return ((p >> 16) & 0xff) > 232 && ((p >> 8) & 0xff) > 232 && (p & 0xff) > 225;
    }

    /** Point to tap {x, y, kind} (kind 0 = button, 1 = sun), or null if nothing to press. */
    public static float[] find(Frame f) {
        float[] b = button(f);
        if (b != null) return b;
        return sun(f);
    }

    static float[] button(Frame f) {
        int w = f.w, h = f.h;
        for (int pass = 0; pass < 2; pass++) {
            int bandStart = -1, bandEnd = -1, x0 = 0, x1 = 0, gap = 0;
            for (int y = (int) (h * 0.55f); y < (int) (h * 0.93f); y += 2) {
                int best = 0, bestStart = 0, run = 0, start = 0, row = y * w;
                for (int x = 0; x < w; x++) {
                    int p = f.px[row + x];
                    if (pass == 0 ? brownBtn(p) : purpleBtn(p)) {
                        if (run == 0) start = x;
                        if (++run > best) { best = run; bestStart = start; }
                    } else run = 0;
                }
                boolean pill = best > w * 0.06f && Math.abs(bestStart + best / 2f - w / 2f) < w * 0.1f;
                if (pill) {
                    if (bandStart < 0) { bandStart = y; x0 = bestStart; x1 = bestStart + best; }
                    bandEnd = y;
                    x0 = Math.min(x0, bestStart);
                    x1 = Math.max(x1, bestStart + best);
                    gap = 0;
                } else if (bandStart >= 0 && ++gap > (int) (h * 0.03f / 2)) {   // label rows break the run
                    float[] r = check(f, bandStart, bandEnd, x0, x1);
                    if (r != null) return r;
                    bandStart = -1;
                    gap = 0;
                }
            }
        }
        return null;
    }

    private static float[] check(Frame f, int y0, int y1, int x0, int x1) {
        int w = f.w, h = f.h;
        int bw = x1 - x0, bh = y1 - y0;
        if (bw < w * 0.25f || bw > w * 0.85f || bh < h * 0.025f || bh > h * 0.09f) return null;
        // White label inside.
        int text = 0;
        for (int y = y0; y <= y1; y += 2) for (int x = x0; x < x1; x += 2) if (white(f.px[y * w + x])) text++;
        if (text < 15) return null;
        // Warm celebration background around it (not an ad's).
        int warm = 0, n = 0;
        int ym = (y0 + y1) / 2;
        int[][] probes = {{x0 - w / 20, ym}, {x1 + w / 20, ym}, {(x0 + x1) / 2, y0 - h / 40}, {(x0 + x1) / 2, y1 + h / 40}};
        for (int[] p : probes) {
            if (p[0] < 0 || p[0] >= w || p[1] < 0 || p[1] >= h) continue;
            n++;
            if (warm(f.px[p[1] * w + p[0]])) warm++;
        }
        if (warm < 3) return null;
        return new float[]{(x0 + x1) / 2f, ym, 0};
    }

    /** Grey sun disc in the middle of a warm screen. */
    static float[] sun(Frame f) {
        int w = f.w, h = f.h;
        long sx = 0, sy = 0;
        int n = 0, minX = w, maxX = 0, minY = h, maxY = 0;
        int ya = (int) (h * 0.25f), yb = (int) (h * 0.55f), xa = (int) (w * 0.25f), xb = (int) (w * 0.75f);
        for (int y = ya; y < yb; y += 2) {
            for (int x = xa; x < xb; x += 2) {
                int p = f.px[y * w + x];
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                if (mx - mn <= 12 && mx >= 150 && mx <= 215) {
                    sx += x; sy += y; n++;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }
        int bw = maxX - minX, bh = maxY - minY;
        if (n < 400 || bw < w * 0.1f || bw > w * 0.5f || bh < bw * 0.75f || bh > bw * 1.3f) return null;
        // Filled disc, not scattered grey.
        if (n < (bw / 2f) * (bh / 2f) * 0.45f) return null;
        // Warm background around it.
        int cx = (int) (sx / n), cy = (int) (sy / n);
        int warm = 0;
        int[][] probes = {{cx - bw, cy}, {cx + bw, cy}, {cx, cy - bh}, {cx, cy + bh}};
        for (int[] p : probes) if (p[0] >= 0 && p[0] < w && p[1] >= 0 && p[1] < h && warm(f.px[p[1] * w + p[0]])) warm++;
        if (warm < 3) return null;
        return new float[]{cx, cy, 1};
    }
}
