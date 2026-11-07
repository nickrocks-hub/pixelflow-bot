package com.nickrocks.aibot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * What the bot remembers between runs, and how that changes what it does next time.
 *
 * 1. Strategy choice (Adaptive skill only): seven strategy variants (the playstyle as set, more
 *    dodging, more aggression, both, fighting closer, more abilities, careful). Each run plays one
 *    variant, picked by UCB1: its average score so far plus a bonus for variants tried less often.
 *    After a few runs per variant, the best-scoring one is played most of the time. Scores are
 *    noisy (luck matters), so the choice firms up over tens of runs, not one or two.
 * 2. Enemy memory: per enemy look, how often it was met, killed, and how often the player got hurt
 *    next to it. Dangerous types are shot first and kept further away.
 * 3. Ability memory: per button, how often a press was followed by a kill or by getting hurt.
 *    Buttons that keep failing get used less.
 * 4. Calibration: measured walking speed and shot speed, used to predict where things will be.
 * 5. Mistakes: running counts per kind, shown in AI Statistics.
 */
public final class Learner {

    public static final String[] PARAM_NAMES = {"keep-distance (tiles)", "dodge weight (log)", "aggression", "ability eagerness", "shot lead", "dodge look-ahead (s)"};

    public float[] mean = new float[6];
    public int policyRuns;
    public float[] best = new float[6];
    public float bestFitness = Float.NEGATIVE_INFINITY;

    public static final class EnemyStats {
        public int encounters, kills, hurtNear;
        public long killMsSum;
        public float danger() {
            // share of encounters that hurt us, smoothed towards 0.3 for types seen only a few times
            return (hurtNear + 0.6f) / (encounters + 2f);
        }
    }

    public final Map<String, EnemyStats> enemies = new LinkedHashMap<>();
    public final int[] btnUses = new int[4], btnKills = new int[4], btnHurt = new int[4];
    public int diveButton = -1;
    public boolean buttonsTested;
    public float walkTilesPerS = 5.5f, shotTilesPerS = 12f;
    public int walkSamples, shotSamples;
    public final Map<String, Integer> mistakes = new LinkedHashMap<>();
    public final List<float[]> fitnessHistory = new ArrayList<>();   // {run#, fitness, adaptive?1:0}

    // ---------------------------------------------------------------------------------- policy

    public static final String[] ARM_NAMES = {"playstyle as set", "more dodging", "more aggressive", "dodge + aggressive",
            "fight closer", "more abilities", "careful"};
    /** Offsets per variant: keep-distance, dodge weight (log), aggression, ability eagerness, shot lead, look-ahead. */
    public static final float[][] ARMS = {
            {0, 0, 0, 0, 0, 0},
            {0, 0.5f, 0, 0, 0, 0},
            {0, 0, 0.3f, 0, 0, 0},
            {0, 0.5f, 0.3f, 0, 0, 0},
            {-1.5f, 0, 0, 0, 0, 0},
            {0, 0, 0, 0.3f, 0, 0},
            {1.5f, 0.5f, -0.3f, 0, 0, 0.3f}};
    public final int[] armRuns = new int[ARMS.length];
    public final float[] armSum = new float[ARMS.length];
    public int currentArm = -1;
    public double scoreSum, scoreSq;
    public int scoreN;

    /** Starts a run; returns the variant's offsets (Adaptive), or null. */
    public float[] beginRun(boolean adaptive, Random rnd) {
        if (!adaptive) { currentArm = -1; return null; }
        currentArm = pickArm();
        mean = ARMS[currentArm].clone();
        return ARMS[currentArm].clone();
    }

    public int pickArm() {
        int total = 0;
        for (int i = 0; i < ARMS.length; i++) { if (armRuns[i] == 0) return i; total += armRuns[i]; }
        int best = 0; double bestU = -1e18;
        for (int i = 0; i < ARMS.length; i++) {
            double u = armMean(i) + 0.8 * Math.sqrt(2 * Math.log(total) / armRuns[i]);
            if (u > bestU) { bestU = u; best = i; }
        }
        return best;
    }

