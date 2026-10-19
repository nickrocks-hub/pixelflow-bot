import com.nickrocks.pixelflowbot.core.flow.FlowBoard;
import com.nickrocks.pixelflowbot.core.flow.FlowSolver;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Flow Free checks: random puzzles (5×5 to 12×12) rendered like the game, with and without pipes
 * already drawn, must be read exactly and solved; plus any real screenshots given as arguments.
 *   javac -d /tmp/fp $S/core/flow/*.java tools/flow/FlowProbe.java && java -cp /tmp/fp FlowProbe [screens…]
 */
public class FlowProbe {
    static final int[][] PALETTE = {{255, 0, 0}, {0, 141, 0}, {12, 41, 254}, {234, 224, 0}, {251, 137, 0}, {0, 255, 255}, {255, 0, 255},
            {165, 44, 41}, {128, 0, 128}, {255, 255, 255}, {166, 166, 166}, {0, 255, 0}, {189, 182, 107}, {0, 0, 141}, {0, 128, 128}, {255, 160, 200}};
    static int fail;
    static boolean DBG = false;

    static void check(boolean ok, String what) { if (!ok) { fail++; System.out.println("FAIL: " + what); } }

    public static void main(String[] args) throws Exception {
        Random rnd = new Random(7);
        int made = 0;
        long worst = 0;
        for (int size = 5; size <= 14; size++) {
            for (int t = 0; t < 6; t++) {
                List<int[]> sol = generate(size, size, Math.min(16, t % 2 == 0 ? size + 2 : size), rnd);
                if (sol == null) continue;
                made++;
                for (int pipes = 0; pipes < 2; pipes++) {
                    BufferedImage im = render(size, size, sol, pipes == 1, rnd);
                    int w = im.getWidth(), h = im.getHeight();
                    FlowBoard b = FlowBoard.read(im.getRGB(0, 0, w, h, null, 0, w), w, h);
                    String tag = size + "×" + size + " #" + t + (pipes == 1 ? " with pipes" : "");
                    check(b.problem == null && b.cols == size && b.rows == size && b.pairs.length == sol.size(), tag + ": read " + b.summary());
                    if (b.problem != null) { ImageIO.write(im, "png", new File("/tmp/flow_fail.png")); continue; }
                    FlowSolver.Result r = FlowSolver.solve(b.rows, b.cols, b.pairs, 20_000);
                    worst = Math.max(worst, r.ms);
                    check(r.solved && valid(b.rows, b.cols, b.pairs, r.paths), tag + ": solve " + (r.solved ? "invalid" : r.problem));
                }
            }
        }
        // Big boards: random Hamiltonian path (backbite moves) cut into pipes — like the 9×9–14×14 packs.
        for (int size = 8; size <= 14; size++) {
            for (int t = 0; t < 4; t++) {
                // Real levels never have a pipe running alongside itself; make test boards like that.
                List<int[]> sol = null;
                for (int k = 0; k < 200 && sol == null; k++) sol = merged(size, size, Math.min(16, size + rnd.nextInt(4)), rnd);
                if (sol == null) { System.out.println("  (no clean " + size + "×" + size + " board generated)"); continue; }
                made++;
                BufferedImage im = render(size, size, sol, t % 2 == 1, rnd);
                int w = im.getWidth(), h = im.getHeight();
                FlowBoard b = FlowBoard.read(im.getRGB(0, 0, w, h, null, 0, w), w, h);
                String tag = size + "×" + size + " big #" + t;
                check(b.problem == null && b.cols == size && b.rows == size && b.pairs.length == sol.size(), tag + ": read " + b.summary());
                if (b.problem != null) { ImageIO.write(im, "png", new File("/tmp/flow_fail.png")); continue; }
                FlowSolver.Result r = FlowSolver.solve(b.rows, b.cols, b.pairs, 20_000);
                worst = Math.max(worst, r.ms);
                check(r.solved && valid(b.rows, b.cols, b.pairs, r.paths), tag + ": solve " + (r.solved ? "invalid" : r.problem) + " (" + sol.size() + " colours)");
                System.out.println("  " + tag + ": " + sol.size() + " colours, " + (r.solved ? "solved" : "NOT solved") + " in " + r.ms + " ms, " + r.nodes + " nodes");
            }
        }
        System.out.println(made + " generated puzzles, read and solved twice each (blank and with pipes); slowest solve " + worst + " ms");
        for (String f : args) {
            BufferedImage im = ImageIO.read(new File(f));
            int w = im.getWidth(), h = im.getHeight();
            FlowBoard b = FlowBoard.read(im.getRGB(0, 0, w, h, null, 0, w), w, h);
            System.out.println(f + ": " + b.summary());
            if (b.problem == null) {
                FlowSolver.Result r = FlowSolver.solve(b.rows, b.cols, b.pairs, 20_000);
                System.out.println("  solve: " + (r.solved ? "solved in " + r.ms + " ms" : r.problem));
            }
        }
        System.out.println(fail == 0 ? "all good" : fail + " failures");
        if (fail > 0) System.exit(1);
    }

