package com.nickrocks.pixelflowbot.game.blocks;

import com.nickrocks.pixelflowbot.learn.Env;

import java.util.Random;

/**
 * Block Puzzle simulator. Pieces come from the episode's seeded random stream, so a seed fixes the
 * whole game. Rewards are the game's own points (+1 per cell, +10 per line, combo bonus) and a
 * −20 penalty at game over; an episode also ends after {@link #MAX_PLACEMENTS} placements (counted
 * as a win: the board survived).
 */
public final class BlockEnv implements Env<BlockState> {
    public static final int MAX_PLACEMENTS = 300;
    public static final float R_GAME_OVER = -20f;

    private Random rnd;

    @Override
    public BlockState reset(Random seed) {
        rnd = new Random(seed.nextLong());
        return new BlockState(new boolean[Blocks.N * Blocks.N], deal(), 0, 0, 0, false);
    }

    private int[] deal() {
        return new int[]{rnd.nextInt(Blocks.SHAPES.length), rnd.nextInt(Blocks.SHAPES.length), rnd.nextInt(Blocks.SHAPES.length)};
    }

    @Override
    public Outcome<BlockState> step(BlockState s, Object action) {
        int a = (Integer) action;
        int slot = a / 64, r = (a % 64) / Blocks.N, c = a % Blocks.N;
        Outcome<BlockState> o = new Outcome<>();
        if (s.over || slot < 0 || slot > 2 || s.tray[slot] < 0 || !Blocks.fits(s.board, s.tray[slot], r, c)) {
            o.next = s;
            o.reward = -1f;
            o.note = "invalid placement";
            return o;
        }
        Blocks.Placed p = Blocks.place(s.board, s.tray[slot], r, c);
        int[] tray = s.tray.clone();
        tray[slot] = -1;
        if (tray[0] < 0 && tray[1] < 0 && tray[2] < 0) tray = deal();
        boolean any = false;
        for (int t : tray) if (t >= 0 && Blocks.fitsAnywhere(p.board, t)) { any = true; break; }
        int placed = s.placed + 1;
        BlockState n = new BlockState(p.board, tray, s.score + p.points, placed, s.lines + p.lines, !any);
        o.next = n;
        o.reward = p.points;
        o.note = p.lines > 0 ? "cleared " + p.lines + " line" + (p.lines > 1 ? "s" : "") : "placed";
        if (!any) {
            o.done = true;
            o.reward += R_GAME_OVER;
            o.note = "game over";
        } else if (placed >= MAX_PLACEMENTS) {
            o.done = true;
            o.win = true;
            o.note = "survived " + MAX_PLACEMENTS + " placements";
        }
        return o;
    }
}
