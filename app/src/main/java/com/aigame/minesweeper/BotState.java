package com.aigame.minesweeper;

import android.content.Context;
import android.content.SharedPreferences;

import com.aigame.minesweeper.core.MineBrain;

/** Process-wide switches shared by the activity, the capture service and the tap service. */
public final class BotState {
    private BotState() {}

    public static final String PREFS = "aigame_minesweeper";

    /** Screen capture is live. */
    public static volatile boolean captureActive = false;
    /** The bot is allowed to tap. */
    public static volatile boolean running = false;
    /** Ask the vision thread to start over (look for the board again). */
    public static volatile boolean resetRequested = false;
    /** Package of the app in front, as reported by the accessibility service. */
    public static volatile String foregroundPackage = "";
    /** The app the board was found in; taps only go there. */
    public static volatile String gamePackage = "";

    public static final String K_FIRST_CENTRE = "first_centre";
    public static final String K_RESTART = "restart_on_loss";
    public static final String K_AGAIN = "play_again_on_win";
    public static final String K_GUESS = "allow_guess";
    public static final String K_TAP_GAP = "tap_gap_ms";
    public static final String K_LOGS = "save_logs";
    public static final String K_PANEL_X = "panel_x";
    public static final String K_PANEL_Y = "panel_y";
    public static final String K_OVERLAY_HIDDEN = "overlay_hidden";
    public static final String K_LAST_GAME = "last_game_package";   // only used to reopen the game
    public static final String K_WINS = "wins";
    public static final String K_LOSSES = "losses";
    public static final String K_BEST = "best_ms";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void applyTo(SharedPreferences p, MineBrain.Params out) {
        out.firstTapCentre = p.getBoolean(K_FIRST_CENTRE, false);
        out.restartOnLoss = p.getBoolean(K_RESTART, true);
        out.playAgainOnWin = p.getBoolean(K_AGAIN, false);
        out.allowGuess = p.getBoolean(K_GUESS, true);
    }

    public static int tapGap(Context c) { return prefs(c).getInt(K_TAP_GAP, 45); }

    /** Taps only go to the game: never to this app, the home screen, or another app that came to the front. */
    public static boolean gameInFront(String ownPackage) {
        String fg = foregroundPackage;
        if (fg == null || fg.isEmpty()) return true;
        if (fg.equals(ownPackage)) return false;
        String game = gamePackage;
        return game == null || game.isEmpty() || game.equals(fg);
    }
}
