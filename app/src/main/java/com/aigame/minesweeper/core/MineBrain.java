package com.aigame.minesweeper.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The player: frame in, taps out. Pure Java (no Android), so the desktop simulator drives exactly
 * this code.
 *
 * Every step: read the board, make sure the picture has settled (the tiles it tapped have opened,
 * no half-drawn cascades, the same reading twice or a quiet screen), then tap every tile the
 * solver proves safe in one quick burst. Only when nothing is certain does it guess, always the
 * least risky tile. It never places flags, so there is nothing to undo and no mode to switch.
 */
public final class MineBrain {

    public static final class Params {
        public boolean firstTapCentre = false;   // false = a corner (most likely to be an opening)
        public boolean restartOnLoss = true;     // tap the smiley and play again after hitting a mine
        public boolean playAgainOnWin = false;
        public boolean allowGuess = true;
        public long settleMs = 1500;             // how long tapped tiles may take to open
    }

    /** What to do now. Taps are in frame pixels. */
    public static final class Plan {
        public final float[] xy;
        public final String why;
        public final int[] cells;
        Plan(float[] xy, int[] cells, String why) { this.xy = xy; this.cells = cells; this.why = why; }
        public int count() { return xy.length / 2; }
    }

    public enum Event { NONE, WON, LOST, STOP }

    private enum State { SEARCH, PLAY, TAPPING, WAITING, RESTARTING, DONE }

    public final Params params;
    public String status = "Looking for the board…";
    /** Set when the bot should stop by itself (won, stuck, or a problem the player must fix). */
    public Event event = Event.NONE;
    public String eventText = "";

    public int wins, losses, guesses, taps, games;
    public long lastWinMs = -1, bestWinMs = -1;

    private State state = State.SEARCH;
    private MineVision.Geometry geom;
    private MineVision.Reading prev;
    private int totalMines = -1;
    private Set<Integer> pending = new HashSet<>();
    private long tapsDoneAt, firstTapAt = -1, restartAt, unsettledSince = -1, waitingSince = -1;
    private int retries, restartTries, searchFails;
    private boolean lastWasGuess;
    private String lastLine = "";

    public MineBrain(Params p) { params = p; }

    public void reset() {
        state = State.SEARCH;
        geom = null;
        prev = null;
        pending.clear();
        firstTapAt = -1;
        totalMines = -1;
        unsettledSince = -1;
        waitingSince = -1;
        retries = 0;
        restartTries = 0;
        event = Event.NONE;
        status = "Looking for the board…";
    }

    public MineVision.Geometry geometry() { return geom; }

    /** True while the bot is waiting for the picture to settle: re-run on the last frame if the screen goes quiet. */
    public boolean wantsRecheck() { return state == State.PLAY || state == State.WAITING || state == State.SEARCH || state == State.RESTARTING; }

    public boolean tapping() { return state == State.TAPPING; }

    public String logLine() { return lastLine; }

    /** The taps of the last plan have all been delivered (or failed). */
    public void onTapsDone(long now, boolean ok, String note) {
        if (state != State.TAPPING) return;
        tapsDoneAt = now;
        if (!ok) {
            state = State.PLAY;
            pending.clear();
            status = "A tap didn't go through (" + note + ") — trying again";
            return;
        }
        state = restartPending ? State.RESTARTING : State.WAITING;
        restartPending = false;
        waitingSince = now;
    }

    private boolean restartPending;

