package com.nickrocks.pixelflowbot.learn;

import java.util.Random;

/**
 * A simulator for a game: lets the agent train fast and safely (no lost lives, no waiting for
 * animations) using the same adapter, features and rewards as the real game.
 */
public interface Env<S> {
    S reset(Random rnd);

    Outcome<S> step(S state, Object action);

    final class Outcome<S> {
        public S next;
        public float reward;
        public boolean done;
        public boolean win;
        public String note = "";
        public float confidence = 1f;
    }
}
