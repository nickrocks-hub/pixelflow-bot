package com.nickrocks.pixelflowbot.core;

import com.nickrocks.pixelflowbot.learn.Json;

import java.util.Locale;
import java.util.Map;

/**
 * One stored step of experience for any game:
 * state → action (with timing) → outcome/next state, reward and where the reward came from,
 * episode boundary, confidence, and the game-profile version that produced it.
 */
public final class Experience {
    /** START opens an episode (no action), STEP is an ordinary step, END closes it. */
    public enum Boundary { START, STEP, END }

    public String profileId;
    public int profileVersion;
    public String mode = "";           // TRAINING / EVALUATION / PLAY / SOLVER / USER
    public long episode;
    public int step;
    public Boundary boundary = Boundary.STEP;
    public String endReason;           // for END: solved / failed / stopped / paused / lost permission …
    public long time;
    public String stateJson;           // StateObservation.toJson() before the action (may be null)
    public String actionJson;          // GameAction.toJson() (null for START/END)
    public long actionStartMs;         // wall clock when the action began
    public long actionLatencyMs;       // request → gesture completed
    public String nextStateJson;       // StateObservation.toJson() after
    public float reward;
    public String rewardSource = "";
    public float confidence = 1f;      // min(confidence of before, after)
    public String note = "";

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"profile\":").append(Json.q(profileId)).append(",\"profileVersion\":").append(profileVersion);
        sb.append(",\"mode\":").append(Json.q(mode)).append(",\"episode\":").append(episode).append(",\"step\":").append(step);
        sb.append(",\"boundary\":").append(Json.q(boundary.name()));
        if (endReason != null) sb.append(",\"endReason\":").append(Json.q(endReason));
        sb.append(",\"t\":").append(time);
        sb.append(",\"state\":").append(stateJson == null ? "null" : stateJson);
        sb.append(",\"action\":").append(actionJson == null ? "null" : actionJson);
        sb.append(",\"actionStart\":").append(actionStartMs).append(",\"latencyMs\":").append(actionLatencyMs);
        sb.append(",\"next\":").append(nextStateJson == null ? "null" : nextStateJson);
        sb.append(",\"reward\":").append(String.format(Locale.US, "%.4f", reward));
        sb.append(",\"rewardSource\":").append(Json.q(rewardSource));
        sb.append(",\"confidence\":").append(String.format(Locale.US, "%.3f", confidence));
        sb.append(",\"note\":").append(Json.q(note));
        return sb.append('}').toString();
    }

    /** Reads back the scalar fields (nested state/action stay as parsed maps, not re-serialized). */
    public static Experience fromJson(String line) {
        Map<String, Object> m = Json.obj(Json.parse(line));
        Experience e = new Experience();
        e.profileId = Json.str(m, "profile", "");
        e.profileVersion = (int) Json.num(m, "profileVersion", 0);
        e.mode = Json.str(m, "mode", "");
        e.episode = (long) Json.num(m, "episode", 0);
        e.step = (int) Json.num(m, "step", 0);
        e.boundary = Boundary.valueOf(Json.str(m, "boundary", "STEP"));
        e.endReason = m.get("endReason") instanceof String ? (String) m.get("endReason") : null;
        e.time = (long) Json.num(m, "t", 0);
        e.actionStartMs = (long) Json.num(m, "actionStart", 0);
        e.actionLatencyMs = (long) Json.num(m, "latencyMs", 0);
        e.reward = (float) Json.num(m, "reward", 0);
        e.rewardSource = Json.str(m, "rewardSource", "");
        e.confidence = (float) Json.num(m, "confidence", 1);
        e.note = Json.str(m, "note", "");
        Object a = m.get("action");
        if (a instanceof Map) e.actionJson = "{\"label\":" + Json.q(Json.str(Json.obj(a), "label", "")) + "}";
        return e;
    }
}
