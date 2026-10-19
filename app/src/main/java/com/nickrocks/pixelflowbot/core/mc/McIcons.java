package com.nickrocks.pixelflowbot.core.mc;

import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reads item icons and stack counts in Minecraft's crafting screen (recipe book cells, hotbar,
 * output slot). An icon is reduced to a 12×12 descriptor: for each block, how much of it is
 * foreground (differs from the slot's own background colour) and the mean foreground colour. The
 * bottom-right corner, where the stack count is printed, is ignored. Templates come from the
 * user's own screen recording ({@link McIconData}); the nearest template wins if it is close enough.
 *
 * Counts are Minecraft's 5×7 pixel font in white; each glyph is sampled on that grid and matched
 * to the ten digit bitmaps.
 */
public final class McIcons {
    private McIcons() { }

    public static final int G = 12;                 // descriptor grid
    static final float SPAN = 120;                  // reference px covered by the descriptor (inside the slot border)

    /** Slot background, as seen in the recipe book. */
    public enum Bg { EMPTY, NORMAL, MISSING, SELECTED, OTHER }

    public static final class Desc {
        public final float[] frac = new float[G * G];
        public final int[] rgb = new int[G * G];
        public int bg;                              // background colour (packed RGB)
        public float fg;                            // overall foreground fraction
    }

    /** True for the blocks where the stack count is drawn. */
    static boolean ignored(int bx, int by) { return bx >= 7 && by >= 7; }

    public static Desc describe(Frame f, float cxRef, float cyRef) {
        float sx = McLayout.sx(f.w), sy = McLayout.sy(f.h);
        int bg = ringColour(f, cxRef, cyRef);
        int br = (bg >> 16) & 255, bgg = (bg >> 8) & 255, bb = bg & 255;
        Desc d = new Desc();
        d.bg = bg;
        float x0 = (cxRef - SPAN / 2) * sx, y0 = (cyRef - SPAN / 2) * sy, bw = SPAN / G * sx, bh = SPAN / G * sy;
        int step = Math.max(1, Math.round(Math.min(bw, bh) / 5));
        int fgAll = 0, nAll = 0;
        for (int by = 0; by < G; by++) {
            for (int bx = 0; bx < G; bx++) {
                int n = 0, k = 0;
                long sr = 0, sg = 0, sb = 0;
                for (float yy = y0 + by * bh + step / 2f; yy < y0 + (by + 1) * bh; yy += step) {
                    for (float xx = x0 + bx * bw + step / 2f; xx < x0 + (bx + 1) * bw; xx += step) {
                        int x = (int) xx, y = (int) yy;
                        if (x < 0 || y < 0 || x >= f.w || y >= f.h) continue;
                        int p = f.px[y * f.w + x];
                        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                        n++;
                        int diff = Math.max(Math.abs(r - br), Math.max(Math.abs(g - bgg), Math.abs(b - bb)));
                        if (diff > 30) { k++; sr += r; sg += g; sb += b; }
                    }
                }
                int i = by * G + bx;
                d.frac[i] = n == 0 ? 0 : k / (float) n;
                d.rgb[i] = k == 0 ? 0 : (int) (sr / k) << 16 | (int) (sg / k) << 8 | (int) (sb / k);
                if (!ignored(bx, by)) { fgAll += k; nAll += n; }
            }
        }
        d.fg = nAll == 0 ? 0 : fgAll / (float) nAll;
        return d;
    }

    /** Median colour of a thin ring just inside the slot border. */
    static int ringColour(Frame f, float cxRef, float cyRef) {
        float sx = McLayout.sx(f.w), sy = McLayout.sy(f.h);
        int[] hr = new int[256], hg = new int[256], hb = new int[256];
        int n = 0;
        for (int t = -56; t <= 56; t += 4) {
            for (int edge = 0; edge < 4; edge++) {
                for (int inset = 56; inset <= 60; inset += 2) {
                    float x = cxRef, y = cyRef;
                    switch (edge) {
                        case 0: x += t; y -= inset; break;
                        case 1: x += t; y += inset; break;
                        case 2: x -= inset; y += t; break;
                        default: x += inset; y += t; break;
                    }
                    int px = Math.round(x * sx), py = Math.round(y * sy);
                    if (px < 0 || py < 0 || px >= f.w || py >= f.h) continue;
                    int p = f.px[py * f.w + px];
                    hr[(p >> 16) & 255]++; hg[(p >> 8) & 255]++; hb[p & 255]++; n++;
                }
            }
        }
        return med(hr, n) << 16 | med(hg, n) << 8 | med(hb, n);
    }

    private static int med(int[] h, int n) {
        int acc = 0;
        for (int i = 0; i < 256; i++) { acc += h[i]; if (acc * 2 >= n) return i; }
        return 255;
    }

    public static Bg bgKind(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (mx - mn < 18 && mx < 115) return Bg.EMPTY;                       // dark grey, no recipe here
        if (mx - mn < 18 && mx >= 120 && mx < 185) return Bg.NORMAL;          // grey: can craft
        if (r > 170 && g < 140 && b < 140 && r - g > 60) return Bg.MISSING;   // red: missing ingredients
        if (b > 225 && r > 200 && g > 200 && b >= r) return Bg.SELECTED;      // lilac: picked
        return Bg.OTHER;
    }

    /** Distance between two descriptors (0 = identical). Colour counts where both have foreground. */
    public static float distance(Desc a, Desc b, boolean shapeOnly) {
        float sum = 0;
        int n = 0;
        for (int by = 0; by < G; by++) {
            for (int bx = 0; bx < G; bx++) {
                if (ignored(bx, by)) continue;
                int i = by * G + bx;
                float fa = a.frac[i], fb = b.frac[i];
                float d = Math.abs(fa - fb);
                if (!shapeOnly && fa > 0.25f && fb > 0.25f) {
                    int ca = a.rgb[i], cb = b.rgb[i];
                    int dc = Math.abs(((ca >> 16) & 255) - ((cb >> 16) & 255)) + Math.abs(((ca >> 8) & 255) - ((cb >> 8) & 255)) + Math.abs((ca & 255) - (cb & 255));
                    d += Math.min(fa, fb) * dc / 255f;
                }
                sum += d;
                n++;
            }
        }
        return sum / n;
    }

    // ---------------------------------------------------------------- templates

    public static final class Template {
        public final String name;
        public final Desc desc;
        public Template(String name, Desc desc) { this.name = name; this.desc = desc; }
    }

    private static List<Template> templates;

    public static synchronized List<Template> templates() {
        if (templates == null) {
            templates = new ArrayList<>();
            for (String[] t : McIconData.ICONS) templates.add(new Template(t[0], decode(t[1])));
        }
        return templates;
    }

    /** Replaces the templates (desktop tests: leave-one-frame-out checks). Null restores the shipped set. */
    public static synchronized void useTemplates(List<Template> t) { templates = t; }

    /** Result of reading one slot. */
    public static final class Read {
        public String item = "";      // "" = empty slot, "?" = something unrecognised
        public float dist = 1;
        public Bg bg = Bg.OTHER;
        public int count;             // 0 if empty; 1 if no number shown
        @Override public String toString() {
            return item.isEmpty() ? "·" : item + (count > 1 ? "×" + count : "") + (bg == Bg.MISSING ? "(red)" : bg == Bg.SELECTED ? "(sel)" : "");
        }
    }

    /** Accept a match if its distance is below this. */
    public static float maxDist = 0.16f;

    public static Read read(Frame f, float cxRef, float cyRef) {
        Read r = new Read();
        Desc d = describe(f, cxRef, cyRef);
        r.bg = bgKind(d.bg);
        if (d.fg < 0.03f) { r.item = ""; r.count = 0; return r; }
        String best = "?";
        float bd = 9;
        for (Template t : templates()) {
            float dist = distance(d, t.desc, false);
            if (dist < bd) { bd = dist; best = t.name; }
        }
        r.dist = bd;
        r.item = bd <= maxDist ? best : "?";
        r.count = 1;
        return r;
    }

    /**
     * A recipe-book cell: the item, its background (red = missing ingredients) and the number shown,
     * which is how many of that item the current inventory can make in total.
     */
    public static Read readRecipe(Frame f, float cxRef, float cyRef) {
        Read r = read(f, cxRef, cyRef);
        if (r.bg == Bg.EMPTY) { r.item = ""; r.count = 0; return r; }     // the book's dark empty cells
        if (r.item.isEmpty()) return r;
        if (r.bg != Bg.NORMAL && r.bg != Bg.MISSING && r.bg != Bg.SELECTED) { r.item = "?"; return r; }   // a toast or tooltip over it
        if (r.bg == Bg.MISSING) { r.count = 0; return r; }
        int c = readCount(f, cxRef, cyRef);
        r.count = c > 0 ? c : 1;
        return r;
    }

    // ---------------------------------------------------------------- encoding (for McIconData)

    public static String encode(Desc d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < G * G; i++) {
            int fq = Math.round(d.frac[i] * 15);
            sb.append(Integer.toHexString(fq));
            sb.append(String.format(Locale.US, "%06x", d.rgb[i] & 0xFFFFFF));
        }
        return sb.toString();
    }

    public static Desc decode(String s) {
        Desc d = new Desc();
        float fg = 0;
        int n = 0;
        for (int i = 0; i < G * G; i++) {
            int o = i * 7;
            d.frac[i] = Integer.parseInt(s.substring(o, o + 1), 16) / 15f;
            d.rgb[i] = Integer.parseInt(s.substring(o + 1, o + 7), 16);
            if (!ignored(i % G, i / G)) { fg += d.frac[i]; n++; }
        }
        d.fg = fg / n;
        return d;
    }

    // ---------------------------------------------------------------- stack counts

    private static final String[] DIGITS = {
            ".###.#...##..###.#.###..##...#.###.", // 0
            "..#...##....#....#....#....#..#####", // 1
            ".###.#...#....#..##..#....#...#####", // 2
            ".###.#...#....#..##.....##...#.###.", // 3
            "...##..#.#.#..##...######....#....#", // 4
            "######....####.....#....##...#.###.", // 5
            "..##..#...#....####.#...##...#.###.", // 6
            "######...#....#...#...#....#....#..", // 7
            ".###.#...##...#.###.#...##...#.###.", // 8
            ".###.#...##...#.####....#...#..##..", // 9
    };

    private static boolean white(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        return Math.min(r, Math.min(g, b)) >= 205 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 14;   // not the lilac 'selected' background
    }

    /** The number printed at the bottom right of a slot, or -1 if none could be read. */
    public static int readCount(Frame f, float cxRef, float cyRef) {
        float sx = McLayout.sx(f.w), sy = McLayout.sy(f.h);
        int x0 = Math.max(0, Math.round((cxRef - 20) * sx)), x1 = Math.min(f.w - 1, Math.round((cxRef + 70) * sx));
        int y0 = Math.max(0, Math.round((cyRef + 4) * sy)), y1 = Math.min(f.h - 1, Math.round((cyRef + 67) * sy));   // stay inside the cell
        if (x1 <= x0 || y1 <= y0) return -1;
        boolean[] col = new boolean[x1 - x0 + 1];
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) if (white(f.px[y * f.w + x])) { col[x - x0] = true; break; }
        }
        int value = 0, digits = 0;
        int x = 0;
        while (x < col.length) {
            if (!col[x]) { x++; continue; }
            int s = x;
            while (x < col.length && col[x]) x++;
            int gx0 = x0 + s, gx1 = x0 + x - 1;
            int gy0 = -1, gy1 = -1;
            for (int y = y0; y <= y1; y++) {
                boolean any = false;
                for (int xx = gx0; xx <= gx1 && !any; xx++) any = white(f.px[y * f.w + xx]);
                if (any) { if (gy0 < 0) gy0 = y; gy1 = y; }
            }
            int h = gy1 - gy0 + 1, w = gx1 - gx0 + 1;
            if (gy0 < 0 || h < 18 * sy || w < 4) continue;          // speck, not a glyph
            float fp = h / 7f;
            int cols = Math.max(1, Math.min(5, Math.round(w / fp)));
            StringBuilder bits = new StringBuilder();
            for (int r = 0; r < 7; r++) {
                for (int c = 0; c < 5; c++) {
                    // glyphs narrower than 5 font pixels are right-padded
                    if (c >= cols) { bits.append('.'); continue; }
                    float cx = gx0 + (c + 0.5f) * (w / (float) cols), cy = gy0 + (r + 0.5f) * fp;
                    bits.append(white(f.px[Math.round(cy - 0.5f) * f.w + Math.round(cx - 0.5f)]) ? '#' : '.');
                }
            }
            int best = -1, bd = 99;
            for (int dgt = 0; dgt < 10; dgt++) {
                int ham = 0;
                for (int i = 0; i < 35; i++) if (bits.charAt(i) != DIGITS[dgt].charAt(i)) ham++;
                if (ham < bd) { bd = ham; best = dgt; }
            }
            if (bd > 6) return -1;
            value = value * 10 + best;
            digits++;
            if (digits > 2) return -1;
        }
        return digits == 0 ? -1 : value;
    }

    public static String debug(Desc d) { return Arrays.toString(d.frac); }
}
