package com.riderbot.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.riderbot.app.core.RiderBrain;

/** Process-wide switches shared by the activity, the capture service and the accessibility service. */
public final class BotState {
    private BotState() {}

    public static final String RIDER_PACKAGE = "com.ketchapp.rider";
    public static final String PREFS = "riderbot";

    /** Screen capture is live. */
    public static volatile boolean captureActive = false;
    /** The bot is allowed to touch the screen. */
    public static volatile boolean running = false;
    /** Ask the vision thread to forget its state (new run). */
    public static volatile boolean resetRequested = false;

    // preference keys
    public static final String K_LATENCY = "latency_ms";
    public static final String K_MARGIN = "flip_margin_ms";
    public static final String K_TOL = "upright_tol";
    public static final String K_FLIPS = "do_flips";
    public static final String K_MAXFLIPS = "max_flips";
    public static final String K_TOUCH_Y = "touch_y_pct";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Copies the saved settings into a brain's parameters. */
    public static void applyTo(SharedPreferences p, RiderBrain.Params out) {
        out.latencyMs = p.getInt(K_LATENCY, 90);
        out.flipMarginMs = p.getInt(K_MARGIN, 160);
        out.uprightTolDeg = p.getInt(K_TOL, 28);
        out.doFlips = p.getBoolean(K_FLIPS, true);
        out.maxFlipsPerJump = p.getInt(K_MAXFLIPS, 0);
    }
}
