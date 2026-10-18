package com.nickrocks.pixelflowbot.core;

/**
 * Writes one episode to an {@link ExperienceStore}: START, a STEP per action (or batch of
 * actions between two observations), END with the reason. Never throws — a storage problem must
 * not stop a game in progress; it is counted in {@link #failures} instead.
 */
public final class EpisodeRecorder {
    private final ExperienceStore store;
    private final GameProfile profile;
    private final String mode;
    private final RewardModel rewards;
    private long episode = -1;
    private int step;
    private StateObservation last;
    public int failures;
    public float totalReward;

    public EpisodeRecorder(ExperienceStore store, GameProfile profile, String mode, RewardModel rewards) {
        this.store = store;
        this.profile = profile;
        this.mode = mode;
        this.rewards = rewards;
    }

    public boolean open() { return episode >= 0; }

    public long episode() { return episode; }

    public StateObservation last() { return last; }

    public void begin(StateObservation first, String note) {
        if (open()) end("restarted", null);
        episode = store.nextEpisode(profile.id);
        step = 0;
        totalReward = 0;
        last = first;
        Experience e = base(Experience.Boundary.START);
        e.nextStateJson = first == null ? null : first.toJson();
        e.confidence = first == null ? 0 : first.confidence;
        e.note = note == null ? "" : note;
        write(e);
    }

    /** Records an action and what was observed after it; returns the reward the model gave. */
    public RewardModel.Reward step(GameAction action, long actionStart, long latencyMs, StateObservation after) {
        if (!open()) begin(null, "implicit start");
        RewardModel.Reward r = rewards == null ? RewardModel.Reward.none("no reward model") : rewards.reward(last, action, after);
        Experience e = base(Experience.Boundary.STEP);
        e.stateJson = last == null ? null : last.toJson();
        e.actionJson = action == null ? null : action.toJson();
        e.actionStartMs = actionStart;
        e.actionLatencyMs = latencyMs;
        e.nextStateJson = after == null ? null : after.toJson();
        e.reward = r.value;
        e.rewardSource = r.source;
        e.confidence = Math.min(last == null ? 1f : last.confidence, after == null ? 0f : after.confidence);
        write(e);
        totalReward += r.value;
        last = after;
        return r;
    }

    public void end(String reason, StateObservation finalState) {
        if (!open()) return;
        Experience e = base(Experience.Boundary.END);
        e.endReason = reason;
        e.stateJson = last == null ? null : last.toJson();
        e.nextStateJson = finalState == null ? null : finalState.toJson();
        e.reward = 0;
        e.rewardSource = "episode total " + totalReward;
        write(e);
        episode = -1;
        last = null;
    }

    private Experience base(Experience.Boundary b) {
        Experience e = new Experience();
        e.profileId = profile.id;
        e.profileVersion = profile.version;
        e.mode = mode;
        e.episode = episode;
        e.step = b == Experience.Boundary.STEP ? ++step : step;
        e.boundary = b;
        e.time = System.currentTimeMillis();
        return e;
    }

    private void write(Experience e) {
        try { store.write(e); } catch (Exception ex) { failures++; }
    }
}