    /** Every cell covered once, paths connect their dots through neighbours. */
    static boolean valid(int rows, int cols, int[][] pairs, List<int[]> paths) {
        int[] seen = new int[rows * cols];
        for (int c = 0; c < paths.size(); c++) {
            int[] p = paths.get(c);
            boolean ends = (p[0] == pairs[c][0] && p[p.length - 1] == pairs[c][1]) || (p[0] == pairs[c][1] && p[p.length - 1] == pairs[c][0]);
            if (!ends) return false;
            for (int i = 0; i < p.length; i++) {
                if (seen[p[i]]++ > 0) return false;
                if (i > 0) { int a = p[i - 1], b = p[i]; if (Math.abs(a / cols - b / cols) + Math.abs(a % cols - b % cols) != 1) return false; }
            }
        }
        for (int v : seen) if (v != 1) return false;
        return true;
    }

    /** Random full cover by non-self-touching paths of length ≥ 3. */
    static List<int[]> generate(int rows, int cols, int maxColors, Random rnd) {
        for (int attempt = 0; attempt < 4000; attempt++) {
            int n = rows * cols;
            int[] g = new int[n];
            Arrays.fill(g, -1);
            List<int[]> paths = new ArrayList<>();
            boolean ok = true;
            while (true) {
                int start = -1, bestOpen = 9;
                for (int i = 0; i < n; i++) if (g[i] < 0) { int o = open(g, i, rows, cols); if (o < bestOpen || (o == bestOpen && rnd.nextBoolean())) { bestOpen = o; start = i; } }
                if (start < 0) break;
                int c = paths.size();
                if (c >= maxColors) { ok = false; break; }
                List<Integer> p = new ArrayList<>();
                p.add(start);
                g[start] = c;
                int maxLen = 3 + rnd.nextInt(Math.max(2, n / Math.max(3, maxColors - 2)));
                while (p.size() < maxLen) {
                    int h = p.get(p.size() - 1);
                    List<Integer> opts = new ArrayList<>();
                    for (int m : nb(h, rows, cols)) {
                        if (g[m] >= 0) continue;
                        boolean touch = false;
                        for (int q : nb(m, rows, cols)) if (q != h && g[q] == c) touch = true;
                        if (!touch) opts.add(m);
                    }
                    if (opts.isEmpty()) break;
                    int m = opts.get(rnd.nextInt(opts.size()));
                    g[m] = c;
                    p.add(m);
                }
                if (p.size() < 3) { ok = false; break; }
                int[] arr = new int[p.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = p.get(i);
                paths.add(arr);
            }
            if (ok && paths.size() >= 3) return paths;
        }
        return null;
    }

    static List<int[]> hamiltonCut(int rows, int cols, int colors, Random rnd) {
        List<Integer> p = new ArrayList<>();
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) p.add(r * cols + (r % 2 == 0 ? c : cols - 1 - c));
        for (int it = 0; it < rows * cols * 60; it++) {
            if (rnd.nextBoolean()) Collections.reverse(p);
            int end = p.get(0);
            int[] nbs = nb(end, rows, cols);
            int v = nbs[rnd.nextInt(nbs.length)];
            int i = p.indexOf(v);
            if (i <= 1) continue;
            Collections.reverse(p.subList(0, i));
        }
        int n = p.size();
        List<int[]> out = new ArrayList<>();
        int pos = 0;
        for (int c = 0; c < colors; c++) {
            int left = n - pos, rest = colors - c - 1;
            int len = c == colors - 1 ? left : Math.max(3, Math.min(left - 3 * rest, 3 + rnd.nextInt(Math.max(1, 2 * left / (rest + 1) - 3))));
            int[] a = new int[len];
            for (int k = 0; k < len; k++) a[k] = p.get(pos + k);
            pos += len;
            out.add(a);
        }
        return out;
    }

    /** Short clean pieces of a random Hamiltonian path, merged end to end (staying clean) down to the colour count. */
    static List<int[]> merged(int rows, int cols, int colors, Random rnd) {
        List<int[]> whole = hamiltonCut(rows, cols, 1, rnd);
        int[] hp = whole.get(0);
        List<int[]> pieces = new ArrayList<>();
        for (int pos = 0; pos < hp.length; ) {
            int len = hp.length - pos < 6 ? hp.length - pos : 3;
            pieces.add(Arrays.copyOfRange(hp, pos, pos + len));
            pos += len;
        }
        List<List<Integer>> ps = new ArrayList<>();
        for (int[] a : pieces) { List<Integer> l = new ArrayList<>(); for (int v : a) l.add(v); ps.add(l); }
        int stuck = 0;
        while (ps.size() > colors && stuck < 5000) {
            int i = rnd.nextInt(ps.size()), j = rnd.nextInt(ps.size());
            if (i == j) { stuck++; continue; }
            List<Integer> a = new ArrayList<>(ps.get(i)), b = new ArrayList<>(ps.get(j));
            if (rnd.nextBoolean()) Collections.reverse(a);
            if (rnd.nextBoolean()) Collections.reverse(b);
            int ea = a.get(a.size() - 1), eb = b.get(0);
            if (Math.abs(ea / cols - eb / cols) + Math.abs(ea % cols - eb % cols) != 1) { stuck++; continue; }
            List<Integer> m = new ArrayList<>(a); m.addAll(b);
            int[] arr = new int[m.size()]; for (int k = 0; k < arr.length; k++) arr[k] = m.get(k);
            if (selfTouch(rows, cols, Collections.singletonList(arr))) { stuck++; continue; }
            ps.remove(Math.max(i, j)); ps.remove(Math.min(i, j)); ps.add(m);
            stuck = 0;
        }
        List<int[]> out = new ArrayList<>();
        for (List<Integer> l : ps) { int[] arr = new int[l.size()]; for (int k = 0; k < arr.length; k++) arr[k] = l.get(k); out.add(arr); }
        if (DBG) System.out.println("    merged → " + out.size() + " pieces, touch=" + selfTouch(rows, cols, out));
        if (out.size() > 16 || selfTouch(rows, cols, out)) return null;
        for (int[] a : out) if (a.length < 3) return null;
        return out;
    }

