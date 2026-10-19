package com.nickrocks.pixelflowbot.core.mc;

import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What a Minecraft (Bedrock, touch controls) screen shows: which screen it is, health, tree trunks
 * in view, and — on the crafting screen — every recipe, the hotbar and the output slot.
 * Plain Java on packed ARGB frames, so it is tested on the user's recording on a computer.
 */
public final class McVision {
    private McVision() { }

    public enum Screen { WORLD, CRAFT2, CRAFT3, OTHER }

    // ---------------------------------------------------------------- screen kind

    public static Screen screen(Frame f) {
        if (craftingOpen(f)) return table3x3(f) ? Screen.CRAFT3 : Screen.CRAFT2;
        if (hudVisible(f)) return Screen.WORLD;
        return Screen.OTHER;
    }

    /** The crafting screen's big light-grey panel: right of the grid, or below it (toasts can cover one). */
    static boolean craftingOpen(Frame f) {
        if (lightPanel(f, 2060, 160, 2300, 640) < 0.85f && lightPanel(f, 1300, 560, 1620, 860) < 0.8f) return false;
        // ...and the dark recipe panel on the left
        int dark = 0, m = 0;
        for (float y = 560; y <= 860; y += 20) {
            for (float x = 220; x <= 1150; x += 30) {
                int p = at(f, x, y);
                int v = Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255));
                m++;
                if (v < 190) dark++;
            }
        }
        return dark > m * 0.6;
    }

    static float lightPanel(Frame f, float x0, float y0, float x1, float y1) {
        int n = 0, ok = 0;
        for (float y = y0; y <= y1; y += 20) {
            for (float x = x0; x <= x1; x += 20) {
                int p = at(f, x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                n++;
                if (Math.abs(r - 198) < 16 && Math.abs(g - 198) < 16 && Math.abs(b - 198) < 16) ok++;
            }
        }
        return ok / (float) n;
    }

    /** The 3×3 grid's left border is a dark line at x≈1599; the 2×2 grid starts further right. */
    static boolean table3x3(Frame f) {
        int n = 0, dark = 0;
        for (float y = 140; y <= 520; y += 6) {
            int best = 255;
            for (float x = 1594; x <= 1606; x += 2) {
                int p = at(f, x, y);
                best = Math.min(best, Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255)));
            }
            n++;
            if (best < 110) dark++;
        }
        return dark > n * 0.6;
    }

    /** In the world: the hotbar's "…" button (three white dots) and the hearts row. */
    static boolean hudVisible(Frame f) {
        int dots = 0;
        for (float dx : new float[]{-28, -3, 23}) {
            boolean w = false;
            for (float ox = -3; ox <= 3 && !w; ox += 1.5f) {
                for (float oy = -3; oy <= 3 && !w; oy += 1.5f) {
                    int p = at(f, McLayout.INV_BTN_X + dx + ox, McLayout.INV_BTN_Y + oy);
                    w = Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) > 185;
                }
            }
            if (w) dots++;
        }
        return dots >= 2 && heartSlots(f) >= 8;
    }

    /** Heart icons drawn (full, half or empty): a red or dark outline in each heart box. */
    static int heartSlots(Frame f) {
        int n = 0;
        for (int i = 0; i < 10; i++) {
            int dark = 0, tot = 0;
            for (float y = McLayout.HEART_Y0; y <= McLayout.HEART_Y1; y += 3) {
                for (float x = McLayout.heartX0(i); x <= McLayout.heartX0(i) + McLayout.HEART_W; x += 3) {
                    int p = at(f, x, y);
                    int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                    tot++;
                    if ((r > 170 && g < 90 && b < 90) || Math.max(r, Math.max(g, b)) < 50) dark++;
                }
            }
            if (dark > tot * 0.12) n++;
        }
        return n;
    }

    /** Health in half hearts (0..20): red pixels in the left and right half of each heart. */
    public static int health(Frame f) {
        int h = 0;
        for (int i = 0; i < 10; i++) {
            for (int half = 0; half < 2; half++) {
                int red = 0, tot = 0;
                float x0 = McLayout.heartX0(i) + half * McLayout.HEART_W / 2, x1 = x0 + McLayout.HEART_W / 2;
                for (float y = McLayout.HEART_Y0 + 4; y <= McLayout.HEART_Y1 - 6; y += 3) {
                    for (float x = x0 + 2; x < x1 - 1; x += 3) {
                        int p = at(f, x, y);
                        tot++;
                        if (((p >> 16) & 255) > 170 && ((p >> 8) & 255) < 90 && (p & 255) < 90) red++;
                    }
                }
                if (red > tot * 0.3) h++;
            }
        }
        return h;
    }

    // ---------------------------------------------------------------- touch controls

    public enum Layout { JOYSTICK, DPAD, UNKNOWN }

    /**
     * Which movement control is on screen: the D-pad's up and down buttons have dark side edges at
     * x≈215 and x≈388; the joystick's base has them at x≈202 and x≈548. Measured on the user's own
     * screenshot (D-pad) and recording (joystick).
     */
    public static Layout layout(Frame f) {
        float dpad = (edges(f, new float[]{215, 388}, 905, 1050) + edges(f, new float[]{215, 388}, 570, 705)) / 2;
        float joy = edges(f, new float[]{202, 548}, 440, 750);
        if (dpad >= 0.6f && dpad > joy) return Layout.DPAD;
        if (joy >= 0.45f && dpad < 0.35f) return Layout.JOYSTICK;
        return Layout.UNKNOWN;
    }

    /** Share of rows with a vertical edge darker than both sides near each x. */
    static float edges(Frame f, float[] xs, float y0, float y1) {
        int n = 0, hit = 0;
        for (float y = y0; y <= y1; y += 6) {
            for (float x : xs) {
                n++;
                int m = 255;
                for (float dx = -8; dx <= 8; dx += 2) m = Math.min(m, val(at(f, x + dx, y)));
                float side = 0;
                int k = 0;
                for (float dx = 14; dx <= 24; dx += 2) { side += val(at(f, x - dx, y)) + val(at(f, x + dx, y)); k += 2; }
                if (m < 0.72f * side / k) hit++;
            }
        }
        return n == 0 ? 0 : hit / (float) n;
    }

    private static int val(int p) { return Math.max((p >> 16) & 255, Math.max((p >> 8) & 255, p & 255)); }

    // ---------------------------------------------------------------- tree trunks

    /** One trunk (connected bark-coloured region) in reference pixels. */
    public static final class Trunk {
        public float x0, y0, x1, y1;       // bounding box
        public float cx;                   // centre column
        public float width;                // median width of its rows
        public int cells;
        public boolean birch;
        public float holdX, holdY;         // where to press to mine it
        public float score;
        @Override public String toString() {
            return String.format(Locale.US, (birch ? "birch " : "") + "trunk x%.0f w%.0f [%.0f..%.0f] hold %.0f,%.0f", cx, width, y0, y1, holdX, holdY);
        }
    }

    static final float CELL = 12;          // reference px per analysis cell
    static final int GW = (int) (McLayout.REF_W / CELL), GH = (int) (McLayout.REF_H / CELL);

    /** Bark colour: dark, brownish/olive; not dirt (redder, brighter, more saturated). */
    public static boolean bark(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (mx < 23 || mx > 117 || mx == 0) return false;
        float s = (mx - mn) / (float) mx, v = mx / 255f;
        if (s < 0.2f || s > 0.8f) return false;
        float d = mx - mn;
        float h;
        if (mx == r) h = 60 * (((g - b) / d) % 6);
        else if (mx == g) h = 60 * ((b - r) / d + 2);
        else h = 60 * ((r - g) / d + 4);
        if (h < 0) h += 360;
        if (h < 14 || h > 58) return false;
        return !(h < 34 && s > 0.47f && v > 0.30f);   // dirt
    }

    static boolean hud(float x, float y) {
        for (float[] r : McLayout.HUD_RECTS) if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) return true;
        return false;
    }

    /** Bark mask on a 12-px grid (reference space). */
    public static boolean[] barkGrid(Frame f) { return grid(f, false); }

    /** Birch bark: pale grey with a slight blue cast (bluer at dusk), not green, not brown. */
    public static boolean birch(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (mx < 30 || mx > 240) return false;
        float s = (mx - mn) / (float) mx;
        if (s > 0.38f) return false;
        int br = b - r, bg = b - g, gr = g - r;
        return br >= -12 && br <= 32 && bg >= -10 && bg <= 22 && gr >= -8 && gr <= 14;
    }

    public static boolean[] birchGrid(Frame f) { return grid(f, true); }

    static boolean[] grid(Frame f, boolean birchBark) {
        boolean[] g = new boolean[GW * GH];
        for (int gy = 0; gy < GH; gy++) {
            for (int gx = 0; gx < GW; gx++) {
                float cx = gx * CELL + CELL / 2, cy = gy * CELL + CELL / 2;
                if (hud(cx, cy)) continue;
                int k = 0;
                for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {
                    int p = at(f, cx + ox * 4, cy + oy * 4);
                    if (birchBark ? birch(p) : bark(p)) k++;
                }
                g[gy * GW + gx] = k >= 5;
            }
        }
        // vines and leaves split trunks into stacked pieces: close vertical gaps of up to 2 cells
        boolean[] o = g.clone();
        for (int gx = 0; gx < GW; gx++) {
            for (int gy = 1; gy < GH - 1; gy++) {
                int i = gy * GW + gx;
                if (g[i]) continue;
                boolean up = g[i - GW] || (gy >= 2 && g[i - 2 * GW]);
                boolean dn = g[i + GW] || (gy < GH - 2 && g[i + 2 * GW]);
                if (up && dn && !hud(gx * CELL + CELL / 2, gy * CELL + CELL / 2)) o[i] = true;
            }
        }
        return o;
    }

    public static List<Trunk> trunks(Frame f) {
        List<Trunk> ts = trunks(barkGrid(f));
        // a crafting table (wood sides, orange planks top) is not a tree
        ts.removeIf(t -> plankTop(f, t) > 0.22f);
        // birch: pale bark with black streaks; sky, stone and clouds are pale too but have no streaks
        List<Trunk> bs = trunks(birchGrid(f));
        bs.removeIf(t -> { float st = streaks(f, t); return st < 0.025f || st > 0.4f
                // trunks stand upright and reach down towards the ground; sky through the leaves is wide and stays at the top
                || (t.y1 - t.y0) < 1.2f * t.width || t.y1 < 300
                || contrast(f, t) < 1.3f || plankTop(f, t) > 0.22f; });
        for (Trunk t : bs) t.birch = true;
        ts.addAll(bs);
        Collections.sort(ts, (a, b) -> Float.compare(b.score, a.score));
        return ts;
    }

    /** Brightness of a birch trunk's pale bark over the brightness just left and right of it (it's the palest thing around). */
    static float contrast(Frame f, Trunk t) {
        int[] in = new int[256], out = new int[256];
        int ni = 0, no = 0;
        float w = Math.max(36, (t.x1 - t.x0) * 0.4f);
        for (float y = t.y0; y < t.y1; y += 6) {
            for (float x = t.x0; x < t.x1; x += 6) {
                if (hud(x, y)) continue;
                int p = at(f, x, y);
                if (birch(p)) { in[val(p)]++; ni++; }
            }
            for (float x = t.x0 - w; x < t.x0; x += 6) if (x >= 0 && !hud(x, y)) { out[val(at(f, x, y))]++; no++; }
            for (float x = t.x1; x < t.x1 + w; x += 6) if (x < McLayout.REF_W && !hud(x, y)) { out[val(at(f, x, y))]++; no++; }
        }
        if (ni < 10 || no < 10) return 0;
        return (medianOf(in, ni) + 1f) / (medianOf(out, no) + 1f);
    }

    private static int medianOf(int[] h, int n) {
        int acc = 0;
        for (int i = 0; i < 256; i++) { acc += h[i]; if (acc * 2 >= n) return i; }
        return 255;
    }

    /** Share of very dark, colourless pixels (birch's black streaks) inside a trunk's box, relative to its bark. */
    static float streaks(Frame f, Trunk t) {
        int[] hist = new int[256];
        int n = 0;
        for (float y = t.y0; y < t.y1; y += 6) {
            for (float x = t.x0; x < t.x1; x += 6) {
                if (hud(x, y)) continue;
                int p = at(f, x, y);
                if (birch(p)) { hist[val(p)]++; n++; }
            }
        }
        if (n < 20) return 0;
        int acc = 0, med = 0;
        for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc * 2 >= n) { med = i; break; } }
        int dark = 0, all = 0;
        for (float y = t.y0; y < t.y1; y += 6) {
            for (float x = t.x0; x < t.x1; x += 6) {
                if (hud(x, y)) continue;
                int p = at(f, x, y);
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                boolean inTrunk = birch(p);
                boolean streak = mx < med * 0.55f && mx - mn < 18;
                if (inTrunk || streak) all++;
                if (streak) dark++;
            }
        }
        return all == 0 ? 0 : dark / (float) all;
    }

    /** Planks colour (orange-tan, bright): the top of a crafting table, planks, a wooden floor. */
    public static boolean plank(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (mx != r || mx < 120 || mx > 235) return false;
        float s = (mx - mn) / (float) mx;
        float hue = 60 * ((g - b) / (float) (mx - mn));
        return s > 0.3f && s < 0.7f && hue > 12 && hue < 38;
    }

    /** Share of planks-coloured pixels in a band at the top of a trunk's box. */
    static float plankTop(Frame f, Trunk t) {
        int n = 0, k = 0;
        float y0 = Math.max(64, t.y0 - 90), y1 = Math.min(t.y1, t.y0 + 90);
        for (float y = y0; y <= y1; y += 8) {
            for (float x = t.x0; x <= t.x1; x += 8) {
                if (hud(x, y)) continue;
                n++;
                if (plank(at(f, x, y))) k++;
            }
        }
        return n == 0 ? 0 : k / (float) n;
    }

    public static List<Trunk> trunks(boolean[] g) {
        List<Trunk> out = new ArrayList<>();
        int[] comp = new int[GW * GH];
        int[] stack = new int[GW * GH];
        int id = 0;
        for (int s0 = 0; s0 < g.length; s0++) {
            if (!g[s0] || comp[s0] != 0) continue;
            id++;
            int sp = 0, n = 0;
            stack[sp++] = s0;
            comp[s0] = id;
            int x0 = GW, x1 = -1, y0 = GH, y1 = -1;
            List<Integer> cells = new ArrayList<>();
            while (sp > 0) {
                int c = stack[--sp];
                cells.add(c);
                n++;
                int x = c % GW, y = c / GW;
                x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                int[] nb = {x > 0 ? c - 1 : -1, x < GW - 1 ? c + 1 : -1, y > 0 ? c - GW : -1, y < GH - 1 ? c + GW : -1};
                for (int q : nb) if (q >= 0 && g[q] && comp[q] == 0) { comp[q] = id; stack[sp++] = q; }
            }
            int h = y1 - y0 + 1, w = x1 - x0 + 1;
            if (n < 12 || h < 6) continue;
            int[] rowW = new int[h];
            for (int c : cells) rowW[c / GW - y0]++;
            int[] sorted = rowW.clone();
            java.util.Arrays.sort(sorted);
            int medW = Math.max(1, sorted[h / 2]);
            boolean reachesTop = y0 <= 7;                       // continues up out of view
            if (h < 1.2f * medW && !reachesTop && h < GH * 0.6f) continue;   // a flat patch, not a trunk
            if (n < 0.35f * w * h && medW > 3) continue;        // scattered speckles
            Trunk t = new Trunk();
            t.x0 = x0 * CELL; t.x1 = (x1 + 1) * CELL; t.y0 = y0 * CELL; t.y1 = (y1 + 1) * CELL;
            long sx = 0;
            for (int c : cells) sx += c % GW;
            t.cx = (sx / (float) n + 0.5f) * CELL;
            t.width = medW * CELL;
            t.cells = n;
            // press on an interior cell, as close to eye level (screen middle) as possible
            float best = Float.MAX_VALUE;
            for (int c : cells) {
                int x = c % GW, y = c / GW;
                if (x == 0 || y == 0 || x == GW - 1 || y == GH - 1) continue;
                if (!(g[c - 1] && g[c + 1] && g[c - GW] && g[c + GW])) continue;
                float px = (x + 0.5f) * CELL, py = (y + 0.5f) * CELL;
                float d = Math.abs(px - t.cx) * 1.5f + Math.abs(py - McLayout.CY);
                if (d < best) { best = d; t.holdX = px; t.holdY = py; }
            }
            if (best == Float.MAX_VALUE) continue;
            float off = Math.abs(t.cx - McLayout.CX) / McLayout.REF_W;
            t.score = t.width * (1.1f - off) * (reachesTop ? 1.2f : 1f);
            out.add(t);
        }
        Collections.sort(out, (a, b) -> Float.compare(b.score, a.score));
        return out;
    }

    /** Fraction of bark in a square around a point (reference px). */
    public static float barkAround(Frame f, float x, float y, float half) {
        int n = 0, k = 0;
        for (float yy = y - half; yy <= y + half; yy += 6) {
            for (float xx = x - half; xx <= x + half; xx += 6) {
                n++;
                if (bark(at(f, xx, yy))) k++;
            }
        }
        return n == 0 ? 0 : k / (float) n;
    }

    // ---------------------------------------------------------------- change detection

    /** 16×16 grey thumbnail of a square around a point (reference px). */
    public static int[] patch(Frame f, float x, float y, float half) {
        int[] o = new int[256];
        for (int j = 0; j < 16; j++) {
            for (int i = 0; i < 16; i++) {
                int p = at(f, x - half + (i + 0.5f) * half / 8, y - half + (j + 0.5f) * half / 8);
                o[j * 16 + i] = (((p >> 16) & 255) * 3 + ((p >> 8) & 255) * 6 + (p & 255)) / 10;
            }
        }
        return o;
    }

    public static float patchDiff(int[] a, int[] b) {
        long s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / (float) a.length;
    }

    /** Column brightness profile of the world view (for measuring how far a camera swipe turned). */
    public static float[] columnProfile(Frame f) {
        int n = (int) (McLayout.REF_W / 4);
        float[] p = new float[n];
        for (int i = 0; i < n; i++) {
            float x = i * 4 + 2;
            long s = 0;
            int k = 0;
            for (float y = 80; y < 900; y += 8) {
                if (hud(x, y)) continue;
                int q = at(f, x, y);
                s += ((q >> 16) & 255) + ((q >> 8) & 255) + (q & 255);
                k++;
            }
            p[i] = k == 0 ? 0 : s / (float) k;
        }
        return p;
    }

    /**
     * How far the picture moved sideways between two profiles (reference px; + = moved right),
     * by the best normalised correlation of their gradients. NaN if nothing matches well.
     */
    public static float shift(float[] a, float[] b) {
        int n = a.length;
        float[] da = new float[n - 1], db = new float[n - 1];
        for (int i = 0; i < n - 1; i++) { da[i] = a[i + 1] - a[i]; db[i] = b[i + 1] - b[i]; }
        int maxS = n / 3;
        float best = -2;
        int bs = 0;
        for (int s = -maxS; s <= maxS; s++) {
            double sab = 0, saa = 0, sbb = 0;
            for (int i = 0; i < n - 1; i++) {
                int j = i + s;
                if (j < 0 || j >= n - 1) continue;
                sab += da[i] * db[j]; saa += da[i] * da[i]; sbb += db[j] * db[j];
            }
            if (saa == 0 || sbb == 0) continue;
            float c = (float) (sab / Math.sqrt(saa * sbb));
            if (c > best) { best = c; bs = s; }
        }
        return best < 0.35f ? Float.NaN : bs * 4f;
    }

    // ---------------------------------------------------------------- crafting screen

    public static final class Craft {
        public boolean table;                                   // 3×3 grid (at a crafting table)
        public final McIcons.Read[][] recipes = new McIcons.Read[McLayout.RECIPE_ROWS][McLayout.RECIPE_COLS];
        public final McIcons.Read[] hotbar = new McIcons.Read[9];
        public McIcons.Read output;

        /** The recipe cell for an item: prefer one that can be crafted now. {row, col} or null. */
        public int[] find(String item) {
            int[] best = null;
            int bestRank = -1;
            for (int r = 0; r < recipes.length; r++) {
                for (int c = 0; c < recipes[r].length; c++) {
                    McIcons.Read rd = recipes[r][c];
                    if (rd == null || !item.equals(rd.item)) continue;
                    int rank = rd.bg == McIcons.Bg.MISSING ? 0 : 1 + Math.min(rd.count, 64);
                    if (rank > bestRank) { bestRank = rank; best = new int[]{r, c}; }
                }
            }
            return best;
        }

        /** How many of the item the inventory can make (the number on its recipe); 0 if red or absent. */
        public int canMake(String item) {
            int[] rc = find(item);
            if (rc == null) return 0;
            McIcons.Read rd = recipes[rc[0]][rc[1]];
            return rd.bg == McIcons.Bg.MISSING ? 0 : Math.max(1, rd.count);
        }

        public boolean selected(String item) {
            int[] rc = find(item);
            return rc != null && recipes[rc[0]][rc[1]].bg == McIcons.Bg.SELECTED;
        }

        /** Hotbar slot holding the item, or -1. */
        public int slot(String item) {
            for (int i = 0; i < 9; i++) if (hotbar[i] != null && item.equals(hotbar[i].item)) return i;
            return -1;
        }

        public float outX() { return table ? McLayout.OUT3_X : McLayout.OUT2_X; }
        public float outY() { return table ? McLayout.OUT3_Y : McLayout.OUT2_Y; }

        public String summary() {
            StringBuilder sb = new StringBuilder(table ? "table: " : "inventory: ");
            for (McIcons.Read[] row : recipes) for (McIcons.Read r : row) if (r != null && !r.item.isEmpty() && !r.item.startsWith("x_") && !r.item.equals("?")) sb.append(r).append(' ');
            sb.append("| hotbar ");
            for (McIcons.Read r : hotbar) sb.append(r == null ? "·" : r.toString()).append(' ');
            sb.append("| out ").append(output);
            return sb.toString().trim();
        }
    }

    public static Craft craft(Frame f) {
        Craft c = new Craft();
        c.table = table3x3(f);
        for (int r = 0; r < McLayout.RECIPE_ROWS; r++) {
            for (int col = 0; col < McLayout.RECIPE_COLS; col++) c.recipes[r][col] = McIcons.readRecipe(f, McLayout.recipeX(col), McLayout.recipeY(r));
        }
        for (int i = 0; i < 9; i++) c.hotbar[i] = McIcons.read(f, McLayout.invSlotX(i), McLayout.INV_SLOT_Y);
        c.output = McIcons.read(f, c.outX(), c.outY());
        return c;
    }

    // ---------------------------------------------------------------- pixels

    static int at(Frame f, float xRef, float yRef) {
        int x = Math.round(xRef * f.w / McLayout.REF_W), y = Math.round(yRef * f.h / McLayout.REF_H);
        if (x < 0) x = 0; else if (x >= f.w) x = f.w - 1;
        if (y < 0) y = 0; else if (y >= f.h) y = f.h - 1;
        return f.px[y * f.w + x];
    }
}
