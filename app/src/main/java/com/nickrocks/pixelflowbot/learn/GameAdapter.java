package com.nickrocks.pixelflowbot.learn;

import java.util.List;

/**
 * Everything game-specific lives behind this interface, so the agent, trainer and storage work
 * for any game. An adapter turns a game state (from a simulator or from the screen) into the
 * list of valid actions, each described by a feature vector the learner scores.
 *
 * To support a new game: implement this (state type S of your choice), optionally an {@link Env}
 * simulator for safe training, and an observer/executor on the Android side that reads S from the
 * screen and performs an action.
 */
public interface GameAdapter<S> {
    /** Stable id used for model files, e.g. "nonogram". */
    String id();

    /** Shown in the UI. */
    String title();

    /** Names of the features, in order. Fixed for a given model version. */
    String[] featureNames();

    /** Valid actions in this state with their features. Empty = nothing sensible to do. */
    List<Candidate> candidates(S state);

    /** One-line readable summary for logs and the panel. */
    String describe(S state);

    /** A valid action with its features. {@code action} is the adapter's own action object. */
    final class Candidate {
        public final String label;
        public final float[] features;
        public final Object action;

        public Candidate(String label, float[] features, Object action) {
            this.label = label;
            this.features = features;
            this.action = action;
        }
    }
}