    /**
     * One frame. {@code quiet} = the screen hasn't changed for a while (re-check of the last frame).
     */
    public Plan onFrame(int[] px, int w, int h, long now, boolean quiet) {
        if (state == State.DONE || state == State.TAPPING) return null;

        if (geom == null) {
            MineVision.Geometry g = MineVision.findGrid(px, w, h);
            if (g == null) {
                searchFails++;
                status = "Looking for the board… open a game";
                lastLine = "search: no board";
                return null;
            }
            geom = g;
            searchFails = 0;
            prev = null;
            if (state == State.SEARCH) state = State.PLAY;
        }

        MineVision.Reading rd = MineVision.read(px, w, h, geom);
        lastLine = rd.compact() + " m=" + rd.mines;

        // --- restarting: wait for a fresh board
        if (state == State.RESTARTING) {
            boolean fresh = rd.opened == 0 && rd.minesShown == 0 && rd.unreadable == 0;
            if (fresh && now - restartAt > 250) {
                newGame(rd);
                state = State.PLAY;
                prev = null;
                // fall through and play on this frame if it is stable enough
            } else {
                if (now - restartAt > 2500) {
                    if (++restartTries > 3) return stop("Couldn't start a new game — tap the smiley yourself, then START");
                    // board may have moved (new difficulty): look again, then tap the smiley again
                    geom = null;
                    return restartTap(rd, now, "smiley again");
                }
                status = "Starting a new game…";
                return null;
            }
        }

        if (rd.unreadable > 0) {
            if (unsettledSince < 0) unsettledSince = now;
            if (now - unsettledSince > 2500) {
                // maybe the board moved or a dialog is up: find it again
                geom = null;
                unsettledSince = now;
                if (rd.unreadable > rd.g.cols * rd.g.rows / 2) status = "Can't see the board (a message on top?)";
            } else status = "Something is covering " + rd.unreadable + " tile" + (rd.unreadable == 1 ? "" : "s") + "…";
            return null;
        }

        // --- game over?
        if (rd.minesShown > 0) {
            if (firstTapAt >= 0) {
                losses++;
                firstTapAt = -1;
                event = Event.LOST;
                eventText = lastWasGuess ? "Hit a mine on a forced guess" : "Hit a mine";
            }
            if (params.restartOnLoss && rd.smileX >= 0) return restartTap(rd, now, "lost — new game");
            return stop("Hit a mine. Tap the smiley for a new game, then START.");
        }

        // the mine total, read from the counter on the new board and kept for the game (the bot never
        // flags, so it doesn't change); a total that can't be right is ignored rather than trusted
        int nCells = geom.cols * geom.rows;
        if (totalMines < 0 && rd.mines > 0 && rd.mines + rd.flags < nCells && rd.opened == 0) totalMines = rd.mines + rd.flags;
        int mines = totalMines;
        if (mines < 0 && rd.mines > 0 && rd.mines + rd.flags < nCells) mines = rd.mines + rd.flags;

        // --- did the last taps land?
        if (state == State.WAITING) {
            int still = 0, flagged = 0;
            for (int c : pending) {
                int v = rd.cell[c];
                if (v == MineVision.UNKNOWN) still++;
                else if (v == MineVision.FLAG) flagged++;
            }
            if (flagged > 0) return stop("My taps placed flags — switch the game to dig mode (tap the mine/flag button), then START");
            if (still > 0) {
                if (now - tapsDoneAt < params.settleMs) { status = "Waiting for tiles to open…"; return null; }
                if (++retries > 2) return stop("Taps aren't opening tiles — is the game in front and in dig mode?");
                state = State.PLAY;   // tap them again below
            } else {
                retries = 0;
                state = State.PLAY;
            }
        }

        // --- settled? a 0 next to a closed tile means the cascade is still being drawn
        String bad = check(rd);
        if (bad != null) {
            if (unsettledSince < 0) unsettledSince = now;
            if (now - unsettledSince > 3000) return stop("The board doesn't add up (" + bad + ") — misread? Log saved.");
            status = "Waiting for the board to settle…";
            prev = rd;
            return null;
        }
        if (!quiet && !rd.sameCells(prev)) { prev = rd; return null; }   // same reading twice, or a quiet screen
        unsettledSince = -1;
        prev = rd;

        // --- won? (only on a settled picture: a tile drawn pressed-in must not count as opened)
        if (rd.opened > 0 && mines >= 0 && rd.unknown + rd.flags == mines) return won(rd, now);

        // --- think
        if (rd.opened == 0 && rd.flags == 0) {
            if (firstTapAt < 0) newGame(rd);
            int c = params.firstTapCentre ? (geom.rows / 2) * geom.cols + geom.cols / 2 : 0;
            lastWasGuess = true;
            return tap(new int[]{c}, now, "first tap");
        }
        MineSolver.Result res = MineSolver.solve(geom.cols, geom.rows, rd.cell, mines);
        if (res.contradiction) {
            if (unsettledSince < 0) unsettledSince = now;
            status = "Numbers don't fit — reading again…";
            return null;
        }
        if (!res.safe.isEmpty()) {
            int[] cells = new int[res.safe.size()];
            for (int i = 0; i < cells.length; i++) cells[i] = res.safe.get(i);
            lastWasGuess = false;
            return tap(cells, now, cells.length + " safe");
        }
        if (res.mines.size() == rd.unknown && rd.unknown > 0) return won(rd, now);   // only mines left
        if (res.guess < 0) return stop("Nothing left to tap");
        if (!params.allowGuess) return stop(String.format(Locale.US, "No safe tile — best guess %.0f%% risk (guessing is off)", res.guessRisk * 100));
        guesses++;
        lastWasGuess = true;
        return tap(new int[]{res.guess}, now, String.format(Locale.US, "guess %.0f%% risk", res.guessRisk * 100));
    }

