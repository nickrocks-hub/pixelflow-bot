package com.nickrocks.aibot.core;

import java.util.Map;

/**
 * Every setting changes real behaviour; see {@link Tuning} for exactly what each one changes.
 */
public final class AISettings {

    public enum Skill { BEGINNER, INTERMEDIATE, ADVANCED, ADAPTIVE }
    public enum Style { AGGRESSIVE, DEFENSIVE, BALANCED, EXPLORER }
    public enum LogLevel { BASIC, DETAILED, DEBUG }

    public Skill skill = Skill.ADAPTIVE;
    public Style style = Style.BALANCED;
    public LogLevel logLevel = LogLevel.DETAILED;
    /** Decisions per second (also the length of each held-stick gesture segment). */
    public int decisionHz = 15;
    public boolean debugOverlay = false;
    public int maxLogMb = 50;
    public boolean autoExport = false;
    public String autoExportFormat = "TXT";
    public boolean useAbilities = true;
    public boolean learning = true;
    /** Debug only: let the bot see enemies the reader had to guess at (lower confidence). */
    public boolean debugSeeAll = false;

    public Map<String, Object> toMap() {
        return Json.map("skill", skill.name(), "style", style.name(), "logLevel", logLevel.name(),
                "decisionHz", decisionHz, "debugOverlay", debugOverlay, "maxLogMb", maxLogMb,
                "autoExport", autoExport, "autoExportFormat", autoExportFormat,
                "useAbilities", useAbilities, "learning", learning, "debugSeeAll", debugSeeAll);
    }

    public static AISettings fromMap(Map<String, Object> m) {
        AISettings s = new AISettings();
        try { s.skill = Skill.valueOf(Json.str(m, "skill", s.skill.name())); } catch (RuntimeException ignored) { }
        try { s.style = Style.valueOf(Json.str(m, "style", s.style.name())); } catch (RuntimeException ignored) { }
        try { s.logLevel = LogLevel.valueOf(Json.str(m, "logLevel", s.logLevel.name())); } catch (RuntimeException ignored) { }
        s.decisionHz = (int) Math.max(5, Math.min(30, Json.num(m, "decisionHz", s.decisionHz)));
        s.debugOverlay = Boolean.TRUE.equals(m.get("debugOverlay"));
        s.maxLogMb = (int) Math.max(5, Math.min(500, Json.num(m, "maxLogMb", s.maxLogMb)));
        s.autoExport = Boolean.TRUE.equals(m.get("autoExport"));
        s.autoExportFormat = Json.str(m, "autoExportFormat", "TXT");
        s.useAbilities = !Boolean.FALSE.equals(m.get("useAbilities"));
        s.learning = !Boolean.FALSE.equals(m.get("learning"));
        s.debugSeeAll = Boolean.TRUE.equals(m.get("debugSeeAll"));
        return s;
    }

    /** The concrete numbers the agent uses, from skill + playstyle (+ learned values in Adaptive). */
    public static final class Tuning {
        public int reactionMs;          // the agent decides on a picture this old
        public float aimNoiseDeg;       // random aim error
        public float dodgeHorizonS;     // how far ahead projectiles are predicted
        public boolean leadShots;       // aim where a moving enemy will be
        public boolean abilities;       // uses DIVE/SMASH/DROP/SCREAM/ULT
        public boolean useEnemyMemory;  // uses learned enemy danger
        // playstyle / learned policy
        public float keepDistTiles;     // preferred distance from enemies
        public float dodgeWeight;
        public float aggression;        // 0..1, pull towards the target
        public float lootWeight;
        public float exploreWeight;
        public float abilityEagerness;  // 0..1
        public float leadFactor;
        public float wallMargin;

        public String describe() {
            return String.format(java.util.Locale.US,
                    "reaction %d ms, aim noise %.0f°, dodge look-ahead %.2f s, lead %s, abilities %s, keep %.1f tiles, dodge weight %.2f, aggression %.2f, ability eagerness %.2f",
                    reactionMs, aimNoiseDeg, dodgeHorizonS, leadShots ? "on" : "off", abilities ? "on" : "off",
                    keepDistTiles, dodgeWeight, aggression, abilityEagerness);
        }
    }

    public Tuning tuning(float[] learned) {
        Tuning t = new Tuning();
        switch (skill) {
            case BEGINNER: t.reactionMs = 260; t.aimNoiseDeg = 16; t.dodgeHorizonS = 0.30f; t.leadShots = false; t.abilities = false; t.useEnemyMemory = false; break;
            case INTERMEDIATE: t.reactionMs = 120; t.aimNoiseDeg = 7; t.dodgeHorizonS = 0.55f; t.leadShots = true; t.abilities = true; t.useEnemyMemory = true; break;
            default: t.reactionMs = 0; t.aimNoiseDeg = 2; t.dodgeHorizonS = 0.85f; t.leadShots = true; t.abilities = true; t.useEnemyMemory = true; break;
        }
        if (!useAbilities) t.abilities = false;
        switch (style) {
            case AGGRESSIVE: t.keepDistTiles = 2.2f; t.dodgeWeight = 1.0f; t.aggression = 0.9f; t.lootWeight = 0.4f; t.exploreWeight = 0.6f; t.abilityEagerness = 0.8f; break;
            case DEFENSIVE: t.keepDistTiles = 5.5f; t.dodgeWeight = 2.2f; t.aggression = 0.2f; t.lootWeight = 0.6f; t.exploreWeight = 0.5f; t.abilityEagerness = 0.5f; break;
            case EXPLORER: t.keepDistTiles = 4.0f; t.dodgeWeight = 1.5f; t.aggression = 0.4f; t.lootWeight = 1.2f; t.exploreWeight = 1.3f; t.abilityEagerness = 0.5f; break;
            default: t.keepDistTiles = 3.8f; t.dodgeWeight = 1.5f; t.aggression = 0.5f; t.lootWeight = 0.8f; t.exploreWeight = 0.8f; t.abilityEagerness = 0.6f; break;
        }
        t.leadFactor = 1f;
        t.wallMargin = 1f;
        if (skill == Skill.ADAPTIVE && learned != null) {
            // learned policy parameters are offsets applied on top of the playstyle
            t.keepDistTiles = clamp(t.keepDistTiles + learned[0], 1.5f, 8f);
            t.dodgeWeight = clamp(t.dodgeWeight * (float) Math.exp(learned[1]), 0.3f, 5f);
            t.aggression = clamp(t.aggression + learned[2], 0f, 1f);
            t.abilityEagerness = clamp(t.abilityEagerness + learned[3], 0f, 1f);
            t.leadFactor = clamp(1f + learned[4], 0.3f, 1.6f);
            t.dodgeHorizonS = clamp(t.dodgeHorizonS + learned[5], 0.3f, 1.4f);
        }
        return t;
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
