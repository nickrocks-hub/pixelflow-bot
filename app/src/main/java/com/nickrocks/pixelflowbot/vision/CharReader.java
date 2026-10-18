package com.nickrocks.pixelflowbot.vision;

import java.util.ArrayList;
import java.util.List;

/** Reads characters (colour + shot count) in the waiting slots and the queue's front row, plus the belt counter. */
public final class CharReader {
    // Layout positions on a 1080x2520 screen (measured from recordings).
    public static final float[] SLOT_X = {130, 336, 540, 746, 950};
    public static final float SLOT_Y = 1725;
    /** Front-row height of the queue. The number of columns varies by level (4 in L24-25, 3 in L26). */
    public static final float QUEUE_Y = 1937;

    public static final class Character {
        public final GameColor color;
        public final int shots;          // 0 when hidden
        public final boolean hidden;     // "?" character: colour and shots unknown until it is sent
        public final float x;            // layout x of the character's centre (where to tap)
        Character(GameColor color, int shots, boolean hidden, float x) {
            this.color = color; this.shots = shots; this.hidden = hidden; this.x = x;
        }
        public String code() { return hidden ? "?" : color.code + "" + shots; }
        @Override public String toString() { return hidden ? "hidden ?" : color.name().toLowerCase() + " " + shots; }
    }

    private static final int TW = 16, TH = 24;
    private static final char[] T_LABEL;
    private static final float[][] T_DATA;
    static {
        int n = DigitTemplates.DATA.length;
        T_LABEL = new char[n]; T_DATA = new float[n][TW * TH];
        for (int i = 0; i < n; i++) {
            String s = DigitTemplates.DATA[i];
            T_LABEL[i] = s.charAt(0);
            for (int k = 0; k < TW * TH; k++)
                T_DATA[i][k] = Integer.parseInt(s.substring(1 + 2 * k, 3 + 2 * k), 16) / 255f;
        }
    }

    public static Character[] readSlots(Frame f) {
        Character[] out = new Character[SLOT_X.length];
        for (int i = 0; i < out.length; i++) out[i] = readAt(f, SLOT_X[i], SLOT_Y);
        return out;
    }

    /**
     * Scans the whole front row for numbers (or "?"), left to right, so any number of
     * columns works. Glyphs closer than about one glyph width belong to the same number.
     */
    public static Character[] readQueue(Frame f) {
        int y0 = f.Y(QUEUE_Y - 40), y1 = f.Y(QUEUE_Y + 40);
        List<int[]> gl = glyphs(f, f.X(10), y0, f.X(1070), y1);   // {minX, maxX, char}
        List<Character> out = new ArrayList<>();
        int i = 0;
        while (i < gl.size()) {
            int j = i;
            while (j + 1 < gl.size() && gl.get(j + 1)[0] - gl.get(j)[1] < 25 * f.sx) j++;
            StringBuilder num = new StringBuilder();
            boolean unknown = false;
            for (int k = i; k <= j; k++) {
                char ch = (char) gl.get(k)[2];
                if (ch == 'x') unknown = true; else num.append(ch);
            }
            float cxPx = (gl.get(i)[0] + gl.get(j)[1]) / 2f;
            Character c = makeChar(f, cxPx / f.sx, QUEUE_Y, num.toString(), unknown);
            if (c != null) out.add(c);
            i = j + 1;
        }
        return out.toArray(new Character[0]);
    }

    static Character makeChar(Frame f, float lx, float ly, String num, boolean unknown) {
        int cx = f.X(lx), cy = f.Y(ly);
        int[] m = f.medianRgb(cx - Math.round(50 * f.sx), cy + Math.round(45 * f.sy),
                cx + Math.round(50 * f.sx), cy + Math.round(95 * f.sy));
        GameColor col = GameColor.classify(m[0], m[1], m[2]);
        if (col == GameColor.EMPTY) return null;
        // Stray shapes (e.g. the shine on white characters) are ignored when real digits are present.
        if (num.length() >= 1 && num.length() <= 2)
            return new Character(col, Integer.parseInt(num), false, lx);
        if (num.length() == 0 && unknown) return new Character(col, 0, true, lx);   // "?"
        return null;
    }

