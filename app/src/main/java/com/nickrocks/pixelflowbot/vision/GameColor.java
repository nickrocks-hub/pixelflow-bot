package com.nickrocks.pixelflowbot.vision;

/** Colors used by cubes and characters. EMPTY = navy background (no cube / no character). */
public enum GameColor {
    EMPTY('.'), WHITE('W'), BLACK('K'), RED('R'), ORANGE('O'), YELLOW('Y'), GREEN('G'), DARK_GREEN('D'), BLUE('B'), PURPLE('P');

    public final char code;
    GameColor(char code) { this.code = code; }

    /** Thresholds measured on Level 24, 25 and 26. */
    public static GameColor classify(int r, int g, int b) {
        float max = Math.max(r, Math.max(g, b)) / 255f;
        float min = Math.min(r, Math.min(g, b)) / 255f;
        float v = max;
        float s = max == 0 ? 0 : (max - min) / max;
        float h = hue(r, g, b);
        // Strongly coloured but darker cubes (dark green: s 1.0, v 0.54-0.64).
        if (s > 0.75f && v >= 0.4f && v < 0.8f && h >= 70 && h < 170) return DARK_GREEN;
        if (v < 0.55f) return s < 0.26f ? BLACK : EMPTY;   // grey = black cube, bluish = navy background
        if (s < 0.18f) return WHITE;
        if (h < 12 || h >= 340) return RED;
        if (h < 42) return ORANGE;
        if (h < 70) return YELLOW;
        if (h < 170) return v < 0.8f && s > 0.75f ? DARK_GREEN : GREEN;
        if (h < 250) return BLUE;
        return PURPLE;
    }

    static float hue(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        if (d == 0) return 0;
        float h;
        if (max == rf) h = ((gf - bf) / d) % 6f;
        else if (max == gf) h = (bf - rf) / d + 2f;
        else h = (rf - gf) / d + 4f;
        h *= 60f;
        return h < 0 ? h + 360f : h;
    }
}
