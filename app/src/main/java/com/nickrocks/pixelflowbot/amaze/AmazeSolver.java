package com.nickrocks.pixelflowbot.amaze;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which arrows can leave. An arrow can leave when no other arrow sits anywhere on the
 * straight line ahead of its head (to the edge of the board). Removing arrows only ever frees
 * lines, so any free arrow is always a safe move and the order never matters for winning.
 *
 * For speed it returns a batch: free arrows whose exit lines and bodies don't cross each other,
 * so they can be tapped back-to-back while earlier ones are still sliding out.
 */
public final class AmazeSolver {
    private AmazeSolver() { }

    /** True if the line ahead of the head is clear of every other arrow (and of its own body). */
    public static boolean free(AmazeVision.Board b, AmazeVision.Arrow a) {
        if (a.dir < 0) return false;                         // head is off screen
        if (b.cut != null && b.cut[a.dir]) return false;     // its way out runs into the hidden part
        for (int i : ray(b, a)) if (b.owner[i] >= 0) return false;
        return true;
    }

    /** Lattice points ahead of the head, out to the edge of the board. */
    public static List<Integer> ray(AmazeVision.Board b, AmazeVision.Arrow a) {
        List<Integer> out = new ArrayList<>();
        int c = a.head() % b.cols, r = a.head() / b.cols;
        while (true) {
            c += AmazeVision.DX[a.dir];
            r += AmazeVision.DY[a.dir];
            if (c < 0 || r < 0 || c >= b.cols || r >= b.rows) break;
            out.add(r * b.cols + c);
        }
        return out;
    }

    /** All arrows that can leave right now. */
    public static List<AmazeVision.Arrow> freeArrows(AmazeVision.Board b) {
        List<AmazeVision.Arrow> out = new ArrayList<>();
        for (AmazeVision.Arrow a : b.arrows) if (free(b, a)) out.add(a);
        return out;
    }

    /**
     * Free arrows that can be tapped one after another without waiting: none of them crosses
     * another's body or exit line. Up to {@code max}.
     */
    public static List<AmazeVision.Arrow> batch(AmazeVision.Board b, int max) {
        List<AmazeVision.Arrow> out = new ArrayList<>();
        boolean[] used = new boolean[b.cols * b.rows];
        for (AmazeVision.Arrow a : freeArrows(b)) {
            if (out.size() >= max) break;
            List<Integer> path = ray(b, a);
            boolean clash = false;
            for (int i : a.cells) if (used[i]) clash = true;
            for (int i : path) if (used[i]) clash = true;
            if (clash) continue;
            for (int i : a.cells) used[i] = true;
            for (int i : path) used[i] = true;
            out.add(a);
        }
        return out;
    }

    /**
     * Simulates clearing the board (repeatedly removing free arrows). Returns how many arrows
     * remain stuck at the end: 0 means the board as read is fully solvable.
     */
    public static int stuckAfterClearing(AmazeVision.Board b) {
        if (b.cutOff) return 0;   // can't judge a board we can't fully see
        int[] owner = b.owner.clone();
        boolean[] gone = new boolean[b.arrows.size()];
        boolean progress = true;
        int left = b.arrows.size();
        AmazeVision.Board tmp = new AmazeVision.Board();
        tmp.cols = b.cols; tmp.rows = b.rows; tmp.owner = owner;
        while (progress) {
            progress = false;
            for (AmazeVision.Arrow a : b.arrows) {
                if (gone[a.id]) continue;
                if (free(tmp, a)) {
                    gone[a.id] = true;
                    for (int i : a.cells) owner[i] = -1;
                    left--;
                    progress = true;
                }
            }
        }
        return left;
    }

    /** Where to tap an arrow: on its line, just behind the arrowhead. */
    public static float[] tapPoint(AmazeVision.Board b, AmazeVision.Arrow a) {
        int hd = a.head(), prev = a.cells[a.cells.length - 2];
        float hx = b.px(hd), hy = b.py(hd), px = b.px(prev), py = b.py(prev);
        return new float[]{hx + (px - hx) * 0.4f, hy + (py - hy) * 0.4f};
    }
}
