package com.nickrocks.pixelflowbot.core.mc;

/**
 * Where things are on Minecraft (Bedrock, Android) screens, measured on the user's phone
 * (2520×1080 landscape, default GUI scale, "touch" controls). Every position is in that reference
 * space; {@link #sx}/{@link #sy} scale it to the frame actually captured.
 */
public final class McLayout {
    private McLayout() { }

    public static final float REF_W = 2520f, REF_H = 1080f;

    public static float sx(int frameW) { return frameW / REF_W; }
    public static float sy(int frameH) { return frameH / REF_H; }

    // ---------------------------------------------------------------- in the world (HUD)
    /** Centre of hotbar slot i (0..8) on the HUD. */
    public static float hudSlotX(int i) { return 810 + 100 * i; }
    public static final float HUD_SLOT_Y = 1016;
    /** The "…" button at the right end of the hotbar: opens inventory + crafting. */
    public static final float INV_BTN_X = 1710, INV_BTN_Y = 1016;
    /** Heart i (0..9): box x0..x1, y0..y1. */
    public static float heartX0(int i) { return 16 + 40 * i; }
    public static final float HEART_W = 34, HEART_Y0 = 16, HEART_Y1 = 49;

    /** Joystick (newer touch layout): base centre and how far the knob travels. */
    public static final float JOY_X = 377, JOY_Y = 593, JOY_REACH = 190;
    /** D-pad (classic touch layout): the forward arrow. */
    public static final float DPAD_UP_X = 302, DPAD_UP_Y = 633;

    /** Where camera swipes happen: empty sky/world area right of centre, clear of the buttons. */
    public static final float LOOK_X = 1480, LOOK_Y = 300;
    public static final float LOOK_MAX = 700;      // longest single swipe

    /** Screen centre (the bot turns targets to here before walking). */
    public static final float CX = 1260, CY = 540;

    /** HUD rectangles that are never part of the world view: {x0,y0,x1,y1}. */
    public static final float[][] HUD_RECTS = {
            {0, 0, 2520, 64},          // hearts, hunger, top buttons
            {740, 930, 1790, 1080},    // hotbar
            {150, 330, 640, 820},      // joystick / d-pad
            {1980, 330, 2440, 720},    // sprint, jump, sneak
    };

    // ---------------------------------------------------------------- inventory / crafting screen
    /** Recipe book cell (row r, column c): centre. 7 columns, 4 rows fully visible. */
    public static float recipeX(int c) { return 264 + 140 * c; }
    public static float recipeY(int r) { return 287 + 140 * r; }
    public static final int RECIPE_COLS = 7, RECIPE_ROWS = 4;
    /** Size of a slot (recipe cell, hotbar slot) on this screen. */
    public static final float CELL = 136;
    /** Hotbar as shown at the bottom of the crafting screen. */
    public static float invSlotX(int i) { return 700 + 140 * i; }
    public static final float INV_SLOT_Y = 990;
    /** Output slot of the 2×2 (inventory) and 3×3 (crafting table) grids. */
    public static final float OUT2_X = 1806, OUT2_Y = 740;
    public static final float OUT3_X = 1806, OUT3_Y = 795;
    /** Close (×) button, top right. */
    public static final float CLOSE_X = 2420, CLOSE_Y = 84;
}
