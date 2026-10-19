package com.riderbot.app.core;

/**
 * Pure-Java image analysis for the game "Rider".
 *
 * Input frames are packed 0xRRGGBB ints (alpha ignored), already downscaled
 * (the capture service records at ~360 px wide). All distances are scaled by
 * the frame width, so other capture sizes work too.
 *
 * What it finds:
 *  - the car: the largest compact blob of saturated mid-green (the car body),
 *    and its long axis via PCA (an angle modulo 180 degrees);
 *  - the track: the bright, tinted core of the neon line (magenta or green);
 *    pure white (wheels, trails, score text) is not track.
 */
public final class RiderVision {

    /** One detected car. axisDeg is in (-90, 90], counter-clockwise, screen space. */
    public static final class Car {
        public double cx, cy;
        public double axisDeg;
        public double length;
        public int area;
        public double elongation;
    }

    private int w, h;
    private int[] labels = new int[0];
    private int[] stack = new int[0];

    /** Consecutive track pixels needed to accept a hit (neon line core is 5+ px thick at 360 wide). */
    static final int MIN_RUN = 3;

    /** Fraction of the screen height at the top that holds the score HUD. */
    public static final double HUD_FRACTION = 0.11;

    public static boolean isCarColor(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return g > 95 && g < 215 && g > r + 50 && g > b + 40;
    }

    public static boolean isTrackColor(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        return mx > 215 && mn > 140 && (mx - mn) >= 18;
    }

    /**
     * Finds the car. prevX/prevY (or NaN) bias the choice toward the last position.
     */
    public Car findCar(int[] px, int width, int height, double prevX, double prevY) {
        return findCar(px, width, height, prevX, prevY, Double.POSITIVE_INFINITY);
    }

    /** As above, but ignores blobs further than maxJump px from (prevX, prevY). */
    public Car findCar(int[] px, int width, int height, double prevX, double prevY, double maxJump) {
        ensure(width, height);
        final int n = width * height;
        final double s = width / 360.0;
        final int top = (int) (height * HUD_FRACTION);
        java.util.Arrays.fill(labels, 0);

        Car best = null;
        double bestScore = -1e18;
        int label = 0;
        for (int y = top; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int i = row + x;
                if (labels[i] != 0 || !isCarColor(px[i])) continue;
                label++;
                // flood fill (8-connected) collecting moments
                int sp = 0;
                stack[sp++] = i;
                labels[i] = label;
                long cnt = 0;
                double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
                int minX = x, maxX = x, minY = y, maxY = y;
                while (sp > 0) {
                    int p = stack[--sp];
                    int py = p / width, pxx = p - py * width;
                    cnt++;
                    sx += pxx; sy += py;
                    sxx += (double) pxx * pxx; syy += (double) py * py; sxy += (double) pxx * py;
                    if (pxx < minX) minX = pxx; if (pxx > maxX) maxX = pxx;
                    if (py < minY) minY = py; if (py > maxY) maxY = py;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = py + dy;
                        if (yy < top || yy >= height) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = pxx + dx;
                            if (xx < 0 || xx >= width) continue;
                            int q = yy * width + xx;
                            if (labels[q] == 0 && isCarColor(px[q])) {
                                labels[q] = label;
                                if (sp < n) stack[sp++] = q;
                            }
                        }
                    }
                }
                int bw = maxX - minX + 1, bh = maxY - minY + 1;
                int big = Math.max(bw, bh);
                if (big > 70 * s || big < 10 * s || cnt < 35 * s * s) continue;
                double mx = sx / cnt, my = sy / cnt;
                double score = cnt;
                if (!Double.isNaN(prevX)) {
                    double dist = Math.hypot(mx - prevX, my - prevY);
                    if (dist > maxJump) continue;
                    score -= 1.5 * dist / s;
                }
                if (score <= bestScore) continue;
                double cxx = sxx / cnt - mx * mx, cyy = syy / cnt - my * my, cxy = sxy / cnt - mx * my;
                double half = (cxx + cyy) / 2, disc = Math.sqrt(((cxx - cyy) / 2) * ((cxx - cyy) / 2) + cxy * cxy);
                double l1 = half + disc, l2 = Math.max(half - disc, 1e-3);
                if (Math.sqrt(l1 / l2) < 2.2) continue; // the car is long and thin; pickups are square
                double ang = -Math.toDegrees(0.5 * Math.atan2(2 * cxy, cxx - cyy)); // y-down -> CCW
                Car c = new Car();
                c.cx = mx; c.cy = my; c.axisDeg = ang; c.area = (int) cnt;
                c.elongation = Math.sqrt(l1 / l2);
                c.length = Math.max(18 * s, Math.sqrt(cnt * c.elongation) * 1.1);
                best = c;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Counts track pixels in the band just beyond the wheels, for a car facing
     * thetaDeg (full 0-360 orientation, CCW). Wheels are on the car's "down" side.
     */
    public int wheelSideTrack(int[] px, int width, int height, Car car, double thetaDeg) {
        double L = car.length;
        double a = Math.toRadians(thetaDeg);
        // facing (screen, y down) and car-down vectors
        double fx = Math.cos(a), fy = -Math.sin(a);
        double dx = Math.sin(a), dy = Math.cos(a);
        int count = 0;
        int steps = (int) Math.ceil(L);
        for (int i = -steps / 2; i <= steps / 2; i++) {
            double u = i;               // along the car
            int run = 0;
            for (double v = 0.33 * L; v <= 0.80 * L; v += 1.0) {
                int x = (int) Math.round(car.cx + fx * u + dx * v);
                int y = (int) Math.round(car.cy + fy * u + dy * v);
                boolean t = x >= 0 && y >= 0 && x < width && y < height && isTrackColor(px[y * width + x]);
                run = t ? run + 1 : 0;
                if (run >= MIN_RUN) { count++; break; } // a real line is thick; trails/sparks are thin
            }
        }
        return count; // number of along-car samples that see track under the wheels
    }

    /** Vertical screen distance from the car centre down to the first track pixel, or -1. */
    public double trackBelow(int[] px, int width, int height, double cx, double startY) {
        int x = (int) Math.round(cx);
        if (x < 1 || x >= width - 1) return -1;
        int run = 0;
        for (int y = Math.max(0, (int) startY); y < height; y++) {
            int row = y * width;
            boolean t = isTrackColor(px[row + x]) || isTrackColor(px[row + x - 1]) || isTrackColor(px[row + x + 1]);
            run = t ? run + 1 : 0;
            if (run >= MIN_RUN) return y - MIN_RUN + 1;
        }
        return -1;
    }

    /** Slope (degrees, CCW) of the track below the car, or NaN. */
    public double slopeBelow(int[] px, int width, int height, Car car) {
        double off = 0.45 * car.length;
        double start = car.cy + 0.3 * car.length;
        double ya = trackBelow(px, width, height, car.cx - off, start);
        double yb = trackBelow(px, width, height, car.cx + off, start);
        if (ya < 0 || yb < 0 || Math.abs(ya - yb) > 1.6 * car.length) return Double.NaN;
        return -Math.toDegrees(Math.atan2(yb - ya, 2 * off));
    }

    private void ensure(int width, int height) {
        if (width == w && height == h) return;
        w = width; h = height;
        labels = new int[width * height];
        stack = new int[width * height];
    }
}
