package com.nickrocks.aibot.core;

/**
 * What the bot does with the controls this tick. Sticks are held between ticks; a magnitude of 0
 * lets go of the stick. Buttons are single taps.
 */
public final class Action {
    /** MOVE stick direction, length 0..1 (0 = thumb off). */
    public float moveX, moveY;
    /** SING stick (aim + sing) direction, length 0..1. */
    public float aimX, aimY;
    public boolean sing;
    /** Index into the layout's 4 action buttons, or -1. */
    public int button = -1;
    public boolean ult;
    public String note = "";

    public static Action idle() { return new Action(); }

    public boolean moving() { return moveX * moveX + moveY * moveY > 0.01f; }

    public String describe() {
        StringBuilder b = new StringBuilder();
        if (moving()) b.append("move ").append(compass(moveX, moveY));
        else b.append("stand");
        if (sing) b.append(", sing ").append(compass(aimX, aimY));
        if (button >= 0) b.append(", button ").append(button + 1);
        if (ult) b.append(", ULT");
        return b.toString();
    }

    public static String compass(float x, float y) {
        if (x * x + y * y < 1e-4f) return "-";
        double a = Math.toDegrees(Math.atan2(-y, x));
        String[] n = {"right", "up-right", "up", "up-left", "left", "down-left", "down", "down-right"};
        int i = (int) Math.round(((a + 360) % 360) / 45.0) % 8;
        return n[i];
    }
}
