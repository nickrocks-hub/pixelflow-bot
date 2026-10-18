package com.nickrocks.pixelflowbot.game.blocks;

import com.nickrocks.pixelflowbot.core.GameProfile;
import com.nickrocks.pixelflowbot.core.LabGame;
import com.nickrocks.pixelflowbot.core.Perception;
import com.nickrocks.pixelflowbot.core.StateObservation;
import com.nickrocks.pixelflowbot.learn.Env;
import com.nickrocks.pixelflowbot.learn.GameAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Block Puzzle on the shared core. The learner sees, for every legal placement, features of the
 * board that placement would leave (lines cleared, holes, fragmentation, room for big pieces…)
 * and learns from the game's points how much each matters. The features are measurements, not
 * advice: their weights start at zero.
 */
public final class BlockGame implements LabGame<BlockState> {
    public static final String ID = "blocks-sim";

    static final String[] FEATURES = {
            "bias", "lines cleared", "piece size", "board filled", "isolated holes", "empty regions",
            "filled/empty edges", "3×3 still fits", "3×3 placements", "1×5 row still fits", "5×1 column still fits",
            "tray pieces still placeable", "contact (edges touching)", "leaves no move (game over)", "cramped empty cells"};

    private final Adapter adapter = new Adapter();

    @Override public String id() { return ID; }
    @Override public String title() { return "Block Puzzle"; }
    @Override public GameProfile.Pace pace() { return GameProfile.Pace.TURN_BASED; }
    @Override public long tickMs() { return 0; }
    @Override public GameAdapter<BlockState> adapter() { return adapter; }
    @Override public Env<BlockState> newEnv() { return new BlockEnv(); }
    @Override public double score(BlockState s) { return s.score; }
    @Override public String scoreName() { return "score"; }
    @Override public int maxSteps() { return BlockEnv.MAX_PLACEMENTS + 5; }

    @Override
    public void configure(com.nickrocks.pixelflowbot.learn.Agent a) {
        a.model.normalize = true;
        a.model.alpha = 0.02f;
        a.model.gamma = 0.9f;
        a.epsStart = 0.2f;
        a.epsEnd = 0.01f;
        a.epsDecayEpisodes = 300;
    }

    // ------------------------------------------------------------------ learner adapter

    static final class Adapter implements GameAdapter<BlockState> {
        @Override public String id() { return ID; }
        @Override public String title() { return "Block Puzzle"; }
        @Override public String[] featureNames() { return FEATURES; }

        @Override
        public List<Candidate> candidates(BlockState s) {
            List<Candidate> out = new ArrayList<>();
            if (s.over) return out;
            for (int slot = 0; slot < 3; slot++) {
                int sh = s.tray[slot];
                if (sh < 0) continue;
                boolean dup = false;
                for (int k = 0; k < slot; k++) if (s.tray[k] == sh) dup = true;   // same piece twice: same moves
                if (dup) continue;
                for (int r = 0; r < Blocks.N; r++) {
                    for (int c = 0; c < Blocks.N; c++) {
                        if (!Blocks.fits(s.board, sh, r, c)) continue;
                        out.add(new Candidate(String.format(Locale.US, "piece %d at r%d c%d", slot + 1, r + 1, c + 1),
                                features(s, slot, r, c), BlockState.encode(slot, r, c)));
                    }
                }
            }
            return out;
        }

        @Override
        public String describe(BlockState s) {
            return "score " + s.score + " placed " + s.placed + " filled " + s.filled();
        }
    }

    /** Features of the board left by placing tray[slot] at (r, c). */
    static float[] features(BlockState s, int slot, int r, int c) {
        int sh = s.tray[slot];
        Blocks.Placed p = Blocks.place(s.board, sh, r, c);
        boolean[] b = p.board;
        int N = Blocks.N;
        float[] f = new float[FEATURES.length];
        f[0] = 1;
        f[1] = p.lines;
        f[2] = Blocks.size(sh) / 5f;
        int filled = 0, isolated = 0, cramped = 0, transitions = 0;
        for (int i = 0; i < N; i++) {
            for (int k = 0; k < N; k++) {
                boolean v = b[i * N + k];
                if (v) filled++;
                // transitions along rows and columns (walls count as filled)
                boolean right = k + 1 < N ? b[i * N + k + 1] : true, down = i + 1 < N ? b[(i + 1) * N + k] : true;
                if (k == 0 && !v) transitions++;
                if (i == 0 && !v) transitions++;
                if (v != right) transitions++;
                if (v != down) transitions++;
                if (!v) {
                    int closed = 0;
                    if (i == 0 || b[(i - 1) * N + k]) closed++;
                    if (i == N - 1 || b[(i + 1) * N + k]) closed++;
                    if (k == 0 || b[i * N + k - 1]) closed++;
                    if (k == N - 1 || b[i * N + k + 1]) closed++;
                    if (closed == 4) isolated++;
                    else if (closed == 3) cramped++;
                }
            }
        }
        f[3] = filled / 64f;
        f[4] = isolated / 8f;
        f[5] = regions(b) / 8f;
        f[6] = transitions / 64f;
        int fits3 = Blocks.placements(b, 10);
        f[7] = fits3 > 0 ? 1 : 0;
        f[8] = fits3 / 36f;
        f[9] = Blocks.fitsAnywhere(b, 7) ? 1 : 0;
        f[10] = Blocks.fitsAnywhere(b, 8) ? 1 : 0;
        int remaining = 0, fitting = 0;
        for (int k = 0; k < 3; k++) {
            if (k == slot || s.tray[k] < 0) continue;
            remaining++;
            if (Blocks.fitsAnywhere(b, s.tray[k])) fitting++;
        }
        f[11] = remaining == 0 ? 1 : fitting / (float) remaining;
        // contact: edges of the placed piece touching filled cells or walls (on the board before clearing)
        int touch = 0, edges = 0;
        for (int[] d : Blocks.SHAPES[sh]) {
            int rr = r + d[0], cc = c + d[1];
            int[][] nb = {{rr - 1, cc}, {rr + 1, cc}, {rr, cc - 1}, {rr, cc + 1}};
            for (int[] q : nb) {
                if (inPiece(sh, r, c, q[0], q[1])) continue;
                edges++;
                if (q[0] < 0 || q[1] < 0 || q[0] >= N || q[1] >= N || s.board[q[0] * N + q[1]]) touch++;
            }
        }
        f[12] = edges == 0 ? 0 : touch / (float) edges;
        f[13] = remaining > 0 && fitting == 0 ? 1 : 0;
        f[14] = cramped / 16f;
        return f;
    }