    static boolean selfTouch(int rows, int cols, List<int[]> sol) {
        int[] g = new int[rows * cols];
        Arrays.fill(g, -1);
        for (int c = 0; c < sol.size(); c++) for (int v : sol.get(c)) g[v] = c;
        for (int[] p : sol)
            for (int i = 0; i < p.length; i++)
                for (int q : nb(p[i], rows, cols))
                    if (g[q] == g[p[i]] && (i == 0 || q != p[i - 1]) && (i == p.length - 1 || q != p[i + 1])) return true;
        return false;
    }

    static int open(int[] g, int i, int rows, int cols) { int k = 0; for (int m : nb(i, rows, cols)) if (g[m] < 0) k++; return k; }

    static int[] nb(int i, int rows, int cols) {
        int r = i / cols, c = i % cols;
        List<Integer> l = new ArrayList<>();
        if (r > 0) l.add(i - cols); if (r < rows - 1) l.add(i + cols); if (c > 0) l.add(i - 1); if (c < cols - 1) l.add(i + 1);
        int[] a = new int[l.size()]; for (int k = 0; k < a.length; k++) a[k] = l.get(k); return a;
    }

    /** Looks like the game: black screen, dim green lines, glowing background, round dots, optional pipes. */
    static BufferedImage render(int rows, int cols, List<int[]> sol, boolean pipes, Random rnd) {
        int W = 1080, H = 2520;
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(540, 1800, 700, new float[]{0f, 1f}, new Color[]{new Color(60, 30, 0), Color.BLACK}));
        g.fillRect(0, 0, W, H);
        int margin = rnd.nextInt(3) == 0 ? 3 : 20;
        float cell = (W - 2f * margin) / cols;
        float x0 = margin, y0 = 650;
        if (pipes) {
            g.setStroke(new BasicStroke(cell * 0.32f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int c = 0; c < sol.size(); c++) {
                int[] p = sol.get(c);
                int upto = 1 + rnd.nextInt(p.length);
                int[] col = PALETTE[c];
                g.setColor(new Color(col[0] / 4, col[1] / 4, col[2] / 4));
                for (int i = 0; i < upto; i++) g.fill(new Rectangle2DF(x0 + (p[i] % cols) * cell, y0 + (p[i] / cols) * cell, cell, cell));
                g.setColor(new Color(col[0], col[1], col[2]));
                for (int i = 1; i < upto; i++) {
                    int a = p[i - 1], b = p[i];
                    g.drawLine(Math.round(x0 + (a % cols + 0.5f) * cell), Math.round(y0 + (a / cols + 0.5f) * cell), Math.round(x0 + (b % cols + 0.5f) * cell), Math.round(y0 + (b / cols + 0.5f) * cell));
                }
            }
        }
        g.setColor(new Color(63, 109, 63));
        g.setStroke(new BasicStroke(3));
        for (int i = 0; i <= cols; i++) g.drawLine(Math.round(x0 + i * cell), Math.round(y0), Math.round(x0 + i * cell), Math.round(y0 + rows * cell));
        for (int j = 0; j <= rows; j++) g.drawLine(Math.round(x0), Math.round(y0 + j * cell), Math.round(x0 + cols * cell), Math.round(y0 + j * cell));
        for (int c = 0; c < sol.size(); c++) {
            int[] p = sol.get(c);
            int[] col = PALETTE[c];
            g.setColor(new Color(col[0], col[1], col[2]));
            for (int e : new int[]{p[0], p[p.length - 1]}) {
                float cx = x0 + (e % cols + 0.5f) * cell, cy = y0 + (e / cols + 0.5f) * cell, r = cell * 0.275f;
                g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
            }
        }
        g.dispose();
        return im;
    }

    static final class Rectangle2DF extends java.awt.geom.Rectangle2D.Float {
        Rectangle2DF(float x, float y, float w, float h) { super(x + 2, y + 2, w - 4, h - 4); }
    }
}
