package com.nickrocks.pixelflowbot.core.arrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads an "arrows escape" puzzle (e.g. com.oakever.arrows) from a screenshot and finds which
 * arrows can leave the board right now.
 *
 * The puzzle: dark arrows drawn as bent lines on a square lattice. Tapping an arrow sends it forward
 * along its head's direction; it escapes if nothing lies between its head and the edge of the board,
 * otherwise it bumps. Removing arrows only ever clears paths, so repeatedly tapping any free arrow
 * solves the level.
 *
 * Reading: ink mask (dark, warm-coloured pixels) → the board band → lattice spacing and origin from
 * the stroke projections → which lattice edges are drawn → arrows are connected paths of edges →
 * the head is the end with arrowhead wings beside it. Pure Java (no Android), unit-tested on screenshots.
 */
public final class ArrowsBoard {

    public static final class Arrow {
        public final List<int[]> nodes = new ArrayList<>();  // lattice {col,row}, tail → head
        public int dx, dy;                                    // head direction (lattice units)
        public boolean free;                                  // nothing between head and the board edge
        public float tapX, tapY;                              // screen pixels: middle of the edge next to the head
        public int[] head() { return nodes.get(nodes.size() - 1); }

        @Override public String toString() {
            int[] h = head();
            String dir = dx > 0 ? "right" : dx < 0 ? "left" : dy > 0 ? "down" : "up";
            return String.format(Locale.US, "arrow of %d at (%d,%d) %s%s", nodes.size() - 1, h[0], h[1], dir, free ? " FREE" : "");
        }
    }

    // Lattice: x = x0 + col*s, y = y0 + row*s, cols × rows nodes.
    public float x0, y0, s;
    public int cols, rows;
    public final List<Arrow> arrows = new ArrayList<>();
    /** Ends that couldn't be read (no head, two heads, branching) — reported, never tapped. */
    public int unreadable;
    public String problem;

    public static boolean DEBUG = false;
    private boolean[] ink;
    private int[][] owner;     // lattice node → arrow index, -1 empty, -2 unreadable ink
    private int w, h;

    // ------------------------------------------------------------------ reading

    /** ARGB pixels. Null result never; check {@link #problem}. */
    public static ArrowsBoard read(int[] px, int w, int h) { return read(px, w, h, null); }

