import com.aigame.minesweeper.core.MineBrain;
import com.aigame.minesweeper.core.MineSolver;
import com.aigame.minesweeper.core.MineVision;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Random;

/**
 * Plays simulated Minesweeper games through the real bot.
 *
 *   java Sim pixels  N cols rows mines   — rendered screens → MineVision → MineBrain → taps (end to end)
 *   java Sim logic   N cols rows mines   — solver only, many games, win rate
 *   java Sim reader  N                   — random mid-game boards, every tile read back and compared
 *
 * First-tap rule of the simulated game: "safe" (first tap is never a mine), set FIRST=open for
 * "first tap always opens an area", FIRST=none for no protection.
 */
public class Sim {
    static String FIRST = System.getenv().getOrDefault("FIRST", "safe");
    static boolean DEBUG = System.getenv("DEBUG") != null;

    static final class Game {
        final int cols, rows, mines;
        boolean[] mine;
        boolean[] open;
        boolean started, lost;
        final Random rnd;

        Game(int c, int r, int m, Random rnd) { cols = c; rows = r; mines = m; this.rnd = rnd; reset(); }

        void reset() { mine = new boolean[cols * rows]; open = new boolean[cols * rows]; started = false; lost = false; }

        void place(int first) {
            int n = cols * rows;
            boolean[] banned = new boolean[n];
            if (!FIRST.equals("none")) banned[first] = true;
            if (FIRST.equals("open")) for (int nb : nbs(first)) banned[nb] = true;
            int left = mines;
            while (left > 0) {
                int i = rnd.nextInt(n);
                if (mine[i] || banned[i]) continue;
                mine[i] = true; left--;
            }
        }

        int[] nbs(int i) {
            int c = i % cols, r = i / cols, k = 0;
            int[] t = new int[8];
            for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int x = c + dc, y = r + dr;
                if (x >= 0 && y >= 0 && x < cols && y < rows) t[k++] = y * cols + x;
            }
            return java.util.Arrays.copyOf(t, k);
        }

        int count(int i) { int k = 0; for (int nb : nbs(i)) if (mine[nb]) k++; return k; }

        void click(int i) {
            if (lost || open[i]) return;
            if (!started) { place(i); started = true; }
            if (mine[i]) { lost = true; open[i] = true; return; }
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(i); open[i] = true;
            while (!q.isEmpty()) {
                int v = q.poll();
                if (count(v) != 0) continue;
                for (int nb : nbs(v)) if (!open[nb] && !mine[nb]) { open[nb] = true; q.add(nb); }
            }
        }

        boolean won() {
            if (lost) return false;
            for (int i = 0; i < open.length; i++) if (!mine[i] && !open[i]) return false;
            return started;
        }

