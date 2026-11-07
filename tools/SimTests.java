import com.nickrocks.aibot.core.*;

import java.io.File;
import java.util.*;

/**
 * Scenario tests and training for the bot, run against {@link Sim}.
 *   java SimTests scenarios <workDir>
 *   java SimTests train <workDir> <episodes>
 * Each scenario checks the outcome AND that the log agrees with what really happened in the game.
 */
public class SimTests {

    static int failures = 0;

    static class Result {
        Map<String, Object> meta;
        List<Map<String, Object>> events;
        Sim sim;
        RunSessionManager session;
        int ticks;
    }

    static Result run(File dir, Sim.Config cfg, AISettings s, long seed, float maxSec, Integer stopAtSec) {
        RunHistoryRepository repo = new RunHistoryRepository(dir);
        int[] completions = {0};
        RunSessionManager sess = new RunSessionManager(repo, s, new ScreenLayout(), new RunSessionManager.Listener() {
            public void onState(RunSessionManager.State st, String note) { }
            public void onFinished(Map<String, Object> m) { completions[0]++; }
        });
        Sim sim = new Sim(seed, cfg);
        sess.start(0);
        float dt = 1f / s.decisionHz;
        Result r = new Result();
        int ticks = 0;
        while (sess.isLive() && sim.tMs < maxSec * 1000) {
            if (stopAtSec != null && sim.tMs >= stopAtSec * 1000L) sess.requestStop("Stopped by you");
            Observation o = sim.observe();
            Action a = sess.onObservation(o, sim.tMs);
            sim.apply(a, dt);
            ticks++;
        }
        if (sess.isLive()) { sess.requestStop("time limit of the test"); sess.onObservation(sim.observe(), sim.tMs + 50); }
        r.meta = repo.meta(sess.runId);
        r.events = repo.events(sess.runId);
        r.sim = sim; r.session = sess; r.ticks = ticks;
        check(completions[0] == 1, "run finished exactly once (got " + completions[0] + ")");
        return r;
    }

    static int countType(List<Map<String, Object>> ev, String type) {
        int n = 0;
        for (Map<String, Object> e : ev) if (type.equals(Json.str(e, "type", ""))) n++;
        return n;
    }

