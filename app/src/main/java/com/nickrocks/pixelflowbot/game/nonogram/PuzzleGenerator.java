package com.nickrocks.pixelflowbot.game.nonogram;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Reproducible puzzle sets.
 *
 *  - Every puzzle comes from a seed derived from (split, size, index), so "E10-0007" is the same
 *    puzzle on every phone and every run.
 *  - A candidate picture is kept only if {@link LineSolver#countSolutions} proves exactly ONE
 *    solution within a fixed node budget (deterministic — no time limits). Otherwise the next
 *    attempt is tried, still derived from the same seed.
 *  - Training and evaluation use different seed salts, and a training puzzle whose clues match any
 *    evaluation puzzle of that size is skipped, so the two sets never overlap.
 */
public final class PuzzleGenerator {
    public static final int[] SIZES = {5, 10, 15};
    /** Evaluation set size per board size. Fixed, so results stay comparable between versions. */
    public static final int EVAL_COUNT_5 = 60, EVAL_COUNT_10 = 40, EVAL_COUNT_15 = 20;

    private static final long SALT_TRAIN = 0x7A11_9E37_79B9_7F4AL;
    private static final long SALT_EVAL = 0xE7A1_C2B2_AE3D_27D4L;
    private static final long SALT_PLAY = 0x5EED_1656_67B1_9E37L;

    private final Map<String, Puzzle> cache = new HashMap<>();
    private final Map<Integer, Set<String>> evalPrints = new HashMap<>();

    public static int evalCount(int size) {
        return size <= 5 ? EVAL_COUNT_5 : size <= 10 ? EVAL_COUNT_10 : EVAL_COUNT_15;
    }

    public static int nodeBudget(int size) {
        return size <= 5 ? 400 : size <= 10 ? 3000 : 6000;
    }

    /** SplitMix64: turns (salt, size, index) into a well-mixed seed. */
    public static long seedFor(Puzzle.Split split, int size, int index) {
        long salt = split == Puzzle.Split.TRAIN ? SALT_TRAIN : split == Puzzle.Split.EVAL ? SALT_EVAL : SALT_PLAY;
        return mix(salt ^ mix(size * 0x9E3779B97F4A7C15L + index));
    }

    static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Puzzle #index of a set. Cached. */
    public synchronized Puzzle get(Puzzle.Split split, int size, int index) {
        if (index < 0) throw new IllegalArgumentException("index must be ≥ 0");
        if (split == Puzzle.Split.EVAL && index >= evalCount(size)) {
            throw new IllegalArgumentException("evaluation set for " + size + "×" + size + " has " + evalCount(size) + " puzzles");
        }
        String key = split.code + size + ":" + index;
        Puzzle p = cache.get(key);
        if (p != null) return p;
        if (split == Puzzle.Split.EVAL) {
            evalFingerprints(size);           // builds the whole evaluation set, in order
            return cache.get(key);
        }
        p = build(split, size, index, seedFor(split, size, index), evalFingerprints(size));
        if (cache.size() > 2000) cache.keySet().removeIf(k -> !k.startsWith(Puzzle.Split.EVAL.code));
        cache.put(key, p);
        return p;
    }

    /** A one-off puzzle from any seed the player types in (still checked for uniqueness and kept out of evaluation). */
    public synchronized Puzzle fromSeed(int size, long seed) {
        return build(Puzzle.Split.PLAY, size, 0, seed, evalFingerprints(size));
    }

    /** Clue fingerprints of the whole evaluation set for a size (built once). */
    public synchronized Set<String> evalFingerprints(int size) {
        Set<String> s = evalPrints.get(size);
        if (s != null) return s;
        // Built in order, each puzzle skipping clues already in the set, so the set has no duplicates.
        s = new HashSet<>();
        for (int i = 0; i < evalCount(size); i++) {
            Puzzle p = build(Puzzle.Split.EVAL, size, i, seedFor(Puzzle.Split.EVAL, size, i), s);
            s.add(p.fingerprint());
            cache.put(Puzzle.Split.EVAL.code + size + ":" + i, p);
        }
        evalPrints.put(size, s);
        return s;
    }

    private static Puzzle build(Puzzle.Split split, int size, int index, long seed, Set<String> exclude) {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            Random rnd = new Random(mix(seed + attempt * 0x632BE59BD9B4E019L));
            boolean[] img = picture(rnd, size, size);
            LineSolver ls = new LineSolver(Clues.rows(img, size, size), Clues.cols(img, size, size));
            LineSolver.Count c = ls.countSolutions(null, nodeBudget(size));
            if (c.solutions != 1) continue;               // 0 can't happen, 2 = ambiguous, −1 = too hard to prove
            Puzzle p = new Puzzle(split, index, seed, attempt, size, size, img, c.nodes);
            if (exclude != null && exclude.contains(p.fingerprint())) continue;
            return p;
        }
        throw new IllegalStateException("couldn't generate a unique " + size + "×" + size + " puzzle from seed " + seed);
    }

    /**
     * A blobby random picture (noise, then a majority smoothing pass) — closer to the shapes in
     * real nonograms than plain noise, and much more often uniquely solvable.
     */
    static boolean[] picture(Random rnd, int rows, int cols) {
        float p = 0.48f + rnd.nextFloat() * 0.17f;
        boolean[] a = new boolean[rows * cols];
        for (int i = 0; i < a.length; i++) a[i] = rnd.nextFloat() < p;
        int passes = rows >= 10 && rnd.nextBoolean() ? 2 : 1;
        for (int pass = 0; pass < passes; pass++) {
            boolean[] b = new boolean[a.length];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int on = 0, tot = 0;
                    for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                        int rr = r + dr, cc = c + dc;
                        if (rr < 0 || cc < 0 || rr >= rows || cc >= cols) continue;
                        tot++;
                        if (a[rr * cols + cc]) on++;
                    }
                    b[r * cols + c] = on * 2 > tot || (on * 2 == tot && a[r * cols + c]);
                }
            }
            a = b;
        }
        return a;
    }
}
