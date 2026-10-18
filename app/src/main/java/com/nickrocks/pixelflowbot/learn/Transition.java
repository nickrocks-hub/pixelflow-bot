package com.nickrocks.pixelflowbot.learn;

import java.util.Map;

/**
 * One experience: state (as the chosen action's feature vector, plus a readable summary), action,
 * reward, next state, and whether the episode ended — with confidence and debugging notes.
 *
 * For linear Q-learning the state-action pair is fully described by its feature vector; the
 * next state is represented by the feature vectors of its candidate actions' best value, which
 * the learner recomputes from {@code nextBest} (features of the best next action at the time) —
 * kept compact so thousands of transitions fit in memory and on disk.
 */
public final class Transition {
    public String adapter;      // which game adapter produced it
    public String mode;         // TRAINING / EVALUATION / PLAY
    public String source;       // "sim" or "real"
    public long episode;
    public int step;
    public long time;
    public String state;        // readable summary of the state before the action
    public String action;       // readable action label
    public float[] features;    // features of (state, action)
    public float reward;
    public String nextState;    // readable summary of the result
    public float[] nextBest;    // features of the best action in the next state (null if done)
    public boolean done;
    public float confidence;    // how sure the observation was (1 = certain)
    public boolean explored;    // chosen at random (exploration) rather than greedily
    public float q;             // predicted value when chosen
    public String note;         // debugging detail (e.g. "mistake marked red", "cell filled")

    public String toJson() {
        return "{\"adapter\":" + Json.q(adapter) + ",\"mode\":" + Json.q(mode) + ",\"source\":" + Json.q(source)
                + ",\"episode\":" + episode + ",\"step\":" + step + ",\"t\":" + time
                + ",\"state\":" + Json.q(state) + ",\"action\":" + Json.q(action)
                + ",\"f\":" + Json.arr(features) + ",\"reward\":" + reward
                + ",\"next\":" + Json.q(nextState) + ",\"nf\":" + Json.arr(nextBest)
                + ",\"done\":" + done + ",\"conf\":" + confidence + ",\"explored\":" + explored
                + ",\"q\":" + q + ",\"note\":" + Json.q(note) + "}";
    }

    public static Transition fromJson(String line) {
        Map<String, Object> m = Json.obj(Json.parse(line));
        Transition t = new Transition();
        t.adapter = Json.str(m, "adapter", "");
        t.mode = Json.str(m, "mode", "");
        t.source = Json.str(m, "source", "");
        t.episode = (long) Json.num(m, "episode", 0);
        t.step = (int) Json.num(m, "step", 0);
        t.time = (long) Json.num(m, "t", 0);
        t.state = Json.str(m, "state", "");
        t.action = Json.str(m, "action", "");
        t.features = Json.floats(m.get("f"));
        t.reward = (float) Json.num(m, "reward", 0);
        t.nextState = Json.str(m, "next", "");
        t.nextBest = Json.floats(m.get("nf"));
        t.done = Boolean.TRUE.equals(m.get("done"));
        t.confidence = (float) Json.num(m, "conf", 1);
        t.explored = Boolean.TRUE.equals(m.get("explored"));
        t.q = (float) Json.num(m, "q", 0);
        t.note = Json.str(m, "note", "");
        return t;
    }
}
