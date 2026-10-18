package com.nickrocks.pixelflowbot.nono;

import com.nickrocks.pixelflowbot.vision.Frame;

/**
 * Finds Nonogram.com's "Next Level" button on the "Level Completed!" screen: a wide white pill
 * (about 63% of the screen wide, 5% high) on the bright blue celebration background, low on the
 * screen. Popups (Rate Game, tutorial notes) dim the background to dark blue, so the button is
 * deliberately NOT found while one is open.
 */
public final class NextButton {
    private NextButton() { }

    static boolean white(int p) {
        return ((p >> 16) & 0xff) > 238 && ((p >> 8) & 0xff) > 238 && (p & 0xff) > 238;
    }

    /** The celebration background: saturated, bright blue. */
    static boolean brightBlue(int p) {
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        return b > 200 && b - r > 120 && g > 90 && g < 200;
    }

    /** Centre {x, y} of the button, or null. */
    public static float[] find(Frame f) {
        int w = f.w, h = f.h;
        int bandStart = -1, bandEnd = -1, x0 = 0, x1 = 0, gapRows = 0;
        int[] found = null;
        for (int y = (int) (h * 0.55f); y < (int) (h * 0.97f); y += 2) {
            int best = 0, bestStart = 0, run = 0, start = 0;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                if (white(f.px[row + x])) {
                    if (run == 0) start = x;
                    if (++run > best) { best = run; bestStart = start; }
                } else run = 0;
            }
            boolean pillRow = best > w * 0.45f && best < w * 0.82f
                    && Math.abs(bestStart + best / 2f - w / 2f) < w * 0.07f;
            if (pillRow) {
                if (bandStart < 0) { bandStart = y; x0 = bestStart; x1 = bestStart + best; }
                bandEnd = y;
                x0 = Math.min(x0, bestStart);
                x1 = Math.max(x1, bestStart + best);
                gapRows = 0;
            } else if (bandStart >= 0) {
                // Text rows inside the pill break the run; allow a short gap.
                if (++gapRows > (int) (h * 0.03f / 2)) {
                    found = check(f, bandStart, bandEnd, x0, x1);
                    if (found != null) break;
                    bandStart = -1;
                    gapRows = 0;
                }
            }
        }
        if (found == null && bandStart >= 0) found = check(f, bandStart, bandEnd, x0, x1);
        return found == null ? null : new float[]{found[0], found[1]};
    }

    private static int[] check(Frame f, int y0, int y1, int x0, int x1) {
        int w = f.w, h = f.h;
        int bh = y1 - y0;
        if (bh < h * 0.03f || bh > h * 0.08f) return null;
        int ym = (y0 + y1) / 2;
        // Blue on both sides of the pill and just above it.
        int[][] probes = {
                {x0 - (int) (w * 0.03f), ym}, {x1 + (int) (w * 0.03f), ym},
                {(x0 + x1) / 2, y0 - (int) (h * 0.02f)}, {x0 + (x1 - x0) / 4, y0 - (int) (h * 0.02f)},
        };
        int blue = 0;
        for (int[] p : probes) {
            if (p[0] < 0 || p[0] >= w || p[1] < 0) continue;
            if (brightBlue(f.px[p[1] * w + p[0]])) blue++;
        }
        if (blue < 3) return null;
        // Blue label text inside the pill ("Next Level").
        int ink = 0;
        for (int y = y0; y <= y1; y += 2) {
            for (int x = x0 + (x1 - x0) / 4; x < x1 - (x1 - x0) / 4; x += 2) {
                int p = f.px[y * w + x];
                if ((p & 0xff) > 150 && ((p >> 16) & 0xff) < 160) ink++;
            }
        }
        if (ink < 10) return null;
        return new int[]{(x0 + x1) / 2, ym};
    }
}