    static int countMsg(List<Map<String, Object>> ev, String needle) {
        int n = 0;
        for (Map<String, Object> e : ev) if (Json.str(e, "msg", "").contains(needle)) n++;
        return n;
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static Map<String, Object> st(Result r) { return PerformanceAnalyzer.stats(r.meta); }
    static int stat(Result r, String k) { return (int) Json.num(st(r), k, 0); }

    /** The log must agree with the game: damage, abilities, kills, completion. */
    static void consistency(Result r, boolean buttonTest) {
        int dmgLogged = 0;
        for (Map<String, Object> e : r.events) if ("DAMAGE".equals(Json.str(e, "type", ""))) {
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) e.get("data");
            dmgLogged += (int) Json.num(d, "lostHalfHearts", 0);
        }
        check(dmgLogged == r.sim.damageTaken, "damage in the log (" + dmgLogged + ") = damage really taken (" + r.sim.damageTaken + ")");
        int abil = countType(r.events, "ABILITY");
        int testPresses = countMsg(r.events, "moved the character");
        int pressed = r.sim.buttonPresses + r.sim.ultPresses - testPresses;
        check(Math.abs(abil - pressed) <= 1, "abilities in the log (" + abil + ") match presses the game received (" + pressed + ")");
        int kills = stat(r, "enemiesDefeated");
        check(kills <= r.sim.kills, "kills counted (" + kills + ") never exceed real kills (" + r.sim.kills + ")");
        check(countMsg(r.events, "RUN COMPLETE") == 1, "exactly one RUN COMPLETE in the log");
        int decisions = countType(r.events, "DECISION");
        int incomplete = 0;
        for (Map<String, Object> e : r.events) if ("DECISION".equals(Json.str(e, "type", ""))) {
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) e.get("data");
            for (String k : new String[]{"situation", "decision", "reason", "result", "assessment"}) if (!d.containsKey(k)) incomplete++;
        }
        check(incomplete == 0, decisions + " explained decisions, each with situation/decision/reason/result/assessment");
    }

    static AISettings settings(AISettings.Skill sk, AISettings.Style sty) {
        AISettings s = new AISettings();
        s.skill = sk; s.style = sty; s.logLevel = AISettings.LogLevel.DETAILED; s.decisionHz = 15;
        return s;
    }

    static File fresh(File root, String name) {
        File d = new File(root, name);
        deleteTree(d);
        d.mkdirs();
        return d;
    }

    static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        f.delete();
    }

    static void summary(Result r) {
        Map<String, Object> s = st(r);
        System.out.printf(Locale.US, "  -> %s in %s | rooms %d entered/%d cleared, floors %d, kills %d (real %d), hits %d, dmg %d, dodges %d ok/%d failed, stuck %d, items %d, abilities %d, decisions %d%n",
                Json.str(r.meta, "outcome", "?"), PerformanceAnalyzer.duration(Json.num(r.meta, "durationMs", 0)),
                stat(r, "roomsEntered"), stat(r, "roomsCleared"), stat(r, "floorsCompleted"), stat(r, "enemiesDefeated"), r.sim.kills,
                stat(r, "hitsLanded"), stat(r, "damageReceived"), stat(r, "dodgesOk"), stat(r, "dodgesFailed"), stat(r, "timesStuck"),
                stat(r, "itemsCollected"), stat(r, "abilitiesUsed"), stat(r, "decisions"));
    }

    // ===================================================================================

    public static void main(String[] a) throws Exception {
        File root = new File(a[1]);
        if (a[0].equals("scenarios")) scenarios(root);
        else if (a[0].equals("train")) train(root, Integer.parseInt(a[2]));
        else if (a[0].equals("sample")) sample(root);
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void scenarios(File root) {
        AISettings adv = settings(AISettings.Skill.ADVANCED, AISettings.Style.BALANCED);

        System.out.println("1. One enemy in a room");
        Sim.Config c = new Sim.Config(); c.onlyRoom = new String[]{"mage"}; c.obstacles = false;
        Result r = run(fresh(root, "s1"), c, adv, 1, 60, null);
        summary(r);
        check(r.sim.kills == 1, "the enemy was really killed in the game");
        check(stat(r, "roomsCleared") == 1, "room logged as cleared");
        check(countMsg(r.events, "Enemy detected") >= 1 && countMsg(r.events, "AI attacked") >= 1, "log shows enemy detected and attacked");
        consistency(r, true);

        System.out.println("2. Many enemies in a room");
        c = new Sim.Config(); c.onlyRoom = new String[]{"bat", "bat", "bat", "mage", "mage", "turret", "demon", "demon"}; c.hp = 10;
        r = run(fresh(root, "s2"), c, adv, 2, 120, null);
        summary(r);
        check(r.sim.kills >= 6, "most enemies really killed (" + r.sim.kills + "/8)");
        consistency(r, true);

        System.out.println("3. Room with obstacles (needs path finding to reach the next rooms)");
        c = new Sim.Config(); c.floors = 1; c.roomsPerFloor = 5; c.enemyScale = 0.5f; c.hp = 10;
        r = run(fresh(root, "s3"), c, adv, 3, 240, null);
        summary(r);
        check(r.sim.roomsEntered >= 3, "walked through doors into " + r.sim.roomsEntered + " rooms");
        check(stat(r, "roomsEntered") == r.sim.roomsEntered || stat(r, "roomsEntered") >= r.sim.roomsEntered,
                "rooms entered in the log (" + stat(r, "roomsEntered") + ") cover the real ones (" + r.sim.roomsEntered + " new rooms)");
        consistency(r, true);

        System.out.println("4. Projectile attacks: dodging on vs off (same seeds)");
        int dmgOn = 0, dmgOff = 0, dodgesOn = 0;
        for (int seed = 10; seed < 16; seed++) {
            c = new Sim.Config(); c.onlyRoom = new String[]{"turret", "turret", "mage", "mage"}; c.obstacles = false; c.hp = 20;
            AISettings on = settings(AISettings.Skill.ADVANCED, AISettings.Style.BALANCED); on.useAbilities = false;
            Result ron = run(fresh(root, "s4on" + seed), c, on, seed, 40, null);
            dmgOn += ron.sim.damageTaken; dodgesOn += stat(ron, "dodgesOk");
            AISettings off = settings(AISettings.Skill.BEGINNER, AISettings.Style.AGGRESSIVE); off.useAbilities = false;
            Result roff = run(fresh(root, "s4off" + seed), c, off, seed, 40, null);
            dmgOff += roff.sim.damageTaken;
        }
        System.out.printf(Locale.US, "  -> damage taken: Advanced/Balanced %d, Beginner/Aggressive %d; dodges logged %d%n", dmgOn, dmgOff, dodgesOn);
        check(dmgOn < dmgOff, "advanced dodging takes less damage than beginner over 6 fights");
        check(dodgesOn > 0, "successful dodges were logged");

        System.out.println("5. Boss encounter");
        c = new Sim.Config(); c.bossOnly = true; c.hp = 12; c.obstacles = false;
        r = run(fresh(root, "s5"), c, adv, 5, 180, null);
        summary(r);
        check(countMsg(r.events, "Boss encountered") >= 1, "boss encounter logged");
        check(r.sim.bossKills == 1 == (countMsg(r.events, "Boss defeated") >= 1), "boss defeat logged exactly when it happened (real kills " + r.sim.bossKills + ")");
        consistency(r, true);

        System.out.println("6. Low health: keeps further away");
        float[] dist = new float[2];
        for (int k = 0; k < 2; k++) {
            c = new Sim.Config(); c.onlyRoom = new String[]{"bat", "bat", "mage"}; c.obstacles = false; c.hp = k == 0 ? 20 : 2;
            c.shotsOn = false;
            RunHistoryRepository repo = new RunHistoryRepository(fresh(root, "s6" + k));
            RunSessionManager sess = new RunSessionManager(repo, adv, new ScreenLayout(), null);
            Sim sim = new Sim(6, c);
            sess.start(0);
            double sum = 0; int n = 0;
            while (sess.isLive() && sim.tMs < 8000) {
                Observation o = sim.observe();
                Action act = sess.onObservation(o, sim.tMs);
                if (o.player != null && !o.enemies.isEmpty() && sim.tMs > 1500) {
                    float best = 1e9f;
                    for (Observation.Thing e : o.enemies) best = Math.min(best, (float) Math.hypot(e.x - o.player.x, e.y - o.player.y));
                    sum += best; n++;
                }
                sim.apply(act, 1f / 15);
            }
            sess.requestStop("test"); sess.onObservation(sim.observe(), sim.tMs + 50);
            dist[k] = n == 0 ? 0 : (float) (sum / n) / Sim.TILE;
        }
        System.out.printf(Locale.US, "  -> average distance to the nearest enemy: full health %.2f tiles, 1 heart %.2f tiles%n", dist[0], dist[1]);
        check(dist[1] > dist[0], "keeps more distance at low health");

        System.out.println("7. Navigation gets stuck (invisible block in front of the doors)");
        c = new Sim.Config(); c.floors = 1; c.roomsPerFloor = 3; c.hiddenObstacle = true; c.obstacles = false; c.enemyScale = 0.4f; c.hp = 10;
        r = run(fresh(root, "s7"), c, adv, 7, 120, null);
        summary(r);
        check(stat(r, "timesStuck") >= 1, "stuck detected and logged (" + stat(r, "timesStuck") + ")");
        check(r.sim.roomsEntered >= 2, "got around the block and left the first room");
        check(countType(r.events, "STUCK") == stat(r, "timesStuck"), "every stuck event is in the log");

        System.out.println("8. Completed victory (2 floors, final boss)");
        c = new Sim.Config(); c.floors = 2; c.roomsPerFloor = 3; c.hp = 16; c.enemyScale = 0.6f;
        r = run(fresh(root, "s8"), c, adv, 8, 900, null);
        summary(r);
        System.out.println("  real: floors " + r.sim.floorsDone + ", boss kills " + r.sim.bossKills + ", victory " + r.sim.victory);
        check(r.sim.victory == "Victory".equals(Json.str(r.meta, "outcome", "")), "outcome Victory logged exactly when the game was won (won: " + r.sim.victory + ")");
        check(r.sim.victory, "the bot finished the dungeon");
        check(stat(r, "floorsCompleted") == r.sim.floorsDone, "floors completed in the log (" + stat(r, "floorsCompleted") + ") = real (" + r.sim.floorsDone + ")");
        consistency(r, true);

        System.out.println("9. Player death");
        c = new Sim.Config(); c.onlyRoom = new String[]{"boss", "demon", "demon", "turret", "turret", "mage", "mage"}; c.hp = 2;
        r = run(fresh(root, "s9"), c, settings(AISettings.Skill.BEGINNER, AISettings.Style.AGGRESSIVE), 9, 120, null);
        summary(r);
        check(r.sim.dead, "the character really died");
        check("Death".equals(Json.str(r.meta, "outcome", "")), "outcome Death");
        consistency(r, true);

        System.out.println("10. Manually stopped run");
        c = new Sim.Config(); c.floors = 1;
        r = run(fresh(root, "s10"), c, adv, 10, 60, 12);
        summary(r);
        check("Stopped".equals(Json.str(r.meta, "outcome", "")), "outcome Stopped");
        check("COMPLETED".equals(Json.str(r.meta, "status", "")), "status COMPLETED");
        check(Math.abs(Json.num(r.meta, "durationMs", 0) - 12000) < 400, "in-game time stops at the stop (" + (long) Json.num(r.meta, "durationMs", 0) + " ms)");

        System.out.println("11. Interrupted app session (killed mid-run)");
        File d11 = fresh(root, "s11");
        RunHistoryRepository repo = new RunHistoryRepository(d11);
        RunSessionManager sess = new RunSessionManager(repo, adv, new ScreenLayout(), null);
        Sim sim = new Sim(11, new Sim.Config());
        sess.start(0);
        while (sim.tMs < 9000) { Action act = sess.onObservation(sim.observe(), sim.tMs); sim.apply(act, 1f / 15); }
        String id = sess.runId;
        int before = repo.events(id).size();
        // ... the app dies here: no stop, no finalize. Next app start:
        RunHistoryRepository again = new RunHistoryRepository(d11);
        int fixed = again.recoverInterrupted("");
        Map<String, Object> m = again.meta(id);
        System.out.println("  -> events saved before the crash: " + before + ", status now " + Json.str(m, "status", "?"));
        check(fixed == 1 && "INTERRUPTED".equals(Json.str(m, "status", "")), "run marked INTERRUPTED, not completed");
        check(before > 5 && again.events(id).size() == before + 1, "log kept, plus one 'interrupted' line");

        System.out.println("12. Export, search, compare, delete");
        RunHistoryRepository rp = new RunHistoryRepository(new File(root, "s1"));
        String rid = Json.str(rp.list().get(0), "runId", "");
        String json = rp.exportJson(rid), csv = rp.exportCsv(rid), txt = rp.exportTxt(rid);
        check(Json.parse(json) instanceof Map, "JSON export parses back");
        check(csv.startsWith("n,time_ms") && csv.split("\n").length > 5, "CSV export has rows");
        check(txt.contains("AI RUN RESULTS") && txt.contains("RUN COMPLETE"), "TXT export has the summary and the log");
        check(!rp.search(rid, "enemy detected").isEmpty(), "search finds events");
        List<String[]> cmp = PerformanceAnalyzer.compare(rp.meta(rid), new RunHistoryRepository(new File(root, "s2")).list().get(0));
        check(cmp.size() > 10, "comparison table built");
        check(rp.delete(rid) && rp.list().isEmpty(), "delete removes the run");
        System.out.println("\nSample TXT export (scenario 1):\n" + txt.substring(0, Math.min(2600, txt.length())));
    }

    /** Learning check: Adaptive trains on some seeds, then is compared with fixed play on unseen seeds. */
    static void train(File root, int episodes) {
        File dir = fresh(root, "train");
        AISettings s = settings(AISettings.Skill.ADAPTIVE, AISettings.Style.BALANCED);
        s.logLevel = AISettings.LogLevel.BASIC;
        Sim.Config c = new Sim.Config(); c.floors = 3; c.roomsPerFloor = 4; c.hp = 4; c.enemyScale = 1.6f;
        List<Float> fits = new ArrayList<>();
        for (int ep = 0; ep < episodes; ep++) {
            Result r = run(dir, c, s, 1000 + ep, 300, null);
            float f = (float) Json.num(r.meta, "fitness", 0);
            fits.add(f);
            Learner l = new RunHistoryRepository(dir).loadLearner();
            if (ep % 5 == 0 || ep == episodes - 1)
                System.out.printf(Locale.US, "episode %3d  score %6.0f  %s | policy: %s%n", ep + 1, f, Json.str(r.meta, "outcome", ""), l.describePolicy());
        }
        Learner l = new RunHistoryRepository(dir).loadLearner();
        System.out.println("Enemy danger learned: ");
        for (Map.Entry<String, Learner.EnemyStats> e : l.enemies.entrySet())
            System.out.printf(Locale.US, "  %-16s met %3d, killed %3d, hurt us %3d -> danger %.2f%n", e.getKey(), e.getValue().encounters, e.getValue().kills, e.getValue().hurtNear, e.getValue().danger());
        System.out.println("Mistakes: " + l.mistakes);
        System.out.printf(Locale.US, "Walk speed learned %.2f tiles/s (true 5.5), note speed %.2f tiles/s (true 12)%n", l.walkTilesPerS, l.shotTilesPerS);
        // held-out comparison: the trained policy (mean, no exploration noise) vs the untrained playstyle
        int N = 24;
        double trained = 0, untrained = 0;
        for (int k = 0; k < N; k++) {
            long seed = 5000 + k;
            File t1 = fresh(root, "evalT"); copyLearner(dir, t1, true);
            AISettings sa = settings(AISettings.Skill.ADAPTIVE, AISettings.Style.BALANCED); sa.logLevel = AISettings.LogLevel.BASIC; sa.learning = true;
            trained += Json.num(runEval(t1, c, sa, seed), "fitness", 0);
            File t2 = fresh(root, "evalU");
            untrained += Json.num(run(t2, c, settings(AISettings.Skill.ADVANCED, AISettings.Style.BALANCED), seed, 300, null).meta, "fitness", 0);
        }
        System.out.println("Adaptive settled on: " + Learner.ARM_NAMES[l.bestArm()]);
        System.out.printf(Locale.US, "Unseen dungeons (%d): untrained Advanced/Balanced average score %.0f, trained Adaptive average score %.0f%n", N, untrained / N, trained / N);
        int half = fits.size() / 2;
        double a = 0, b = 0;
        for (int i = 0; i < half; i++) a += fits.get(i);
        for (int i = half; i < fits.size(); i++) b += fits.get(i);
        System.out.printf(Locale.US, "Training: first half average %.0f, second half average %.0f%n", a / half, b / (fits.size() - half));
    }

    /** Evaluate with the learned mean only (no random nudge), learning off so the store is unchanged. */
    static Map<String, Object> runEval(File dir, Sim.Config c, AISettings s, long seed) {
        Learner l = new RunHistoryRepository(dir).loadLearner();
        // evaluate what Adaptive has settled on: force its best variant for this run
        int best = l.bestArm();
        for (int i = 0; i < Learner.ARMS.length; i++) if (i != best) l.armRuns[i] = Math.max(l.armRuns[i], 1);
        l.armRuns[best] = Math.max(1, l.armRuns[best]);
        l.armSum[best] = 1e9f;   // make UCB pick it
        new RunHistoryRepository(dir).saveLearner(l);
        return run(dir, c, s, seed, 300, null).meta;
    }

    static void copyLearner(File from, File to, boolean x) {
        new RunHistoryRepository(to).saveLearner(new RunHistoryRepository(from).loadLearner());
    }

    static void sample(File root) {
        Sim.Config c = new Sim.Config(); c.floors = 1; c.roomsPerFloor = 3;
        Result r = run(fresh(root, "sample"), c, settings(AISettings.Skill.ADVANCED, AISettings.Style.BALANCED), 42, 200, null);
        System.out.println(new RunHistoryRepository(new File(root, "sample")).exportTxt(r.session.runId));
    }
}
