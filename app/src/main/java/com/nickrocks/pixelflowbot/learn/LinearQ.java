package com.nickrocks.pixelflowbot.learn;

import java.util.Map;

/**
 * Linear action-value model: Q(s, a) = w · φ(s, a).
 *
 * Why linear: a game adapter supplies a modest number of meaningful features per action; a linear
 * model learns which ones predict good outcomes from a few thousand experiences, trains in
 * microseconds per step on a phone, is easy to inspect (one weight per named feature) and can't
 * silently overfit the way a large network would on little data.
 *
 * Update (Q-learning with experience replay):
 *   target = r                              if the episode ended
 *   target = r + γ · w · φ(s', a*)          otherwise (a* = best next action when observed)
 *   w ← w + α · (target − w · φ(s, a)) · φ(s, a)
 */
public final class LinearQ {
    public final String[] featureNames;
    public final float[] w;
    public float alpha = 0.03f;
    public float gamma = 0.1f;
    public long updates;
    /**
     * Normalised step (NLMS): divide the step by |φ|² so actions with many active features don't
     * take huge steps. Off by default (the Nonogram.com model was tuned without it).
     */
    public boolean normalize;

    public LinearQ(String[] featureNames) {
        this.featureNames = featureNames;
        this.w = new float[featureNames.length];
    }

    public float q(float[] f) {
        float s = 0;
        for (int i = 0; i < w.length && i < f.length; i++) s += w[i] * f[i];
        return s;
    }

    /** One stochastic-gradient step towards the TD target. Returns the TD error. */
    public float update(Transition t) {
        float target = t.reward;
        if (!t.done && t.nextBest != null) target += gamma * q(t.nextBest);
        float err = target - q(t.features);
        // Clip huge errors so a single odd observation can't wreck the weights.
        if (err > 20) err = 20;
        if (err < -20) err = -20;
        float step = alpha;
        if (normalize) {
            float n2 = 0;
            for (float v : t.features) n2 += v * v;
            step = alpha / Math.max(1f, n2);
        }
        for (int i = 0; i < w.length && i < t.features.length; i++) w[i] += step * err * t.features[i];
        updates++;
        return err;
    }

    public String toJson() {
        return "{\"features\":" + Json.arr(featureNames) + ",\"w\":" + Json.arr(w)
                + ",\"alpha\":" + alpha + ",\"gamma\":" + gamma + ",\"updates\":" + updates + ",\"normalize\":" + normalize + "}";
    }

    public static LinearQ fromJson(Map<String, Object> m) {
        java.util.List<?> names = (java.util.List<?>) m.get("features");
        String[] fn = new String[names.size()];
        for (int i = 0; i < fn.length; i++) fn[i] = (String) names.get(i);
        LinearQ q = new LinearQ(fn);
        float[] w = Json.floats(m.get("w"));
        if (w != null) System.arraycopy(w, 0, q.w, 0, Math.min(w.length, q.w.length));
        q.alpha = (float) Json.num(m, "alpha", q.alpha);
        q.gamma = (float) Json.num(m, "gamma", q.gamma);
        q.updates = (long) Json.num(m, "updates", 0);
        q.normalize = Boolean.TRUE.equals(m.get("normalize"));
        return q;
    }

    /** Weights by feature, strongest first — for the "what has it learned" view. */
    public String explain(int top) {
        Integer[] idx = new Integer[w.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(Math.abs(w[b]), Math.abs(w[a])));
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < Math.min(top, idx.length); k++) {
            int i = idx[k];
            sb.append(String.format(java.util.Locale.US, "%+.2f  %s%n", w[i], featureNames[i]));
        }
        return sb.toString();
    }
}
