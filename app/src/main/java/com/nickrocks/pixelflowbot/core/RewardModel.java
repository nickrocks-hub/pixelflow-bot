package com.nickrocks.pixelflowbot.core;

/**
 * Reward and progress detection between two observations. Every reward names its source, so
 * the user can see (and later correct) what the agent is being paid for. A reward model must not
 * assume that any number going up is good — it states exactly which signal it uses.
 */
public interface RewardModel {
    final class Reward {
        public final float value;
        public final String source;      // e.g. "board read-back: +12 filled cells"
        public final boolean episodeEnd; // the observation shows a finished episode (win/loss)
        public final boolean win;

        public Reward(float value, String source, boolean episodeEnd, boolean win) {
            this.value = value;
            this.source = source;
            this.episodeEnd = episodeEnd;
            this.win = win;
        }

        public static Reward none(String why) { return new Reward(0f, why, false, false); }
    }

    Reward reward(StateObservation before, GameAction action, StateObservation after);
}