    /** Average normalised score of a variant (0 = average run, 1 = one standard deviation better). */
    public double armMean(int i) {
        if (armRuns[i] == 0) return 0;
        double m = scoreN == 0 ? 0 : scoreSum / scoreN;
        double sd = scoreN < 2 ? 1 : Math.sqrt(Math.max(1, scoreSq / scoreN - m * m));
        return (armSum[i] / armRuns[i] - m) / sd;
    }

    /** The variant with the best average after at least 3 tries (what Adaptive has settled on). */
    public int bestArm() {
        int best = 0; double bm = -1e18;
        for (int i = 0; i < ARMS.length; i++) if (armRuns[i] >= 3 && armSum[i] / armRuns[i] > bm) { bm = armSum[i] / armRuns[i]; best = i; }
        return best;
    }

    /** Ends a run with its score; returns a sentence saying what changed. */
    public String endRun(float fitness, boolean adaptive, int runNumber) {
        fitnessHistory.add(new float[]{runNumber, fitness, adaptive ? 1 : 0});
        while (fitnessHistory.size() > 400) fitnessHistory.remove(0);
        if (!adaptive || currentArm < 0) return "Score " + fmt(fitness) + " (strategy unchanged: skill is not Adaptive)";
        int a = currentArm;
        armRuns[a]++; armSum[a] += fitness;
        scoreSum += fitness; scoreSq += (double) fitness * fitness; scoreN++;
        policyRuns++;
        if (fitness > bestFitness) { bestFitness = fitness; best = ARMS[a].clone(); }
        int next = pickArm();
        currentArm = -1;
        return String.format(Locale.US, "Variant \"%s\" scored %s (its average %s over %d runs). Next run will play \"%s\"; best so far: \"%s\".",
                ARM_NAMES[a], fmt(fitness), fmt(armSum[a] / armRuns[a]), armRuns[a], ARM_NAMES[next], ARM_NAMES[bestArm()]);
    }

    // ---------------------------------------------------------------------------------- enemies

    public EnemyStats enemy(String sig) {
        EnemyStats s = enemies.get(sig);
        if (s == null) { s = new EnemyStats(); enemies.put(sig, s); }
        return s;
    }

    public float danger(String sig) {
        EnemyStats s = enemies.get(sig);
        return s == null ? 0.3f : s.danger();
    }

    public void mistake(String kind) { mistakes.put(kind, mistakes.getOrDefault(kind, 0) + 1); }

    /** 0..1: how worthwhile a button has been (kills after a press, minus getting hurt right after). */
    public float buttonValue(int i) {
        if (i < 0 || i > 3) return 0;
        return clamp((btnKills[i] + 1f - 0.5f * btnHurt[i]) / (btnUses[i] + 2f) * 2f, 0.05f, 1f);
    }

    public void calibrateWalk(float tilesPerS) {
        walkSamples++;
        float a = walkSamples < 20 ? 0.2f : 0.03f;
        walkTilesPerS += (tilesPerS - walkTilesPerS) * a;
    }

    public void calibrateShot(float tilesPerS) {
        shotSamples++;
        float a = shotSamples < 20 ? 0.2f : 0.03f;
        shotTilesPerS += (tilesPerS - shotTilesPerS) * a;
    }

    // ---------------------------------------------------------------------------------- storage

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mean", list(mean)); m.put("best", list(best)); m.put("bestFitness", Float.isInfinite(bestFitness) ? null : (double) bestFitness);
        m.put("policyRuns", policyRuns);
        m.put("armRuns", ilist(armRuns)); m.put("armSum", list(armSum));
        m.put("scoreSum", scoreSum); m.put("scoreSq", scoreSq); m.put("scoreN", scoreN);
        Map<String, Object> en = new LinkedHashMap<>();
        for (Map.Entry<String, EnemyStats> e : enemies.entrySet()) {
            EnemyStats s = e.getValue();
            en.put(e.getKey(), Json.map("encounters", s.encounters, "kills", s.kills, "hurtNear", s.hurtNear, "killMsSum", s.killMsSum));
        }
        m.put("enemies", en);
        m.put("btnUses", ilist(btnUses)); m.put("btnKills", ilist(btnKills)); m.put("btnHurt", ilist(btnHurt));
        m.put("diveButton", diveButton); m.put("buttonsTested", buttonsTested);
        m.put("walk", (double) walkTilesPerS); m.put("shot", (double) shotTilesPerS); m.put("walkN", walkSamples); m.put("shotN", shotSamples);
        m.put("mistakes", new LinkedHashMap<String, Object>(mistakes));