        int[] view() {
            int[] v = new int[cols * rows];
            for (int i = 0; i < v.length; i++) {
                if (!open[i]) v[i] = (lost && mine[i]) ? 9 : -1;
                else v[i] = mine[i] ? 10 : count(i);
            }
            return v;
        }
    }

    public static void main(String[] a) throws Exception {
        Render.base = javax.imageio.ImageIO.read(new java.io.File("tools/samples/new_game_9x9.jpg"));
        String mode = a[0];
        int N = Integer.parseInt(a[1]);
        if (mode.equals("reader")) { reader(N); return; }
        int cols = Integer.parseInt(a[2]), rows = Integer.parseInt(a[3]), mines = Integer.parseInt(a[4]);
        if (mode.equals("logic")) logic(N, cols, rows, mines);
        else pixels(N, cols, rows, mines);
    }

    // ------------------------------------------------------------------ logic only

    static void logic(int N, int cols, int rows, int mines) {
        Random rnd = new Random(1);
        int wins = 0, guessGames = 0;
        long totalMs = 0;
        long t0 = System.nanoTime();
        for (int gi = 0; gi < N; gi++) {
            Game g = new Game(cols, rows, mines, rnd);
            g.click(0);
            boolean guessed = false;
            int steps = 0;
            while (!g.lost && !g.won() && steps++ < 1000) {
                int[] v = g.view();
                MineSolver.Result r = MineSolver.solve(cols, rows, v, mines);
                if (!r.safe.isEmpty()) for (int c : r.safe) g.click(c);
                else { guessed = true; g.click(r.guess); }
            }
            if (g.won()) wins++;
            if (guessed) guessGames++;
        }
        totalMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("logic %dx%d/%d first=%s: won %d/%d = %.1f%%, games needing a guess %.1f%%, %.2f ms/game%n",
                cols, rows, mines, FIRST, wins, N, 100.0 * wins / N, 100.0 * guessGames / N, totalMs / (double) N);
    }

    // ------------------------------------------------------------------ end to end

    static void pixels(int N, int cols, int rows, int mines) throws Exception {
        Random rnd = new Random(7);
        MineBrain.Params p = new MineBrain.Params();
        p.restartOnLoss = true;
        p.playAgainOnWin = true;
        MineBrain brain = new MineBrain(p);
        Game g = new Game(cols, rows, mines, rnd);
        long now = 0;
        int frames = 0, rounds = 0, glitches = 0, wrongTaps = 0;
        long renderNs = 0, brainNs = 0;
        Render.Style st = new Render.Style();
        int gamesTarget = N;
        boolean glitchNext = false;
        int[] pressed = null;
        while (brain.wins + brain.losses < gamesTarget && rounds++ < N * 400) {
            // the screen right now (sometimes a half-drawn frame just after taps)
            int[] view = g.view();
            if (glitchNext && pressed != null) {
                // tapped tiles drawn pressed-in (flat, empty) before the game updates: must not fool it
                int[] before = view.clone();
                for (int c : pressed) if (before[c] == -1 || true) view[c] = 0;
                glitches++;
            }
            int counter = mines;
            long t0 = System.nanoTime();
            BufferedImage im = Render.draw(cols, rows, view, counter, st, rnd);
            Img f = Img.of(im, 540);
            long t1 = System.nanoTime();
            MineBrain.Plan plan = brain.onFrame(f.px, f.w, f.h, now, false);
            if (plan == null && brain.wantsRecheck()) { now += 120; plan = brain.onFrame(f.px, f.w, f.h, now, true); }
            long t2 = System.nanoTime();
            renderNs += t1 - t0; brainNs += t2 - t1;
            frames++;
            if (DEBUG) System.out.println(now + " " + brain.status + " | " + brain.logLine() + (plan != null ? " PLAN " + plan.why : ""));
            glitchNext = false;
            if (brain.event == MineBrain.Event.STOP) { System.out.println("STOPPED: " + brain.eventText); break; }
            if (plan == null) { now += 50; continue; }
            // deliver the taps
            if (plan.cells.length == 0) {             // smiley
                g = new Game(cols, rows, mines, rnd);
                now += 60;
                brain.onTapsDone(now, true, "");
                now += 300;
                continue;
            }
            for (int c : plan.cells) {
                if (g.started && g.mine[c] && !wasGuess(plan)) wrongTaps++;
                g.click(c);
                now += 45;
            }
            brain.onTapsDone(now, true, "");
            pressed = plan.cells;
            glitchNext = rnd.nextInt(4) == 0;   // a quarter of the time, one stale/pressed frame first
            now += 40;
        }
        System.out.printf("pixels %dx%d/%d first=%s: %s, %d games, %d guesses, %d frames (%d glitch frames), wrong certain taps %d%n",
                cols, rows, mines, FIRST, brain.stats(), brain.wins + brain.losses, brain.guesses, frames, glitches, wrongTaps);
        System.out.printf("  per frame: render %.1f ms, bot %.2f ms; mean simulated win time n/a (tap pacing)%n",
                renderNs / 1e6 / frames, brainNs / 1e6 / frames);
    }

    static boolean wasGuess(MineBrain.Plan p) { return p.why.startsWith("guess") || p.why.startsWith("first"); }

    // ------------------------------------------------------------------ reader

    static void reader(int N) throws Exception {
        Random rnd = new Random(3);
        int tiles = 0, wrong = 0, boards = 0, counterWrong = 0, geomWrong = 0;
        int[][] sizes = {{9, 9, 12}, {9, 9, 10}, {16, 16, 40}, {16, 30, 99}, {8, 8, 10}, {12, 20, 40}};
        for (int t = 0; t < N; t++) {
            int[] sz = sizes[t % sizes.length];
            int cols = sz[0], rows = sz[1], mines = sz[2];
            Game g = new Game(cols, rows, mines, rnd);
            Render.Style st = Render.Style.random(rnd);
            int width = new int[]{540, 540, 480, 720, 360}[rnd.nextInt(5)];
            // fresh board first: geometry is locked on a new game
            Img f0 = Img.of(Render.draw(cols, rows, g.view(), mines, st, rnd), width);
            MineVision.Geometry geo = MineVision.findGrid(f0.px, f0.w, f0.h);
            boards++;
            if (geo == null || geo.cols != cols || geo.rows != rows) {
                geomWrong++;
                System.out.println("geometry wrong for " + cols + "x" + rows + " @" + width + ": " + geo);
                continue;
            }
            MineVision.Reading r0 = MineVision.read(f0.px, f0.w, f0.h, geo);
            if (r0.mines != mines) { counterWrong++; System.out.println("counter " + r0.mines + " != " + mines + " (" + cols + "x" + rows + ")"); }
            // play a few random safe clicks to get a mid-game board, sometimes lose, add some flags
            g.click(rnd.nextInt(cols * rows));
            for (int k = 0; k < rnd.nextInt(6); k++) {
                int c = rnd.nextInt(cols * rows);
                if (!g.mine[c] || rnd.nextInt(8) == 0) g.click(c);
            }
            int[] view = g.view();
            for (int i = 0; i < view.length; i++) if (view[i] == -1 && g.mine[i] && rnd.nextInt(3) == 0) view[i] = -2;
            Img f = Img.of(Render.draw(cols, rows, view, mines, st, rnd), width);
            MineVision.Reading r = MineVision.read(f.px, f.w, f.h, geo);
            for (int i = 0; i < view.length; i++) {
                tiles++;
                if (r.cell[i] != view[i]) {
                    wrong++;
                    if (wrong <= 25) System.out.printf("  tile %d,%d of %dx%d @%d: drew %d read %d (font %s %.2f line %d jit %d q %d)%n",
                            i % cols, i / cols, cols, rows, width, view[i], r.cell[i], st.font, st.fontScale, st.line, st.colorJitter, st.jpegQuality);
                }
            }
        }
        System.out.printf("reader: %d boards, geometry wrong %d, counter wrong %d, tiles %d, misread %d (%.3f%%)%n",
                boards, geomWrong, counterWrong, tiles, wrong, 100.0 * wrong / Math.max(1, tiles));
    }
}
