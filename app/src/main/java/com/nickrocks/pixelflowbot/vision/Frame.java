package com.nickrocks.pixelflowbot.vision;

/** A screen frame as packed ARGB ints. No Android dependencies, so it can be tested on a computer. */
public final class Frame {
    public final int w, h;
    public final int[] px;
    /** Scale from the 1080x2520 layout the positions were measured on. */
    public final float sx, sy;

    public Frame(int w, int h, int[] px) {
        this.w = w; this.h = h; this.px = px;
        this.sx = w / 1080f; this.sy = h / 2520f;
    }

    public int r(int x, int y) { return (px[y * w + x] >> 16) & 0xff; }
    public int g(int x, int y) { return (px[y * w + x] >> 8) & 0xff; }
    public int b(int x, int y) { return px[y * w + x] & 0xff; }

    public int X(float layoutX) { return clamp(Math.round(layoutX * sx), 0, w - 1); }
    public int Y(float layoutY) { return clamp(Math.round(layoutY * sy), 0, h - 1); }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Median R,G,B of a rectangle (x0..x1, y0..y1 exclusive), clamped to the frame. */
    public int[] medianRgb(int x0, int y0, int x1, int y1) {
        x0 = clamp(x0, 0, w - 1); x1 = clamp(x1, x0 + 1, w);
        y0 = clamp(y0, 0, h - 1); y1 = clamp(y1, y0 + 1, h);
        int[] hr = new int[256], hg = new int[256], hb = new int[256];
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = px[y * w + x];
                hr[(p >> 16) & 0xff]++; hg[(p >> 8) & 0xff]++; hb[p & 0xff]++; n++;
            }
        }
        return new int[]{median(hr, n), median(hg, n), median(hb, n)};
    }

    private static int median(int[] hist, int n) {
        int half = (n + 1) / 2, acc = 0;
        for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc >= half) return i; }
        return 255;
    }
}
