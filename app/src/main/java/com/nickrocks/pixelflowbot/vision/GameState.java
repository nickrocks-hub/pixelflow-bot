package com.nickrocks.pixelflowbot.vision;

import java.util.EnumMap;
import java.util.Map;

/** Everything the bot can see in one frame. */
public final class GameState {
    public enum Screen { GAMEPLAY, POPUP, OTHER }

    public final Screen screen;
    public final BoardReader.Calibration calib;   // null unless gameplay
    public final GameColor[][] board;             // null unless gameplay
    public final CharReader.Character[] slots, queue;
    public final int beltFree;

    private GameState(Screen screen, BoardReader.Calibration calib, GameColor[][] board,
                      CharReader.Character[] slots, CharReader.Character[] queue, int beltFree) {
        this.screen = screen; this.calib = calib; this.board = board;
        this.slots = slots; this.queue = queue; this.beltFree = beltFree;
    }

    /**
     * Gameplay shows navy background at four fixed points. Popups (win, out of space, you failed)
     * darken them to near-black; the map is bright blue; loading is orange.
     */
    public static Screen detectScreen(Frame f) {
        float[][] pts = {{30, 1700}, {1050, 1700}, {30, 2000}, {1050, 2000}};
        int navy = 0, dim = 0;
        for (float[] p : pts) {
            int x = f.X(p[0]), y = f.Y(p[1]);
            int r = f.r(x, y), g = f.g(x, y), b = f.b(x, y);
            if (Math.abs(r - 57) < 14 && Math.abs(g - 60) < 14 && Math.abs(b - 98) < 16) navy++;
            else if (r < 30 && g < 30 && b < 40) dim++;
        }
        if (navy == 4) return Screen.GAMEPLAY;
        if (dim >= 3) return Screen.POPUP;
        return Screen.OTHER;
    }

    /** Reads a frame. Pass the level's calibration (from BoardReader.calibrate on a full board). */
    public static GameState read(Frame f, BoardReader.Calibration calib) {
        Screen s = detectScreen(f);
        if (s != Screen.GAMEPLAY || calib == null) return new GameState(s, null, null, null, null, -1);
        return new GameState(s, calib, BoardReader.read(f, calib),
                CharReader.readSlots(f), CharReader.readQueue(f), CharReader.readBeltFree(f));
    }

    /**
     * Same state, but a cell counts as a cube only if the previous read saw the same colour there.
     * Shots and falling-cube animations last a split second, so they are filtered out.
     */
    GameState confirmedAgainst(GameColor[][] prev) {
        if (board == null || prev == null || prev.length != board.length || prev[0].length != board[0].length) return this;
        GameColor[][] b = new GameColor[board.length][board[0].length];
        for (int r = 0; r < b.length; r++)
            for (int c = 0; c < b[0].length; c++)
                b[r][c] = board[r][c] == prev[r][c] ? board[r][c] : GameColor.EMPTY;
        return new GameState(screen, calib, b, slots, queue, beltFree);
    }

    public Map<GameColor, Integer> cubeCounts() {
        Map<GameColor, Integer> m = new EnumMap<>(GameColor.class);
        if (board == null) return m;
        for (GameColor[] row : board) for (GameColor c : row) if (c != GameColor.EMPTY) m.merge(c, 1, Integer::sum);
        return m;
    }

    public int slotsUsed() {
        int n = 0;
        if (slots != null) for (CharReader.Character c : slots) if (c != null) n++;
        return n;
    }

    /** Short multi-line summary for the floating panel. */
    public String summary() {
        if (screen != Screen.GAMEPLAY) return "Screen: " + screen.name().toLowerCase() + " (bot would stop)";
        if (calib == null) return "Gameplay, board not calibrated";
        StringBuilder sb = new StringBuilder();
        sb.append("Board ").append(calib).append(": ");
        for (Map.Entry<GameColor, Integer> e : cubeCounts().entrySet())
            sb.append(e.getKey().code).append(e.getValue()).append(' ');
        sb.append("\nSlots ").append(slotsUsed()).append("/5: ").append(list(slots));
        sb.append("\nQueue: ").append(list(queue));
        sb.append("\nBelt free: ").append(beltFree < 0 ? "?" : String.valueOf(beltFree)).append("/5");
        return sb.toString();
    }

    private static String list(CharReader.Character[] cs) {
        StringBuilder sb = new StringBuilder();
        for (CharReader.Character c : cs) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c == null ? "-" : c.color.code + "" + c.shots);
        }
        return sb.toString();
    }
}
