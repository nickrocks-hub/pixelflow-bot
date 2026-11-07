package com.nickrocks.aibot.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Counters for one run. Every number here is counted from something the bot saw or did. */
public final class RunStats {
    public int roomsEntered, roomsCleared, floorsCompleted, floor = 1;
    public int enemiesSeen, enemiesDefeated, bossesSeen, bossesDefeated;
    public int hitsLanded;                 // our notes that vanished on an enemy (damage dealt, counted in hits)
    public int damageReceived;             // half hearts lost
    public int healed;                     // half hearts gained
    public int itemsCollected;
    public int decisions, keyDecisions;
    public int dodgesOk, dodgesFailed;
    public int timesStuck;
    public int abilitiesUsed, ultsUsed;
    public int mistakes;
    public int startHp = -1, lowestHp = -1, hpMax = -1;
    public int mapRoomsKnown;

    public String progression() {
        return "Floor " + floor + ", " + roomsEntered + " rooms entered, " + roomsCleared + " cleared";
    }

    /** Score used to compare runs and to drive learning. */
    public float fitness(boolean victory, long gameMs) {
        return 150f * floorsCompleted + 20f * roomsCleared + 6f * roomsEntered + 3f * enemiesDefeated
                + 40f * bossesDefeated + 0.3f * hitsLanded - 4f * damageReceived
                // surviving longer is better when the run is lost; finishing faster is better when it's won
                + (victory ? 600f + Math.max(0f, 1200f - gameMs / 1000f) * 0.2f : gameMs / 10000f);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roomsEntered", roomsEntered); m.put("roomsCleared", roomsCleared); m.put("floorsCompleted", floorsCompleted);
        m.put("floor", floor); m.put("enemiesSeen", enemiesSeen); m.put("enemiesDefeated", enemiesDefeated);
        m.put("bossesSeen", bossesSeen); m.put("bossesDefeated", bossesDefeated); m.put("hitsLanded", hitsLanded);
        m.put("damageReceived", damageReceived); m.put("healed", healed); m.put("itemsCollected", itemsCollected);
        m.put("decisions", decisions); m.put("keyDecisions", keyDecisions); m.put("dodgesOk", dodgesOk);
        m.put("dodgesFailed", dodgesFailed); m.put("timesStuck", timesStuck); m.put("abilitiesUsed", abilitiesUsed);
        m.put("ultsUsed", ultsUsed); m.put("mistakes", mistakes); m.put("startHp", startHp); m.put("lowestHp", lowestHp);
        m.put("hpMax", hpMax); m.put("mapRoomsKnown", mapRoomsKnown); m.put("progression", progression());
        return m;
    }

    public static RunStats fromMap(Map<String, Object> m) {
        RunStats s = new RunStats();
        if (m == null) return s;
        s.roomsEntered = i(m, "roomsEntered"); s.roomsCleared = i(m, "roomsCleared"); s.floorsCompleted = i(m, "floorsCompleted");
        s.floor = Math.max(1, i(m, "floor")); s.enemiesSeen = i(m, "enemiesSeen"); s.enemiesDefeated = i(m, "enemiesDefeated");
        s.bossesSeen = i(m, "bossesSeen"); s.bossesDefeated = i(m, "bossesDefeated"); s.hitsLanded = i(m, "hitsLanded");
        s.damageReceived = i(m, "damageReceived"); s.healed = i(m, "healed"); s.itemsCollected = i(m, "itemsCollected");
        s.decisions = i(m, "decisions"); s.keyDecisions = i(m, "keyDecisions"); s.dodgesOk = i(m, "dodgesOk");
        s.dodgesFailed = i(m, "dodgesFailed"); s.timesStuck = i(m, "timesStuck"); s.abilitiesUsed = i(m, "abilitiesUsed");
        s.ultsUsed = i(m, "ultsUsed"); s.mistakes = i(m, "mistakes"); s.startHp = i(m, "startHp"); s.lowestHp = i(m, "lowestHp");
        s.hpMax = i(m, "hpMax"); s.mapRoomsKnown = i(m, "mapRoomsKnown");
        return s;
    }

    private static int i(Map<String, Object> m, String k) { return (int) Json.num(m, k, 0); }
}