    private void newGame(MineVision.Reading rd) {
        games++;
        firstTapAt = -1;
        int cells = geom.cols * geom.rows;
        totalMines = rd.mines > 0 && rd.mines + rd.flags < cells ? rd.mines + rd.flags : -1;
        retries = 0;
        restartTries = 0;
        pending.clear();
    }

    /** Problems that mean "not settled yet" (or misread): returns a description, or null if fine. */
    static String check(MineVision.Reading rd) {
        MineVision.Geometry g = rd.g;
        for (int r = 0; r < g.rows; r++) {
            for (int c = 0; c < g.cols; c++) {
                int v = rd.at(c, r);
                if (v < 0 || v > 8) continue;
                int closed = 0;
                for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                    if (dr == 0 && dc == 0) continue;
                    int x = c + dc, y = r + dr;
                    if (x < 0 || y < 0 || x >= g.cols || y >= g.rows) continue;
                    int u = rd.at(x, y);
                    if (u == MineVision.UNKNOWN || u == MineVision.FLAG) closed++;
                }
                if (v == 0 && closed > 0) return "0 at " + (c + 1) + "," + (r + 1) + " next to closed tiles";
                if (v > closed) return v + " at " + (c + 1) + "," + (r + 1) + " with only " + closed + " closed around";
            }
        }
        return null;
    }

    private Plan tap(int[] cells, long now, String why) {
        float[] xy = new float[cells.length * 2];
        for (int i = 0; i < cells.length; i++) {
            xy[2 * i] = geom.cx(cells[i] % geom.cols);
            xy[2 * i + 1] = geom.cy(cells[i] / geom.cols);
        }
        pending.clear();
        for (int c : cells) pending.add(c);
        if (firstTapAt < 0) firstTapAt = now;
        state = State.TAPPING;
        taps += cells.length;
        status = String.format(Locale.US, "%s · %.1f s", why, (now - firstTapAt) / 1000.0);
        lastLine += " => " + why;
        return new Plan(xy, cells, why);
    }

    private Plan restartTap(MineVision.Reading rd, long now, String why) {
        if (rd.smileX < 0) return stop("Can't find the smiley to start a new game");
        restartAt = now;
        state = State.TAPPING;
        restartPending = true;
        pending.clear();
        status = "New game…";
        lastLine += " => " + why;
        return new Plan(new float[]{rd.smileX, rd.smileY}, new int[0], why);
    }

    private Plan won(MineVision.Reading rd, long now) {
        if (firstTapAt < 0 && rd.smileX >= 0) return restartTap(rd, now, "finished board — new game");
        if (firstTapAt >= 0) {
            wins++;
            lastWinMs = now - firstTapAt;
            if (bestWinMs < 0 || lastWinMs < bestWinMs) bestWinMs = lastWinMs;
        }
        firstTapAt = -1;
        event = Event.WON;
        eventText = String.format(Locale.US, "Won in %.2f s", lastWinMs / 1000.0);
        status = eventText + " 🎉";
        if (params.playAgainOnWin && rd.smileX >= 0) return restartTap(rd, now, "won — next game");
        state = State.DONE;
        return null;
    }

    private Plan stop(String why) {
        state = State.DONE;
        event = Event.STOP;
        eventText = why;
        status = why;
        return null;
    }

    public String stats() {
        StringBuilder sb = new StringBuilder();
        sb.append(wins).append(" won · ").append(losses).append(" lost");
        if (bestWinMs >= 0) sb.append(String.format(Locale.US, " · best %.2f s", bestWinMs / 1000.0));
        return sb.toString();
    }

    /** For the desktop simulator: plain list of the last plan's cells. */
    public static List<Integer> cellsOf(Plan p) {
        List<Integer> l = new ArrayList<>();
        if (p != null) for (int c : p.cells) l.add(c);
        return l;
    }
}
