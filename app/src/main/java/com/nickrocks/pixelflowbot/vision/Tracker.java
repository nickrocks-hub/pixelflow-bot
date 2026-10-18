package com.nickrocks.pixelflowbot.vision;

/**
 * Keeps the board grid across frames, as the app does live.
 * The grid is measured when gameplay starts (after any popup, map or loading screen),
 * and accepted once two frames in a row give the same grid (this skips the level-start
 * animation). It works from any point in a level.
 */
public final class Tracker {
    private BoardReader.Calibration calib, candidate;
    private GameState.Screen last = GameState.Screen.OTHER;
    private boolean pending = true;
    public int calibrations = 0;
    private GameColor[][] prevRaw;

    public BoardReader.Calibration calibration() { return calib; }
    public boolean isCalibrating() { return pending; }
    public void reset() { calib = null; candidate = null; pending = true; prevRaw = null; }

    public GameState update(Frame f) {
        GameState.Screen s = GameState.detectScreen(f);
        if (s == GameState.Screen.GAMEPLAY) {
            if (last != GameState.Screen.GAMEPLAY) { pending = true; candidate = null; }
            if (pending) {
                BoardReader.Calibration c = BoardReader.calibrate(f);
                if (c != null && c.closeTo(candidate)) {
                    calib = c; calibrations++; pending = false; candidate = null;
                } else {
                    candidate = c;
                }
            }
        }
        last = s;
        if (pending && s == GameState.Screen.GAMEPLAY) { prevRaw = null; return GameState.read(f, null); }
        GameState raw = GameState.read(f, calib);
        GameState out = raw.confirmedAgainst(prevRaw);
        prevRaw = raw.board;
        return out;
    }
}