    /**
     * Same, but reusing the lattice of an earlier reading of this level ({@code lattice} non-null):
     * the board doesn't move during a level, and once most arrows are gone the remaining lines are
     * too sparse to measure the spacing from.
     */
    public static ArrowsBoard read(int[] px, int w, int h, ArrowsBoard lattice) {
        ArrowsBoard b = new ArrowsBoard();
        b.w = w;
        b.h = h;
        b.ink = new boolean[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i], r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
            b.ink[i] = r + g + bl < 450 && r > bl + 15;   // dark brown lines; not the bot's dark-blue panel
        }
        if (lattice != null && lattice.s > 0) {
            b.x0 = lattice.x0; b.y0 = lattice.y0; b.s = lattice.s; b.cols = lattice.cols; b.rows = lattice.rows;
            b.parseArrows();
        } else {
            b.parse();
        }
        return b;
    }

    private boolean at(int x, int y) {
        return x >= 0 && y >= 0 && x < w && y < h && ink[y * w + x];
    }

    /** Fraction of ink in a (2r+1)² box. */
    private float density(float cx, float cy, int r) {
        int n = 0, k = 0;
        int x = Math.round(cx), y = Math.round(cy);
        for (int j = -r; j <= r; j++) for (int i = -r; i <= r; i++) { n++; if (at(x + i, y + j)) k++; }
        return k / (float) n;
    }

    private void parse() {
        // 1. Board band: the tallest stretch of rows with ink, splitting at gaps wider than 4% of the screen.
        int[] rowInk = new int[h];
        for (int y = 0; y < h; y++) { int c = 0; for (int x = 0; x < w; x++) if (ink[y * w + x]) c++; rowInk[y] = c; }
        int gap = Math.max(20, h / 25), bestA = -1, bestB = -1;
        long bestMass = 0;
        int y = 0;
        while (y < h) {
            while (y < h && rowInk[y] == 0) y++;
            if (y >= h) break;
            int a = y, last = y;
            long mass = 0;
            while (y < h && y - last <= gap) { if (rowInk[y] > 0) { last = y; mass += rowInk[y]; } y++; }
            if (mass > bestMass) { bestMass = mass; bestA = a; bestB = last; }
        }
        if (bestA < 0 || bestB - bestA < h / 10) { problem = "no board found"; return; }
        int top = bestA, bottom = bestB;
        int[] colInk = new int[w];
        int left = w, right = -1;
        for (int yy = top; yy <= bottom; yy++)
            for (int x = 0; x < w; x++) if (ink[yy * w + x]) { colInk[x]++; left = Math.min(left, x); right = Math.max(right, x); }
        int[] rowBand = new int[h];
        for (int yy = top; yy <= bottom; yy++) rowBand[yy] = rowInk[yy];

        // 2. Lattice spacing and phase from both projections.
        float[] sx = lattice(colInk, left, right), sy = lattice(rowBand, top, bottom);
        if (sx == null || sy == null) { problem = "couldn't find the lattice"; return; }
        // The lattice is square: one axis may lock onto a multiple of the spacing (sparse lines), so use
        // the smaller spacing and refit the other axis's phase with it.
        s = Math.min(sx[0], sy[0]);
        float big = Math.max(sx[0], sy[0]), k = Math.round(big / s);
        if (k < 1 || Math.abs(big / s - k) > 0.08f) { problem = String.format(Locale.US, "lattice not square (%.1f vs %.1f)", sx[0], sy[0]); return; }
        if (k > 1) {
            if (sx[0] > sy[0]) sx = refine(colInk, left, right, s, phase(colInk, left, right, s));
            else sy = refine(rowBand, top, bottom, s, phase(rowBand, top, bottom, s));
        }
        s = (sx[0] + sy[0]) / 2f;
        x0 = sx[1];
        y0 = sy[1];
        while (x0 - s > left - s * 0.3f) x0 -= s;
        while (y0 - s > top - s * 0.3f) y0 -= s;
        cols = (int) Math.floor((right + s * 0.3f - x0) / s) + 1;
        rows = (int) Math.floor((bottom + s * 0.3f - y0) / s) + 1;
        if (cols < 2 || rows < 2 || cols > 80 || rows > 120) { problem = "odd lattice " + cols + "×" + rows; return; }

        parseArrows();
    }

    /** Steps 3–5, on a known lattice. */
    private void parseArrows() {
        // 3. Drawn edges.
        int r = Math.max(1, Math.round(s * 0.05f));
        boolean[][] hor = new boolean[cols][rows], ver = new boolean[cols][rows];
        for (int c = 0; c < cols; c++)
            for (int rr = 0; rr < rows; rr++) {
                float nx = x0 + c * s, ny = y0 + rr * s;
                if (c + 1 < cols) hor[c][rr] = density(nx + s / 2, ny, r) > 0.6f && density(nx + s * 0.3f, ny, r) > 0.6f && density(nx + s * 0.7f, ny, r) > 0.6f;
                if (rr + 1 < rows) ver[c][rr] = density(nx, ny + s / 2, r) > 0.6f && density(nx, ny + s * 0.3f, r) > 0.6f && density(nx, ny + s * 0.7f, r) > 0.6f;
            }

        // 4. Arrows = connected paths; walk from each end.
        owner = new int[cols][rows];
        for (int[] o : owner) java.util.Arrays.fill(o, -1);
        boolean[][] seen = new boolean[cols][rows];
        for (int c = 0; c < cols; c++)
            for (int rr = 0; rr < rows; rr++) {
                if (seen[c][rr] || degree(hor, ver, c, rr) == 0) continue;
                List<int[]> comp = new ArrayList<>();
                ArrayList<int[]> stack = new ArrayList<>();
                stack.add(new int[]{c, rr});
                seen[c][rr] = true;
                boolean branch = false;
                while (!stack.isEmpty()) {
                    int[] n = stack.remove(stack.size() - 1);
                    comp.add(n);
                    if (degree(hor, ver, n[0], n[1]) > 2) branch = true;
                    for (int[] m : neighbours(hor, ver, n[0], n[1])) if (!seen[m[0]][m[1]]) { seen[m[0]][m[1]] = true; stack.add(m); }
                }
                List<int[]> ends = new ArrayList<>();
                for (int[] n : comp) if (degree(hor, ver, n[0], n[1]) == 1) ends.add(n);
                if (branch || ends.size() != 2) {
                    if (DEBUG) System.out.println("shape: branch=" + branch + " ends=" + ends.size() + " at " + java.util.Arrays.toString(comp.get(0)));
                    unreadable++; markOwner(owner, comp, -2); continue; }
                Arrow a = path(hor, ver, ends.get(0));
                boolean h0 = hasHead(a), h1;
                java.util.Collections.reverse(a.nodes);
                h1 = hasHead(a);
                if (h0 == h1) {
                    if (DEBUG) System.out.println("heads " + h0 + "/" + h1 + " at " + java.util.Arrays.toString(ends.get(0)) + "-" + java.util.Arrays.toString(ends.get(1)));
                    unreadable++; markOwner(owner, comp, -2); continue;
                }
                if (h0) java.util.Collections.reverse(a.nodes);   // keep tail → head
                int[] hd = a.head(), pv = a.nodes.get(a.nodes.size() - 2);
                a.dx = hd[0] - pv[0];
                a.dy = hd[1] - pv[1];
                a.tapX = x0 + (hd[0] + pv[0]) * s / 2f;
                a.tapY = y0 + (hd[1] + pv[1]) * s / 2f;
                markOwner(owner, comp, arrows.size());
                arrows.add(a);
            }
        if (arrows.isEmpty()) { problem = "no arrows found"; return; }

        // 5. Free = every lattice node from the head to the board edge is empty (or ours).
        for (int i = 0; i < arrows.size(); i++) {
            Arrow a = arrows.get(i);
            int[] hd = a.head();
            int c = hd[0] + a.dx, rr = hd[1] + a.dy;
            boolean free = true;
            while (c >= 0 && rr >= 0 && c < cols && rr < rows) {
                if (owner[c][rr] != -1 && owner[c][rr] != i) { free = false; break; }
                c += a.dx;
                rr += a.dy;
            }
            a.free = free;
        }
    }

    private static void markOwner(int[][] owner, List<int[]> comp, int v) { for (int[] n : comp) owner[n[0]][n[1]] = v; }

    private int degree(boolean[][] hor, boolean[][] ver, int c, int r) { return neighbours(hor, ver, c, r).size(); }

    private List<int[]> neighbours(boolean[][] hor, boolean[][] ver, int c, int r) {
        List<int[]> l = new ArrayList<>(4);
        if (c + 1 < cols && hor[c][r]) l.add(new int[]{c + 1, r});
        if (c > 0 && hor[c - 1][r]) l.add(new int[]{c - 1, r});
        if (r + 1 < rows && ver[c][r]) l.add(new int[]{c, r + 1});
        if (r > 0 && ver[c][r - 1]) l.add(new int[]{c, r - 1});
        return l;
    }

    private Arrow path(boolean[][] hor, boolean[][] ver, int[] start) {
        Arrow a = new Arrow();
        int[] prev = null, cur = start;
        while (cur != null) {
            a.nodes.add(cur);
            int[] next = null;
            for (int[] m : neighbours(hor, ver, cur[0], cur[1]))
                if (prev == null || m[0] != prev[0] || m[1] != prev[1]) { next = m; break; }
            prev = cur;
            cur = next;
        }
        return a;
    }

    /** Arrowhead: ink on both sides of the line just behind its last node, wider than the stroke. */
    private boolean hasHead(Arrow a) {
        int n = a.nodes.size();
        int[] hd = a.nodes.get(n - 1), pv = a.nodes.get(n - 2);
        int dx = hd[0] - pv[0], dy = hd[1] - pv[1];
        float hx = x0 + hd[0] * s, hy = y0 + hd[1] * s;
        float bx = hx - dx * s * 0.2f, by = hy - dy * s * 0.2f;      // just behind the node, where the wings are widest
        float px = -dy, py = dx;                                      // perpendicular
        int r = Math.max(1, Math.round(s * 0.03f));
        float off = s * 0.18f;
        return density(bx + px * off, by + py * off, r) > 0.5f && density(bx - px * off, by - py * off, r) > 0.5f;
    }

    /**
     * Spacing and phase of a lattice from a projection: the smallest spacing whose sampled peaks are
     * nearly as strong as the best (so half or double spacing doesn't win). Returns {s, phase} or null.
     */
    static float[] lattice(int[] proj, int from, int to) {
        int len = to - from;
        if (len < 40) return null;
        float bestScore = 0;
        float[][] cand = new float[0][];
        List<float[]> all = new ArrayList<>();
        for (float s = 14; s <= len / 3f; s += 0.25f) {
            float bs = 0, bp = 0;
            for (float p = 0; p < s; p += 1f) {
                float sum = 0;
                int k = 0;
                for (float x = from + p; x <= to; x += s) { sum += max3(proj, Math.round(x)); k++; }
                float score = sum / k;
                if (score > bs) { bs = score; bp = from + p; }
            }
            all.add(new float[]{s, bp, bs});
            bestScore = Math.max(bestScore, bs);
        }
        for (float[] c : all) if (c[2] >= bestScore * 0.8f) return refine(proj, from, to, c[0], c[1]);
        return null;
    }

    /** Best phase for a known spacing. */
    static float phase(int[] proj, int from, int to, float s) {
        float bs = -1, bp = from;
        for (float p = 0; p < s; p += 1f) {
            float sum = 0;
            int k = 0;
            for (float x = from + p; x <= to; x += s) { sum += max3(proj, Math.round(x)); k++; }
            if (sum / k > bs) { bs = sum / k; bp = from + p; }
        }
        return bp;
    }

    /** Peak value within ±1 px (projections of a stroke are a few px wide). */
    private static float max3(int[] p, int x) {
        float m = 0;
        for (int i = -1; i <= 1; i++) if (x + i >= 0 && x + i < p.length) m = Math.max(m, p[x + i]);
        return m;
    }

    /** Least-squares fit of spacing/phase to the actual stroke centres near each lattice line. */
    private static float[] refine(int[] proj, int from, int to, float s, float p) {
        double sk = 0, skk = 0, sc = 0, skc = 0;
        int n = 0;
        int half = Math.max(2, Math.round(s * 0.2f));
        for (int k = 0; p + k * s <= to; k++) {
            int x = Math.round(p + k * s);
            double wsum = 0, xs = 0;
            for (int i = -half; i <= half; i++) {
                int xi = x + i;
                if (xi < 0 || xi >= proj.length) continue;
                wsum += proj[xi];
                xs += proj[xi] * (double) xi;
            }
            if (wsum <= 0) continue;
            double c = xs / wsum;
            sk += k; skk += (double) k * k; sc += c; skc += k * c;
            n++;
        }
        if (n < 3) return new float[]{s, p};
        double den = n * skk - sk * sk;
        if (Math.abs(den) < 1e-9) return new float[]{s, p};
        double slope = (n * skc - sk * sc) / den, icpt = (sc - slope * sk) / n;
        return new float[]{(float) slope, (float) icpt};
    }

    /**
     * Whole-level plan from this one reading: repeatedly take free arrows, as if they had left.
     * Returns the order, or a shorter list if the rest stays stuck (a sign of a misread).
     */
    public List<Arrow> plan() {
        List<Arrow> order = new ArrayList<>();
        if (owner == null) return order;
        int[][] occ = new int[cols][];
        for (int c = 0; c < cols; c++) occ[c] = owner[c].clone();
        boolean[] gone = new boolean[arrows.size()];
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int i = 0; i < arrows.size(); i++) {
                if (gone[i]) continue;
                Arrow a = arrows.get(i);
                int[] hd = a.head();
                int c = hd[0] + a.dx, r = hd[1] + a.dy;
                boolean free = true;
                while (c >= 0 && r >= 0 && c < cols && r < rows) {
                    if (occ[c][r] != -1 && occ[c][r] != i) { free = false; break; }
                    c += a.dx;
                    r += a.dy;
                }
                if (!free) continue;
                gone[i] = true;
                order.add(a);
                for (int[] n : a.nodes) occ[n[0]][n[1]] = -1;
                progress = true;
            }
        }
        return order;
    }

    /** Every arrow read with one head, and the whole board clears in simulation. */
    public boolean consistent() {
        return problem == null && unreadable == 0 && plan().size() == arrows.size() && !free().isEmpty();
    }

    public List<Arrow> free() {
        List<Arrow> l = new ArrayList<>();
        for (Arrow a : arrows) if (a.free) l.add(a);
        return l;
    }

    public String summary() {
        if (problem != null) return problem;
        return String.format(Locale.US, "%d arrows (%d free now) on a %d×%d lattice, %.1f px%s", arrows.size(), free().size(), cols, rows, s,
                unreadable > 0 ? ", " + unreadable + " unreadable" : "");
    }
}