        List<Object> fh = new ArrayList<>();
        for (float[] f : fitnessHistory) fh.add(list(f));
        m.put("fitness", fh);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Learner fromMap(Map<String, Object> m) {
        Learner l = new Learner();
        if (m == null) return l;
        try {
            l.mean = arr(m.get("mean"), 6); l.best = arr(m.get("best"), 6);
            Object bf = m.get("bestFitness"); if (bf instanceof Number) l.bestFitness = ((Number) bf).floatValue();
            l.policyRuns = (int) Json.num(m, "policyRuns", 0);
            float[] ar = arr(m.get("armRuns"), ARMS.length), as = arr(m.get("armSum"), ARMS.length);
            for (int i = 0; i < ARMS.length; i++) { l.armRuns[i] = (int) ar[i]; l.armSum[i] = as[i]; }
            l.scoreSum = Json.num(m, "scoreSum", 0); l.scoreSq = Json.num(m, "scoreSq", 0); l.scoreN = (int) Json.num(m, "scoreN", 0);
            Object en = m.get("enemies");
            if (en instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) en).entrySet()) {
                Map<String, Object> v = (Map<String, Object>) e.getValue();
                EnemyStats s = l.enemy(e.getKey());
                s.encounters = (int) Json.num(v, "encounters", 0); s.kills = (int) Json.num(v, "kills", 0);
                s.hurtNear = (int) Json.num(v, "hurtNear", 0); s.killMsSum = (long) Json.num(v, "killMsSum", 0);
            }
            float[] u = arr(m.get("btnUses"), 4), k = arr(m.get("btnKills"), 4), hu = arr(m.get("btnHurt"), 4);
            for (int i = 0; i < 4; i++) { l.btnUses[i] = (int) u[i]; l.btnKills[i] = (int) k[i]; l.btnHurt[i] = (int) hu[i]; }
            l.diveButton = (int) Json.num(m, "diveButton", -1); l.buttonsTested = Boolean.TRUE.equals(m.get("buttonsTested"));
            l.walkTilesPerS = (float) Json.num(m, "walk", 5.5); l.shotTilesPerS = (float) Json.num(m, "shot", 12);
            l.walkSamples = (int) Json.num(m, "walkN", 0); l.shotSamples = (int) Json.num(m, "shotN", 0);
            Object mi = m.get("mistakes");
            if (mi instanceof Map) for (Map.Entry<String, Object> e : ((Map<String, Object>) mi).entrySet())
                if (e.getValue() instanceof Number) l.mistakes.put(e.getKey(), ((Number) e.getValue()).intValue());
            Object fh = m.get("fitness");
            if (fh instanceof List) for (Object o : (List<Object>) fh) l.fitnessHistory.add(arr(o, 3));
        } catch (RuntimeException ignored) { }
        return l;
    }

    public String describePolicy() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ARMS.length; i++) {
            b.append(String.format(Locale.US, "%s: %d runs%s", ARM_NAMES[i], armRuns[i], armRuns[i] > 0 ? ", avg " + fmt(armSum[i] / armRuns[i]) : ""));
            if (i < ARMS.length - 1) b.append(" | ");
        }
        return b.toString();
    }

    private static List<Object> list(float[] a) { List<Object> l = new ArrayList<>(); for (float v : a) l.add((double) v); return l; }
    private static List<Object> ilist(int[] a) { List<Object> l = new ArrayList<>(); for (int v : a) l.add(v); return l; }

    @SuppressWarnings("unchecked")
    private static float[] arr(Object o, int n) {
        float[] a = new float[n];
        if (o instanceof List) { List<Object> l = (List<Object>) o; for (int i = 0; i < n && i < l.size(); i++) if (l.get(i) instanceof Number) a[i] = ((Number) l.get(i)).floatValue(); }
        return a;
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    static String fmt(float f) { return String.format(Locale.US, "%.0f", f); }
}
