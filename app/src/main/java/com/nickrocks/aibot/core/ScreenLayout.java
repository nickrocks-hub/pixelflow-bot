package com.nickrocks.aibot.core;

import java.util.Locale;

/**
 * Where the game's HUD and touch controls are, as fractions of the screen (0..1).
 * Defaults were measured from a screen recording of the game on a Galaxy Z Fold (unfolded,
 * 1200x1080 recording of a 2184x1968 screen). If the game's "Touch Layout" is changed, or the game
 * runs on a different screen shape, the controls can be moved in the bot's calibration screen.
 */
public final class ScreenLayout {

    /** Joystick centres and the radius the thumb travels (fraction of screen height). */
    public float moveX = 0.169f, moveY = 0.831f;
    public float singX = 0.561f, singY = 0.831f;
    public float stickR = 0.062f;

    /** The four action buttons and the ULT star. Which button does what is checked by a button test. */
    public final float[] btnX = {0.788f, 0.899f, 0.8175f, 0.9225f};
    public final float[] btnY = {0.921f, 0.882f, 0.806f, 0.753f};
    public float ultX = 0.928f, ultY = 0.637f;
    public float pauseX = 0.967f, pauseY = 0.034f;

    /** HUD regions. */
    public float heartsX0 = 0.083f, heartsX1 = 0.40f, heartsY = 0.027f;
    public float ultBarX0 = 0.086f, ultBarX1 = 0.218f, ultBarY = 0.052f;
    public float portraitX = 0.042f, portraitY = 0.046f, portraitR = 0.040f;
    public float mapX0 = 0.756f, mapY0 = 0.016f, mapX1 = 0.926f, mapY1 = 0.128f;
    public float mapPitchX = 0.025f, mapPitchY = 0.0208f;
    /** Everything above this line is HUD, never playfield. */
    public float hudBottom = 0.145f;

    /** Default names for the 4 buttons, in the order of btnX (bottom-left mic, big guitar, disc, rings). */
    public static final String[] DEFAULT_BUTTON_NAMES = {"DIVE", "SMASH", "DROP", "SCREAM"};
    public final String[] btnRole = DEFAULT_BUTTON_NAMES.clone();

    public static final String[] ROLES = {"DIVE", "SMASH", "DROP", "SCREAM"};

    public int buttonFor(String role) {
        for (int i = 0; i < 4; i++) if (role.equals(btnRole[i])) return i;
        return -1;
    }

    public String serialize() {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f", moveX, moveY, singX, singY, stickR, ultX, ultY, 0f));
        for (int i = 0; i < 4; i++) b.append(String.format(Locale.US, ",%.4f,%.4f,%s", btnX[i], btnY[i], btnRole[i]));
        return b.toString();
    }

    public static ScreenLayout parse(String s) {
        ScreenLayout l = new ScreenLayout();
        if (s == null || s.isEmpty()) return l;
        try {
            String[] p = s.split(",");
            l.moveX = f(p[0]); l.moveY = f(p[1]); l.singX = f(p[2]); l.singY = f(p[3]); l.stickR = f(p[4]);
            l.ultX = f(p[5]); l.ultY = f(p[6]);
            for (int i = 0; i < 4; i++) {
                l.btnX[i] = f(p[8 + i * 3]); l.btnY[i] = f(p[9 + i * 3]); l.btnRole[i] = p[10 + i * 3];
            }
        } catch (RuntimeException e) {
            return new ScreenLayout();
        }
        return l;
    }

    private static float f(String s) { return Float.parseFloat(s.trim()); }

    public ScreenLayout copy() { return parse(serialize()); }
}
