package com.nickrocks.pixelflowbot.core;

/**
 * Reward model for simulators, where the environment itself reports the reward: set it with
 * {@link #next} right before recording the step.
 */
public final class KnownReward implements RewardModel {
    private float value;
    private String source = "";
    private boolean end, win;

    public KnownReward next(float value, String source, boolean end, boolean win) {
        this.value = value;
        this.source = source;
        this.end = end;
        this.win = win;
        return this;
    }

    @Override
    public Reward reward(StateObservation before, GameAction action, StateObservation after) {
        return new Reward(value, source, end, win);
    }
}