    /** Free belt spaces from the "x/5" counter, or -1 if unreadable. Red counter = 0. */
    public static int readBeltFree(Frame f) {
        int x0 = f.X(5), y0 = f.Y(1565), x1 = f.X(140), y1 = f.Y(1630);
        List<String> d = digits(f, x0, y0, x1, y1);
        if (d.size() >= 2 && d.get(d.size() - 1).equals("5") && !d.get(0).equals("x"))
            return Integer.parseInt(d.get(0));
        int red = 0, tot = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            tot++;
            if (f.r(x, y) > 180 && f.g(x, y) < 90 && f.b(x, y) < 90) red++;
        }
        return red > tot * 0.03 ? 0 : -1;
    }

    static Character readAt(Frame f, float lx, float ly) {
        StringBuilder num = new StringBuilder();
        boolean unknown = false;
        for (String d : digits(f, f.X(lx - 75), f.Y(ly - 40), f.X(lx + 75), f.Y(ly + 40))) {
            if (d.equals("x")) unknown = true; else num.append(d);
        }
        return makeChar(f, lx, ly, num.toString(), unknown);
    }

    static List<String> digits(Frame f, int x0, int y0, int x1, int y1) {
        List<String> out = new ArrayList<>();
        for (int[] g : glyphs(f, x0, y0, x1, y1)) out.add(String.valueOf((char) g[2]));
        return out;
    }

    /** White glyphs enclosed by a dark outline, left to right: {screen minX, screen maxX, char ('x' = not a digit)}. */
    static List<int[]> glyphs(Frame f, int x0, int y0, int x1, int y1) {
        int W = x1 - x0, H = y1 - y0;
        boolean[] white = new boolean[W * H], dark = new boolean[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int r = f.r(x0 + x, y0 + y), g = f.g(x0 + x, y0 + y), b = f.b(x0 + x, y0 + y);
            int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            white[y * W + x] = mn > 200 && (mx - mn) < 45;
            dark[y * W + x] = mx < 90;
        }
        int[] label = new int[W * H];
        int next = 0;
        List<int[]> found = new ArrayList<>(); // {minX, minY, maxX, maxY, id}
        int[] stack = new int[W * H];
        for (int i = 0; i < W * H; i++) {
            if (!white[i] || label[i] != 0) continue;
            next++;
            int sp = 0; stack[sp++] = i; label[i] = next;
            int minX = W, minY = H, maxX = -1, maxY = -1;
            while (sp > 0) {
                int p = stack[--sp], px = p % W, py = p / W;
                if (px < minX) minX = px; if (px > maxX) maxX = px;
                if (py < minY) minY = py; if (py > maxY) maxY = py;
                int[] nb = {p - 1, p + 1, p - W, p + W};
                boolean[] ok = {px > 0, px < W - 1, py > 0, py < H - 1};
                for (int k = 0; k < 4; k++) {
                    if (ok[k] && white[nb[k]] && label[nb[k]] == 0) { label[nb[k]] = next; stack[sp++] = nb[k]; }
                }
            }
            found.add(new int[]{minX, minY, maxX, maxY, next});
        }
        float s = f.sy;
        List<int[]> keep = new ArrayList<>();
        for (int[] b : found) {
            int h = b[3] - b[1] + 1, w = b[2] - b[0] + 1;
            if (h < 20 * s || h > 60 * s || w < 6 * s || w > 45 * s) continue;
            if (b[0] == 0 || b[1] == 0 || b[2] == W - 1 || b[3] == H - 1) continue;
            if (ringDarkFraction(label, dark, W, H, b) < 0.5f) continue;
            keep.add(b);
        }
        keep.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] b : keep) out.add(new int[]{x0 + b[0], x0 + b[2], classify(label, W, b)});
        return out;
    }

    /** Fraction of dark pixels 2-3 px outside the blob (the outline). */
    private static float ringDarkFraction(int[] label, boolean[] dark, int W, int H, int[] b) {
        int id = b[4], darkN = 0, n = 0;
        for (int y = Math.max(0, b[1] - 3); y <= Math.min(H - 1, b[3] + 3); y++) {
            for (int x = Math.max(0, b[0] - 3); x <= Math.min(W - 1, b[2] + 3); x++) {
                if (label[y * W + x] == id) continue;
                int d = distToBlob(label, W, H, x, y, id, 3);
                if (d >= 2 && d <= 3) { n++; if (dark[y * W + x]) darkN++; }
            }
        }
        return n == 0 ? 0 : darkN / (float) n;
    }

    /** Chebyshev distance (up to max) from (x,y) to the nearest blob pixel; max+1 if farther. */
    private static int distToBlob(int[] label, int W, int H, int x, int y, int id, int max) {
        for (int d = 1; d <= max; d++) {
            for (int yy = y - d; yy <= y + d; yy++) for (int xx = x - d; xx <= x + d; xx++) {
                if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                if (Math.max(Math.abs(xx - x), Math.abs(yy - y)) != d) continue;
                if (label[yy * W + xx] == id) return d;
            }
        }
        return max + 1;
    }

    /** Area-average the blob mask to 16x24 and pick the nearest template. */
    private static char classify(int[] label, int W, int[] b) {
        int id = b[4], w = b[2] - b[0] + 1, h = b[3] - b[1] + 1;
        float[] v = new float[TW * TH];
        for (int oy = 0; oy < TH; oy++) {
            float sy0 = oy * h / (float) TH, sy1 = (oy + 1) * h / (float) TH;
            for (int ox = 0; ox < TW; ox++) {
                float sx0 = ox * w / (float) TW, sx1 = (ox + 1) * w / (float) TW;
                float tot = 0, area = 0;
                for (int y = (int) sy0; y < Math.ceil(sy1); y++) {
                    float fy = Math.min(sy1, y + 1) - Math.max(sy0, y);
                    if (fy <= 0) continue;
                    for (int x = (int) sx0; x < Math.ceil(sx1); x++) {
                        float fx = Math.min(sx1, x + 1) - Math.max(sx0, x);
                        if (fx <= 0) continue;
                        if (label[(b[1] + y) * W + (b[0] + x)] == id) tot += fx * fy;
                        area += fx * fy;
                    }
                }
                v[oy * TW + ox] = tot / area;
            }
        }
        float bestD = Float.MAX_VALUE; char best = 'x';
        for (int i = 0; i < T_DATA.length; i++) {
            float d = 0;
            for (int k = 0; k < v.length; k++) d += Math.abs(T_DATA[i][k] - v[k]);
            d /= v.length;
            if (d < bestD) { bestD = d; best = T_LABEL[i]; }
        }
        return bestD < 0.15f ? best : 'x';
    }
}
