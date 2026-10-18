package com.nickrocks.pixelflowbot.nono;

import com.nickrocks.pixelflowbot.vision.Frame;

/**
 * Recognises one clue digit by comparing its normalised shape with templates cut from real
 * Nonogram.com screenshots (see tools/nono/). Size-independent, so it works for 5x5 up to 25x25 boards.
 */
public final class Digits {
    static final int GW = 8, GH = 12;

    public static final class Match {
        public int digit;
        public float dist;     // mean squared difference per feature (0 = identical)
        public float second;   // distance to the best *other* digit
        public boolean grey;   // drawn in the light "satisfied" colour
    }

    private static float[][] templates;
    private static int[] labels;

    /** Shape features: GWxGH ink coverage, then aspect ratio (weighted). */
    public static float[] features(Frame f, int x0, int y0, int x1, int y1, boolean[] greyOut) {
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        int minL = 255;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) minL = Math.min(minL, NonoVision.lum(f.px[y * f.w + x]));
        float bg = 238f;
        float span = Math.max(30f, bg - minL);
        if (greyOut != null) greyOut[0] = minL > 110;
        float[] v = new float[GW * GH + 1];
        // Area-average each output cell over the source pixels it covers.
        for (int gy = 0; gy < GH; gy++) {
            float sy0 = y0 + gy * h / (float) GH, sy1 = y0 + (gy + 1) * h / (float) GH;
            for (int gx = 0; gx < GW; gx++) {
                float sx0 = x0 + gx * w / (float) GW, sx1 = x0 + (gx + 1) * w / (float) GW;
                float acc = 0, area = 0;
                for (int y = (int) sy0; y < Math.ceil(sy1); y++) {
                    float oy = Math.min(sy1, y + 1) - Math.max(sy0, y);
                    if (oy <= 0) continue;
                    for (int x = (int) sx0; x < Math.ceil(sx1); x++) {
                        float ox = Math.min(sx1, x + 1) - Math.max(sx0, x);
                        if (ox <= 0) continue;
                        int l = NonoVision.lum(f.px[y * f.w + x]);
                        float ink = Math.max(0f, Math.min(1f, (bg - l) / span));
                        acc += ink * ox * oy;
                        area += ox * oy;
                    }
                }
                v[gy * GW + gx] = area > 0 ? acc / area : 0;
            }
        }
        v[GW * GH] = 3f * w / (float) h;
        return v;
    }

    public static Match classify(Frame f, int x0, int y0, int x1, int y1) {
        boolean[] grey = {false};
        float[] v = features(f, x0, y0, x1, y1, grey);
        Match m = classify(v);
        m.grey = grey[0];
        return m;
    }

    public static Match classify(float[] v) {
        load();
        Match m = new Match();
        m.dist = Float.MAX_VALUE;
        m.second = Float.MAX_VALUE;
        float[] bestPer = new float[10];
        java.util.Arrays.fill(bestPer, Float.MAX_VALUE);
        for (int t = 0; t < templates.length; t++) {
            float d = 0;
            float[] tp = templates[t];
            for (int i = 0; i < v.length; i++) { float e = v[i] - tp[i]; d += e * e; }
            d /= v.length;
            bestPer[labels[t]] = Math.min(bestPer[labels[t]], d);
        }
        for (int dgt = 0; dgt < 10; dgt++) if (bestPer[dgt] < m.dist) { m.dist = bestPer[dgt]; m.digit = dgt; }
        for (int dgt = 0; dgt < 10; dgt++) if (dgt != m.digit && bestPer[dgt] < m.second) m.second = bestPer[dgt];
        if (templates.length == 0) m.dist = 0;
        return m;
    }

    private static synchronized void load() {
        if (templates != null) return;
        String[] rows = DigitData.TEMPLATES;
        templates = new float[rows.length][];
        labels = new int[rows.length];
        for (int i = 0; i < rows.length; i++) {
            String s = rows[i];
            labels[i] = s.charAt(0) - '0';
            float[] v = new float[GW * GH + 1];
            for (int k = 0; k < GW * GH; k++) v[k] = Character.digit(s.charAt(2 + k), 16) / 15f;
            v[GW * GH] = Integer.parseInt(s.substring(2 + GW * GH + 1)) / 100f;
            templates[i] = v;
        }
    }

    /** Encodes a feature vector as "d:<hex shape>:<aspect*100>" for DigitData. */
    public static String encode(int digit, float[] v) {
        StringBuilder sb = new StringBuilder().append(digit).append(':');
        for (int k = 0; k < GW * GH; k++) sb.append(Character.forDigit(Math.round(Math.max(0, Math.min(1, v[k])) * 15), 16));
        sb.append(':').append(Math.round(v[GW * GH] * 100));
        return sb.toString();
    }
}