    private static boolean inPiece(int sh, int r, int c, int rr, int cc) {
        for (int[] d : Blocks.SHAPES[sh]) if (r + d[0] == rr && c + d[1] == cc) return true;
        return false;
    }

    /** Number of 4-connected empty regions. */
    static int regions(boolean[] b) {
        int N = Blocks.N, n = 0;
        boolean[] seen = new boolean[N * N];
        int[] stack = new int[N * N];
        for (int i = 0; i < N * N; i++) {
            if (b[i] || seen[i]) continue;
            n++;
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            while (sp > 0) {
                int x = stack[--sp], r = x / N, c = x % N;
                int[] nb = {r > 0 ? x - N : -1, r < N - 1 ? x + N : -1, c > 0 ? x - 1 : -1, c < N - 1 ? x + 1 : -1};
                for (int y : nb) if (y >= 0 && !b[y] && !seen[y]) { seen[y] = true; stack[sp++] = y; }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ baselines

    @Override
    public List<Baseline<BlockState>> baselines() {
        List<Baseline<BlockState>> l = new ArrayList<>();
        l.add(new Baseline<BlockState>() {
            @Override public String name() { return "Random"; }
            @Override public int choose(BlockState s, List<GameAdapter.Candidate> c, Random rnd) { return rnd.nextInt(c.size()); }
        });
        l.add(new Baseline<BlockState>() {
            // A fixed, hand-written scoring rule (not learned): clear lines, fill snugly, avoid holes,
            // keep room for the 3×3 and long pieces, never leave yourself without a move.
            final float[] w = {0, 10, 0, -2, -6, -2, -3, 4, 1, 2, 2, 4, 3, -30, -1};
            @Override public String name() { return "Hand-written rule"; }
            @Override public int choose(BlockState s, List<GameAdapter.Candidate> c, Random rnd) {
                int best = 0;
                float bv = -Float.MAX_VALUE;
                for (int i = 0; i < c.size(); i++) {
                    float v = 0;
                    float[] f = c.get(i).features;
                    for (int k = 0; k < w.length; k++) v += w[k] * f[k];
                    if (v > bv) { bv = v; best = i; }
                }
                return best;
            }
        });
        return l;
    }

    // ------------------------------------------------------------------ perception (exact: our own game)

    @Override
    public Perception<BlockState> perception() {
        return (s, p) -> {
            StateObservation o = new StateObservation(p.id, p.version, System.currentTimeMillis());
            o.screen = s.over ? StateObservation.Screen.LOSS : StateObservation.Screen.GAMEPLAY;
            o.confidence = 1f;
            o.detail = s;
            o.add("score", s.score, 1, StateObservation.Source.OBSERVED);
            o.add("placed", s.placed, 1, StateObservation.Source.OBSERVED);
            o.add("lines", s.lines, 1, StateObservation.Source.OBSERVED);
            o.add("filled cells", s.filled(), 1, StateObservation.Source.OBSERVED);
            StringBuilder tray = new StringBuilder();
            int fit = 0;
            for (int t : s.tray) {
                tray.append(t < 0 ? "–" : String.valueOf(Blocks.size(t))).append(' ');
                if (t >= 0 && Blocks.fitsAnywhere(s.board, t)) fit++;
            }
            o.add("tray (piece sizes)", tray.toString().trim(), 1, StateObservation.Source.OBSERVED);
            o.add("pieces that fit", fit, 1, StateObservation.Source.INFERRED);
            o.add("isolated holes", (int) (features0(s) * 8), 1, StateObservation.Source.INFERRED);
            return o;
        };
    }

    private static float features0(BlockState s) {
        int N = Blocks.N, n = 0;
        for (int i = 0; i < N; i++) for (int k = 0; k < N; k++) {
            if (s.board[i * N + k]) continue;
            boolean u = i == 0 || s.board[(i - 1) * N + k], d = i == N - 1 || s.board[(i + 1) * N + k];
            boolean l = k == 0 || s.board[i * N + k - 1], r = k == N - 1 || s.board[i * N + k + 1];
            if (u && d && l && r) n++;
        }
        return n / 8f;
    }
}
