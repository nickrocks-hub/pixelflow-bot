package com.nickrocks.pixelflowbot;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;

/** One palette and a few drawable helpers so the app and the floating panel look like one product. */
final class Ui {
    private Ui() { }

    // Surfaces
    static final int BG = 0xFF0A0E1F;            // app background (deep navy)
    static final int BG_2 = 0xFF151A35;          // gradient end
    static final int CARD = 0xFF141A33;
    static final int CARD_STROKE = 0x332DD4BF;
    static final int PANEL_TOP = 0xF00B1022;
    static final int PANEL_BOTTOM = 0xF01B1640;
    static final int PANEL_STROKE = 0x8838BDF8;

    // Text
    static final int TEXT = 0xFFF1F5F9;
    static final int TEXT_DIM = 0xFFB8C2D9;
    static final int TEXT_FAINT = 0xFF7C87A6;
    static final int NEON = 0xFF67E8F9;          // status line / highlights

    // Actions
    static final int ACCENT = 0xFF14B8A6;        // go / start (teal → cyan gradient)
    static final int DANGER = 0xFFF43F5E;        // stop
    static final int INFO = 0xFF6366F1;          // read / inspect
    static final int MUTED = 0xFF2B3354;         // secondary buttons

    // Per-game colours
    static final int GAME_PIXELFLOW = 0xFF8B5CF6;
    static final int GAME_CANDY = 0xFFEC4899;
    static final int GAME_NONO = 0xFF0EA5E9;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);
    static final Typeface BLACK = Typeface.create("sans-serif-black", Typeface.NORMAL);

    /** Colour mixed toward white by t (0..1). */
    static int lighten(int c, float t) {
        int a = Color.alpha(c);
        int r = Math.round(Color.red(c) + (255 - Color.red(c)) * t);
        int g = Math.round(Color.green(c) + (255 - Color.green(c)) * t);
        int b = Math.round(Color.blue(c) + (255 - Color.blue(c)) * t);
        return Color.argb(a, r, g, b);
    }

    /** Hue-shifted partner colour for two-tone gradients (teal→cyan, pink→orange, …). */
    static int partner(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.25f) return lighten(c, 0.12f);
        hsv[0] = (hsv[0] + 28f) % 360f;
        hsv[2] = Math.min(1f, hsv[2] * 1.08f);
        return Color.HSVToColor(Color.alpha(c), hsv);
    }

    /** Rounded two-tone gradient pill. */
    static GradientDrawable pill(int color, float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{color, partner(color)});
        d.setCornerRadius(radius);
        return d;
    }

    /** Pill with a touch ripple. */
    static RippleDrawable button(int color, float radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), pill(color, radius), mask);
    }

    /** Re-colours a button made with {@link #button}. */
    static void tint(android.view.View v, int color, float radius) {
        v.setBackground(button(color, radius));
    }

    static GradientDrawable card(float radius, int stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{CARD, 0xFF101530});
        d.setCornerRadius(radius);
        d.setStroke(stroke, strokeColor);
        return d;
    }
}
