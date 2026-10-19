package com.aigame.subwaysurfers;

import android.content.Context;
import android.content.SharedPreferences;

import com.aigame.subwaysurfers.core.SurferBrain;

/** Process-wide switches shared by the activity, the capture service and the swipe service. */
public final class BotState {
    private BotState() {}

    public static final String GAME_PACKAGE = "com.kiloo.subwaysurf";
    public static final String PREFS = "aigame_subway";

    /** Screen capture is live. */
    public static volatile boolean captureActive = false;
    /** The bot is allowed to swipe. */
    public static volatile boolean running = false;
    /** Ask the vision thread to forget its state (new run). */
    public static volatile boolean resetRequested = false;
    /** Package of the app in front, as reported by the accessibility service. */
    public static volatile String foregroundPackage = "";

    // preference keys
    // v1.1: new key so phones that saved the old 420 ms default start from the new, earlier default
    public static final String K_REACT = "react_ms_v2";
    public static final String K_LATENCY = "latency_ms";
    public static final String K_COINS = "seek_coins";
    public static final String K_LOGS = "save_logs";
    public static final String K_PANEL_X = "panel_x";
    public static final String K_PANEL_Y = "panel_y";
    public static final String K_SAFE = "safe_extra_ms";
    public static final String K_LANE_CD = "lane_cooldown_ms";
    public static final String K_JUMP_LOW = "jump_low";
    public static final String K_MIDDLE = "prefer_middle";
    public static final String K_OFFSET = "runner_offset_pct";
    public static final String K_SWIPE_Y = "swipe_y_pct";
    public static final String K_ONLY_GAME = "only_in_game";

    /** Off = swipe whatever app is in front (for phones where detection gets it wrong). */
    public static volatile boolean onlyInGame = true;

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Copies the saved settings into the brain's parameters. */
    public static void applyTo(SharedPreferences p, SurferBrain.Params out) {
        out.reactMs = p.getInt(K_REACT, 650);
        out.latencyMs = p.getInt(K_LATENCY, 120);
        out.seekCoins = p.getBoolean(K_COINS, true);
        out.safeExtraMs = p.getInt(K_SAFE, 350);
        out.laneCooldownMs = p.getInt(K_LANE_CD, 230);
        out.jumpLowObstacles = p.getBoolean(K_JUMP_LOW, true);
        out.preferMiddle = p.getBoolean(K_MIDDLE, true);
        out.runnerOffsetPct = p.getInt(K_OFFSET, 0);
        onlyInGame = p.getBoolean(K_ONLY_GAME, true);
    }

    /** Only swipe while the game itself is in front (never on the home screen, ads, other apps). */
    public static boolean gameInFront() {
        if (!onlyInGame) return true;
        String fg = foregroundPackage;
        return fg == null || fg.isEmpty() || GAME_PACKAGE.equals(fg);
    }
}
