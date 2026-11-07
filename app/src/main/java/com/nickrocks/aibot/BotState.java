package com.nickrocks.aibot;

import com.nickrocks.aibot.core.RunSessionManager;

/** State shared between the capture service, the control (accessibility) service and the screens. */
public final class BotState {
    private BotState() { }

    /** The live run, or null. */
    public static volatile RunSessionManager session;
    public static volatile boolean captureActive;
    /** App currently in front (from accessibility window events). */
    public static volatile String foregroundPackage = "";
    /** Short status for the panel and the main screen. */
    public static volatile String status = "Idle";
    public static volatile String lastFinishedRunId = "";
    public static volatile boolean paused;

    public static boolean running() {
        RunSessionManager s = session;
        return s != null && s.isLive();
    }
}
