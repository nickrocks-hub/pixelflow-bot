package com.nickrocks.pixelflowbot;

import android.view.View;

/** The playable view of one lab game: draws a state, turns touches into game actions. */
interface LabScreen<S> {
    interface MoveListener { void onMove(Object action); }

    View view();

    /** Redraw for this state; {@code lastAction} (may be null) is outlined so you can follow the AI. */
    void show(S state, Object lastAction);

    void setMoveListener(MoveListener l);

    /** Real-time games: the action when nobody presses anything (e.g. "stay"). */
    Object idleAction();

    /** Short how-to-play line under the board. */
    String help();
}
