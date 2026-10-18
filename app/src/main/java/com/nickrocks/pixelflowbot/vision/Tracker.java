package com.nickrocks.pixelflowbot.vision;

/**
 * Keeps the board calibration across frames, as the app does live.
 * Calibration is needed when gameplay starts (after a popup, map or loading screen)
 * or when the board looks bigger than before (a new, full board). It only happens once
 * the board has stopped moving for two frames in a row, so the level-start animation
 * (cubes dropping in) doesn't distort it, and only while the belt is empty (no cube is
 * being shot). If the belt never empties, it calibrates anyway after 10 frames.
 */
public final class Tracker {
    private BoardReader.Calibration calib;
    private GameState.Screen last = GameState.Screen.OTHER;
    private boolean pending = true;
    private int[] prevBox;
    private int pendingFrames = 0;
    public int calibrations = 0;

    public BoardReader.Calibration calibration() { return calib; }
    public boolean isCalibrating() { return pending; }
    public void reset() { calib = null; pending = true; prevBox = null; pendingFrames = 0; }

    public GameState update(Frame f) {
        GameState.Screen s = GameState.detectScreen(f);
        if (s == GameState.Screen.GAMEPLAY) {
            if (last != GameState.Screen.GAMEPLAY) pending = true;
            int[] box = BoardReader.boardBox(f);
            if (box != null && calib != null && !pending) {
                int area = (box[1] - box[0] + 1) * (box[3] - box[2] + 1);
                if (area > calib.area() * 1.05f) pending = true;
            }
            if (pending) {
                pendingFrames++;
                // Nothing on the belt means nothing is shooting, so no cube is mid-animation.
                boolean quiet = CharReader.readBeltFree(f) == 5 || pendingFrames >= 10;
                if (box != null && stable(box, prevBox) && quiet) {
                    BoardReader.Calibration c = BoardReader.calibrate(f);
                    if (c != null) { calib = c; calibrations++; pending = false; pendingFrames = 0; }
                }
            }
            prevBox = box;
        } else {
            prevBox = null;
        }
        last = s;
        if (pending && s == GameState.Screen.GAMEPLAY) return GameState.read(f, null);
        return GameState.read(f, calib);
    }

    private static boolean stable(int[] a, int[] b) {
        if (a == null || b == null) return false;
        for (int i = 0; i < 4; i++) if (Math.abs(a[i] - b[i]) > 3) return false;
        return true;
    }
}
