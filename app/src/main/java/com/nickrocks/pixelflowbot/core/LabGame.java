package com.nickrocks.pixelflowbot.core;

import com.nickrocks.pixelflowbot.learn.Env;
import com.nickrocks.pixelflowbot.learn.GameAdapter;

import java.util.List;
import java.util.Random;

/**
 * A game the platform can simulate, train on and evaluate without touching a real app.
 * Everything game-specific is here; {@link Lab} does training and evaluation for all of them.
 *
 * Environments must be deterministic given the {@code Random} passed to reset, so a seed
 * identifies an episode exactly (training and evaluation use disjoint seed streams).
 */
public interface LabGame<S> {
    String id();

    String title();

    GameProfile.Pace pace();

    /** Milliseconds per tick for real-time games (0 = turn-based: waits for each move). */
    long tickMs();

    /** Candidate actions + features for the learning agent. */
    GameAdapter<S> adapter();

    /** A fresh environment (the training/evaluation simulator). */
    Env<S> newEnv();

    /** Structured view of a state for the UI and the experience store. */
    Perception<S> perception();

    /** Simple non-learning players to compare against. */
    List<Baseline<S>> baselines();

    /** The game's own score for a (final) state — what evaluation reports. */
    double score(S state);

    String scoreName();

    /** Learning settings for this game's agent (learning rate, discount, exploration). */
    default void configure(com.nickrocks.pixelflowbot.learn.Agent agent) { }

    /** Step limit per episode for training and evaluation. */
    default int maxSteps() { return 10_000; }

    /** A hand-written, non-learning player. */
    interface Baseline<S> {
        String name();

        /** Index into the candidate list. */
        int choose(S state, List<GameAdapter.Candidate> cands, Random rnd);
    }
}
