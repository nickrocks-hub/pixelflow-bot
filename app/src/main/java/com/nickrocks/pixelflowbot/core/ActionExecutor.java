package com.nickrocks.pixelflowbot.core;

/**
 * Action execution: performs a {@link GameAction} and reports how it went. The Android
 * implementation dispatches accessibility gestures; simulators apply actions directly.
 */
public interface ActionExecutor {
    interface Callback {
        /** ok = the system accepted and completed the gesture; latencyMs = from request to completion. */
        void done(boolean ok, long latencyMs, String note);
    }

    void execute(GameAction action, Callback cb);

    /** Most strokes this executor can perform at once (1 = no multi-touch). */
    int maxSimultaneousStrokes();
}
