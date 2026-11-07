package com.nickrocks.aibot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The bot's brain. Every tick it takes the latest {@link Observation}, decides on an objective
 * (fight, pick up, explore, test buttons), chooses stick directions and button presses, and reports
 * what happened (kills, hits, damage, rooms, items) and why it chose what it chose.
 */
public final class AutonomousAgent {

    public enum Objective { WAIT_PLAYER, BUTTON_TEST, FIGHT, HOLD, LOOT, EXPLORE }

    private final AISettings settings;
    private AISettings.Tuning tu;
    private final ScreenLayout L;
    private final RunContext ctx;
    private final Learner mem;
    private final Random rnd;
    public final NavigationPlanner nav = new NavigationPlanner();
    public final CombatDecisionSystem combat;

    // ---- shown on the debug overlay
    public Objective objective = Objective.WAIT_PLAYER;
    public String targetText = "-", actionText = "", lastDecision = "", objectiveText = "";
    public float threatLevel;
    public long procMicros;
    public int nearbyEnemies;
    public Observation lastObs;
    public float aimDirX, aimDirY;

    // ---- delay line (reaction time)
    private final ArrayDeque<Observation> delay = new ArrayDeque<>();

    // ---- health
    public int hp = -1, hpMax = -1;
    private int hpCand = -1, hpCandN;
    public long lastHurtT = -100000;
    public int hpAfterLastHurt = -1;

    // ---- rooms
    private String roomSig, sigCand;
    private int sigCandN;
    public int roomIndex;
    private long roomEnterT;
    private boolean roomHadEnemies, roomClearedFlag;
    private long lastEnemyT = -100000;
    private int maxKnown;
    private float camAccX, camAccY;
    private int[] pushDir;
    private long pushSince = -1;
    private final Map<String, Long> doorBlacklist = new HashMap<>();
    private int[] chosenExit;

    // ---- bookkeeping of things on screen
    private static final class Seen {
        int id; String sig = ""; long first, last; float x, y; boolean boss, confirmed; int frames, hits;
    }
    private final Map<Integer, Seen> seen = new HashMap<>();
    private final Map<Integer, float[]> ownShots = new HashMap<>();      // id -> {x, y, lastSeenT}
    private final Map<Integer, float[]> pickups = new HashMap<>();
    private static final class Threat { int id; long first; float dist, time; boolean resolved; }
    private final Map<Integer, Threat> threats = new HashMap<>();
    public long bossDefeatedT = -1;
    private long lastAbilityT = -100000;
    private long lastSceneCutT = -100000;

    // ---- abilities: readyAt per button 0..3 and ULT (4)
    private final long[] readyAt = new long[5];
    private static final class AbilityUse { int btn; long t; int kills, dmg; }
    private final List<AbilityUse> abilityUses = new ArrayList<>();

    // ---- button test (which button is DIVE?)
    private int btnStep = -1;
    private long btnT;
    private float btnX, btnY;
    private final float[] btnDisp = new float[4];
    public boolean wantButtonTest;

    // ---- explained decisions waiting for their result
    private static final class Pending {
        String kind, situation, decision, reason; long t, evalAt; int hp, kills, rooms, dmg, targetId = -1, stuck;
    }
    private final List<Pending> pending = new ArrayList<>();
    private long lastDodgeLog = -100000, lastObjectiveLog = -100000;
    private Objective lastLoggedObjective;
    private int lastTargetId = -1;

    // ---- movement memory
    private float prevMx, prevMy, lastMag;
    private long fullStickSince = -1, lastCalib;
    private long noPlayerSince = -1;
    private long lastNow;
    private final Map<Integer, Float> dangerById = new HashMap<>();
    private int strafeSign = 1;

    public AutonomousAgent(AISettings s, ScreenLayout layout, RunContext ctx, float[] learned, long seed) {
        this.settings = s;
        this.L = layout;
        this.ctx = ctx;
        this.mem = ctx.learner();
        this.rnd = new Random(seed);
        this.combat = new CombatDecisionSystem(rnd);
        this.tu = s.tuning(learned);
        if (mem.diveButton >= 0) L.btnRole[mem.diveButton] = "DIVE";
        wantButtonTest = !mem.buttonsTested && s.useAbilities;
    }

    public AISettings.Tuning tuning() { return tu; }

    private long gt() { return ctx.gameTimeMs(); }

    private void log(int lvl, String type, String msg, Map<String, Object> data) { ctx.log(lvl, type, msg, data); }

    // =============================================================================== step

    public Action step(Observation raw) {
        long t0 = System.nanoTime();
        long now = raw.timeMs;
        lastNow = now;
        // reaction time: decide on a picture this old
        delay.addLast(raw);
        Observation o = raw;
        while (delay.size() > 1 && delay.peekFirst().timeMs <= now - tu.reactionMs) o = delay.pollFirst();
        if (tu.reactionMs > 0 && delay.size() > 0 && delay.peekFirst().timeMs > now - tu.reactionMs && o == raw) o = delay.peekFirst();
        while (delay.size() > 40) delay.pollFirst();
        lastObs = o;

        if (raw.sceneCut) { camAccX = camAccY = 0; lastSceneCutT = now; }
        else { camAccX += raw.camDx; camAccY += raw.camDy; }

        updateHealth(raw, now);
        updateRoom(raw, now);
        updateEnemies(o, now);
        updateShots(o, now);
        updatePickups(o, now);
        updateThreats(o, now);
        nav.cover(o, camAccX, camAccY);
        evaluatePending(now, false);
        evaluateAbilities(now);

        Action a = decide(o, now);
        ctx.stats().decisions++;
        prevMx = a.moveX; prevMy = a.moveY;
        lastMag = (float) Math.hypot(a.moveX, a.moveY);
        actionText = a.describe();
        procMicros = (System.nanoTime() - t0) / 1000;
        if (ctx.stats().decisions % 15 == 0)
            log(RunLogger.DEBUG, "AI", "tick: " + objective + " | " + actionText + " | threat " + fmt(threatLevel) + " | " + procMicros + " µs", null);
        return a;
    }

    // =============================================================================== decide

    private Action decide(Observation o, long now) {
        Action a = new Action();
        Observation.Thing p = o.player;
        float tile = 19 * o.unit;
        if (p == null) {
            objective = Objective.WAIT_PLAYER;
            objectiveText = "looking for the character";
            if (noPlayerSince < 0) noPlayerSince = now;
            // keep the last movement briefly (the sprite may just be hidden by an effect)
            if (now - noPlayerSince < 350) { a.moveX = prevMx; a.moveY = prevMy; }
            threatLevel = 0;
            return a;
        }
        if (noPlayerSince >= 0 && now - noPlayerSince > 2000)
            log(RunLogger.DETAILED, "AI", "Character visible again after " + (now - noPlayerSince) / 1000 + " s", null);
        noPlayerSince = -1;

        // danger memory per enemy on screen
        dangerById.clear();
        nearbyEnemies = 0;
        for (Observation.Thing e : o.enemies) {
            dangerById.put(e.id, tu.useEnemyMemory ? mem.danger(e.sig) : 0.3f);
            e.danger = dangerById.get(e.id);
            if (Math.hypot(e.x - p.x, e.y - p.y) < tile * 5) nearbyEnemies++;
        }
        List<Observation.Thing> active = new ArrayList<>();
        for (Observation.Thing e : o.enemies) if (!Boolean.TRUE.equals(combat.ignored.get(e.id))) active.add(e);
        if (!active.isEmpty()) lastEnemyT = now;

        // ---------- objective
        Objective obj;
        if (btnStep >= 0 || (wantButtonTest && active.isEmpty() && o.shots.isEmpty() && now - lastEnemyT > 2500)) obj = Objective.BUTTON_TEST;
        else if (!active.isEmpty()) obj = Objective.FIGHT;
        else if (roomHadEnemies && now - lastEnemyT < 1800) obj = Objective.HOLD;
        else if (!reachablePickups(o).isEmpty()) obj = Objective.LOOT;
        else obj = Objective.EXPLORE;
        if (obj != Objective.BUTTON_TEST && btnStep >= 0) { btnStep = -1; }   // interrupted by enemies
        if (obj != objective) onObjectiveChange(objective, obj, o, now);
        objective = obj;

        if (obj == Objective.BUTTON_TEST) return buttonTest(o, now, a);

        // ---------- goal direction for the objective
        float[] goal = null;
        float goalW = 0;
        CombatDecisionSystem.TargetChoice tc = null;
        boolean lowHp = hp >= 0 && hp <= 2;
        switch (obj) {
            case FIGHT: {
                tc = combat.chooseTarget(o, tu, dangerById, mem.shotTilesPerS * tile, now, ctx.stats().hitsLanded);
                if (tc != null) {
                    Observation.Thing e = tc.target;
                    float d = (float) Math.hypot(e.x - p.x, e.y - p.y);
                    float keep = tu.keepDistTiles * tile * (lowHp ? 1.4f : 1f) * (e.kind == Observation.Kind.BOSS ? 1.3f : 1f);
                    float ux = (e.x - p.x) / Math.max(1, d), uy = (e.y - p.y) / Math.max(1, d);
                    if (!tc.lineOfSight) {
                        // walk around furniture to get a clear shot
                        float[] pd = nav.pathDir(o, (int) (e.x / o.cell), (int) (e.y / o.cell), camAccX, camAccY, now);
                        goal = pd != null ? pd : new float[]{ux, uy};
                        goalW = 0.9f;
                        objectiveText = "getting a clear shot at " + e.label();
                    } else if (d > keep * 1.25f && !lowHp) {
                        goal = new float[]{ux, uy};
                        goalW = 0.25f + tu.aggression;
                        objectiveText = "closing in on " + e.label();
                    } else if (d < keep * 0.75f) {
                        goal = new float[]{-ux, -uy};
                        goalW = 0.8f;
                        objectiveText = "backing off from " + e.label();
                    } else {
                        if (rnd.nextFloat() < 0.01f) strafeSign = -strafeSign;
                        goal = new float[]{-uy * strafeSign, ux * strafeSign};
                        goalW = 0.3f;
                        objectiveText = "circling " + e.label() + " at range";
                    }
                    targetText = tc.target.label();
                    a.sing = true;
                    a.aimX = tc.aimX; a.aimY = tc.aimY;
                    aimDirX = a.aimX; aimDirY = a.aimY;
                    if (e.id != lastTargetId) onNewTarget(tc, o, now);
                }
                break;
            }
            case HOLD:
                objectiveText = "room looks clear; waiting a moment for more enemies";
                targetText = "-";
                break;
            case LOOT: {
                Observation.Thing it = reachablePickups(o).get(0);
                float[] pd = nav.pathDir(o, (int) (it.x / o.cell), (int) (it.y / o.cell), camAccX, camAccY, now);
                goal = pd != null ? pd : unit(it.x - p.x, it.y - p.y);
                goalW = 0.9f * tu.lootWeight + 0.3f;
                objectiveText = "picking up " + (it.sig.isEmpty() ? "an item" : it.sig);
                targetText = "-";
                break;
            }
            default: {
                goal = exploreGoal(o, now);
                goalW = 0.6f + 0.6f * tu.exploreWeight;
                targetText = "-";
            }
        }

        // ---------- stuck?
        if (now < nav.escapeUntil) { goal = new float[]{nav.escapeX, nav.escapeY}; goalW = 2.5f; objectiveText = "getting unstuck"; }

        // ---------- movement
        float speed = mem.walkTilesPerS * tile;
        CombatDecisionSystem.MoveChoice mv = combat.chooseMove(o, tu, mem, goal, goalW, speed, prevMx, prevMy, dangerById);
        threatLevel = mv.threat;
        a.moveX = mv.dx; a.moveY = mv.dy;
        if (mv.dodging && now - lastDodgeLog > 800) logDodge(o, mv, now);

        if (nav.checkStuck(o, (float) Math.hypot(a.moveX, a.moveY), a.moveX, a.moveY, camAccX, camAccY, now)) onStuck(o, a, now);

        // ---------- abilities
        int dive = diveButton();
        CombatDecisionSystem.AbilityChoice ab = combat.chooseAbility(o, tu, mv, mem, dive, L, readyAt, now, hp);
        if (ab != null) useAbility(ab, o, a, mv, now);

        // ---------- calibration (walking speed with the stick fully pushed)
        if (lastMag > 0.95f) { if (fullStickSince < 0) fullStickSince = now; } else fullStickSince = -1;
        if (fullStickSince >= 0 && now - fullStickSince > 400 && now - lastCalib > 500 && !raw(o).sceneCut) {
            float v = (float) Math.hypot(p.vx, p.vy) / tile;
            if (v > 0.8f && v < 25) { mem.calibrateWalk(v); lastCalib = now; }
        }
        return a;
    }

    private Observation raw(Observation o) { return o; }

    private static float[] unit(float x, float y) {
        float l = (float) Math.hypot(x, y);
        return l < 1e-3f ? new float[]{0, 0} : new float[]{x / l, y / l};
    }

    // =============================================================================== explore

    private float[] exploreGoal(Observation o, long now) {
        Observation.Thing p = o.player;
        int unvisited = nav.unvisitedCount();
        float cov = nav.coverage(o, camAccX, camAccY);
        // Without unexplored rooms, the way on (stairs/portal) must be somewhere already open: sweep rooms first.
        boolean sweepFirst = unvisited == 0 && cov < 0.8f;
        if (sweepFirst || !o.map.ok && cov < 0.8f) {
            int[] c = nav.sweepCell(o, camAccX, camAccY);
            if (c != null) {
                objectiveText = String.format(Locale.US, "sweeping the room (%.0f%% walked)", cov * 100);
                float[] d = nav.pathDir(o, c[0], c[1], camAccX, camAccY, now);
                if (d != null) return d;
            }
        }
        int[] dir = pickExit(o, now);
        if (dir == null) {
            int[] c = nav.sweepCell(o, camAccX, camAccY);
            if (c != null) { objectiveText = "no door known; sweeping"; return nav.pathDir(o, c[0], c[1], camAccX, camAccY, now); }
            objectiveText = "nothing left to explore here";
            return null;
        }
        int[] door = nav.doorCell(o, dir);
        String side = sideName(dir);
        if (door == null) { objectiveText = "heading " + side; return new float[]{dir[0], dir[1]}; }
        float dx = (door[0] + 0.5f) * o.cell - p.x, dy = (door[1] + 0.5f) * o.cell - p.y;
        float tile = 19 * o.unit;
        if (Math.hypot(dx, dy) < tile * 1.6f || (pushDir != null && pushDir == dir && pushSince >= 0)) {
            // at the doorway: keep walking through
            if (pushDir != dir) { pushDir = dir; pushSince = now; }
            if (now - pushSince > 3000) {
                // door didn't take us anywhere: probably closed or not a door
                doorBlacklist.put(nav.absX + "," + nav.absY + "," + dir[0] + "," + dir[1], now + 30000);
                log(RunLogger.BASIC, "MISTAKE", "The " + side + " exit didn't lead anywhere after 3 s of walking into it; trying another way", null);
                mem.mistake("walked into a closed or false door");
                ctx.stats().mistakes++;
                pushDir = null; pushSince = -1; chosenExit = null;
            }
            objectiveText = "walking through the " + side + " door";
            return new float[]{dir[0], dir[1]};
        }
        pushDir = null; pushSince = -1;
        objectiveText = (unvisited > 0 ? "going to the unexplored room " : "going back through ") + side;
        float[] pd = nav.pathDir(o, door[0], door[1], camAccX, camAccY, now);
        return pd != null ? pd : unit(dx, dy);
    }

    private int[] pickExit(Observation o, long now) {
        int[] d = nav.exitDirection();
        int[][] all = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<int[]> order = new ArrayList<>();
        if (d != null) order.add(d);
        for (int[] x : all) if (d == null || x[0] != d[0] || x[1] != d[1]) order.add(x);
        for (int[] x : order) {
            Long until = doorBlacklist.get(nav.absX + "," + nav.absY + "," + x[0] + "," + x[1]);
            if (until != null && until > now) continue;
            // don't pick a side with no floor reaching it when the map gives no hint
            if (d == null && nav.doorCell(o, x) == null) continue;
            if (chosenExit == null || chosenExit[0] != x[0] || chosenExit[1] != x[1]) {
                chosenExit = x;
                if (now - lastObjectiveLog > 1000) {
                    Pending pd = newPending("explore", now, 20000);
                    pd.situation = String.format(Locale.US, "Room #%d is clear; the map shows %d unexplored room(s); %.0f%% of this room walked",
                            roomIndex, nav.unvisitedCount(), nav.coverage(o, camAccX, camAccY) * 100);
                    pd.decision = "Leave by the " + sideName(x) + " door";
                    pd.reason = d != null && x == d ? (nav.unvisitedCount() > 0 ? "it is the first step of the shortest route to an unexplored room on the minimap"
                            : "every known room is explored; this leads to the room visited longest ago")
                            : "the preferred exit failed or the map gave no route; trying the next side that has floor";
                }
            }
            return x;
        }
        return null;
    }

    private static String sideName(int[] d) {
        return d[0] > 0 ? "right" : d[0] < 0 ? "left" : d[1] > 0 ? "bottom" : "top";
    }

    private List<Observation.Thing> reachablePickups(Observation o) {
        List<Observation.Thing> out = new ArrayList<>();
        if (o.player == null) return out;
        for (Observation.Thing t : o.pickups) if (o.walkable(t.x, t.y + 2) || o.walk == null) out.add(t);
        out.sort((x, y) -> Float.compare(dist(x, o.player), dist(y, o.player)));
        return out;
    }

    private static float dist(Observation.Thing a, Observation.Thing b) { return (float) Math.hypot(a.x - b.x, a.y - b.y); }

    // =============================================================================== button test

    private int diveButton() {
        if (mem.diveButton >= 0) return mem.diveButton;
        return L.buttonFor("DIVE");
    }

    private Action buttonTest(Observation o, long now, Action a) {
        Observation.Thing p = o.player;
        float tile = 19 * o.unit;
        objectiveText = "testing which button is DIVE (button " + (Math.max(0, btnStep) + 1) + " of 4)";
        if (btnStep < 0) {
            btnStep = 0; btnT = now;
            log(RunLogger.BASIC, "AI", "Button test: pressing each of the 4 action buttons once while standing still, to find the dodge (DIVE)", null);
        }
        long since = now - btnT;
        if (since < 600) return a;                         // stand still
        if (since < 700) {
            if (a.button < 0 && btnX == 0 && btnY == 0) { btnX = p.x - camAccX; btnY = p.y - camAccY; a.button = btnStep; a.note = "button test"; }
            return a;
        }
        if (since < 1250) return a;                        // watch where the character goes
        btnDisp[btnStep] = (float) Math.hypot(p.x - camAccX - btnX, p.y - camAccY - btnY) / tile;
        log(RunLogger.DETAILED, "AI", String.format(Locale.US, "Button test: button %d (%s icon) moved the character %.2f tiles",
                btnStep + 1, ScreenLayout.DEFAULT_BUTTON_NAMES[btnStep], btnDisp[btnStep]), null);
        btnX = btnY = 0;
        btnStep++;
        btnT = now;
        if (btnStep >= 4) {
            int best = 0;
            for (int i = 1; i < 4; i++) if (btnDisp[i] > btnDisp[best]) best = i;
            mem.buttonsTested = true;
            wantButtonTest = false;
            if (btnDisp[best] > 1.0f) {
                int old = L.buttonFor("DIVE");
                if (old >= 0 && old != best) L.btnRole[old] = L.btnRole[best];
                L.btnRole[best] = "DIVE";
                mem.diveButton = best;
                log(RunLogger.BASIC, "AI", String.format(Locale.US, "Button test done: button %d is DIVE (moved %.1f tiles). Roles now: %s",
                        best + 1, btnDisp[best], String.join(", ", L.btnRole)), null);
            } else {
                log(RunLogger.BASIC, "AI", "Button test done: no button moved the character more than a tile; keeping the default roles (" + String.join(", ", L.btnRole) + ")", null);
            }
            btnStep = -1;
        }
        return a;
    }

    // =============================================================================== abilities

    private void useAbility(CombatDecisionSystem.AbilityChoice ab, Observation o, Action a, CombatDecisionSystem.MoveChoice mv, long now) {
        int btn = ab.role.equals("ULT") ? 4 : ab.role.equals("DIVE") ? diveButton() : L.buttonFor(ab.role);
        if (btn < 0) return;
        if (btn == 4) { a.ult = true; readyAt[4] = now + 3000; ctx.stats().ultsUsed++; }
        else {
            a.button = btn;
            readyAt[btn] = now + (ab.role.equals("DIVE") ? 1400 : 3500);
            mem.btnUses[btn]++;
            if (ab.role.equals("DIVE") && mv != null && mv.dx == 0 && mv.dy == 0) {
                // dive needs a direction: away from the projectile
                a.moveX = -mv.goalDx; a.moveY = -mv.goalDy;
            }
        }
        ctx.stats().abilitiesUsed++;
        if (!ab.role.equals("DIVE")) lastAbilityT = now;
        AbilityUse u = new AbilityUse(); u.btn = btn; u.t = now; u.kills = ctx.stats().enemiesDefeated; u.dmg = ctx.stats().damageReceived;
        abilityUses.add(u);
        log(RunLogger.BASIC, "ABILITY", "Used " + ab.role + (btn < 4 ? " (button " + (btn + 1) + ")" : ""), Json.map("why", ab.why));
        Pending pd = newPending("ability", now, ab.role.equals("DIVE") ? 800 : 1500);
        pd.situation = situation(o);
        pd.decision = "Use " + ab.role;
        pd.reason = ab.why;
        lastDecision = pd.decision + ": " + ab.why;
    }

    private void evaluateAbilities(long now) {
        for (Iterator<AbilityUse> it = abilityUses.iterator(); it.hasNext(); ) {
            AbilityUse u = it.next();
            if (now - u.t < 1500) continue;
            if (u.btn < 4) {
                if (ctx.stats().enemiesDefeated > u.kills) mem.btnKills[u.btn]++;
                if (ctx.stats().damageReceived > u.dmg) mem.btnHurt[u.btn]++;
            }
            it.remove();
        }
    }

    // =============================================================================== health

    private void updateHealth(Observation o, long now) {
        if (o.hp < 0) return;
        if (o.hp == hpCand && o.hpMax == hpMax) hpCandN++;
        else { hpCand = o.hp; hpCandN = 1; if (o.hpMax != hpMax && hpMax >= 0 && o.hp == hpCand) { /* max changing */ } }
        int need = 3;
        if (hp < 0) {
            if (hpCandN >= need) {
                hp = hpCand; hpMax = o.hpMax;
                RunStats s = ctx.stats();
                s.startHp = hp; s.lowestHp = hp; s.hpMax = hpMax;
                log(RunLogger.BASIC, "STATE", "Character spawned with " + hearts(hp) + " of " + hearts(hpMax) + " hearts", null);
            } else if (o.hpMax > 0) { hpMax = o.hpMax; hpCand = o.hp; }
            return;
        }
        if (hpCandN < need || hpCand == hp) {
            if (hpCandN >= need && o.hpMax != hpMax && o.hpMax > 0) {
                log(RunLogger.BASIC, "ITEM", "Maximum health changed from " + hearts(hpMax) + " to " + hearts(o.hpMax) + " hearts", null);
                hpMax = o.hpMax; ctx.stats().hpMax = hpMax;
            }
            return;
        }
        int old = hp;
        hp = hpCand;
        if (o.hpMax > 0 && o.hpMax != hpMax) { hpMax = o.hpMax; ctx.stats().hpMax = hpMax; }
        RunStats s = ctx.stats();
        if (hp < old) {
            int lost = old - hp;
            s.damageReceived += lost;
            s.lowestHp = Math.min(s.lowestHp < 0 ? hp : s.lowestHp, hp);
            lastHurtT = now;
            hpAfterLastHurt = hp;
            String cause = attributeDamage(lastObs, lost);
            log(RunLogger.BASIC, "DAMAGE", "Health reduced from " + hearts(old) + " to " + hearts(hp) + " hearts — " + cause,
                    Json.map("lostHalfHearts", lost, "hp", hp, "hpMax", hpMax));
            if (hp == 0) log(RunLogger.BASIC, "STATE", "Health reached zero", null);
        } else {
            s.healed += hp - old;
            s.itemsCollected++;
            log(RunLogger.BASIC, "ITEM", "Health restored from " + hearts(old) + " to " + hearts(hp) + " hearts", null);
        }
    }

    private String attributeDamage(Observation o, int lost) {
        if (o == null || o.player == null) return "cause not visible";
        float tile = 19 * o.unit;
        Observation.Thing p = o.player;
        Observation.Thing ne = null; float nd = 1e9f;
        for (Observation.Thing e : o.enemies) { float d = dist(e, p); if (d < nd) { nd = d; ne = e; } }
        Observation.Thing ns = null; float sd = 1e9f;
        for (Observation.Thing s : o.shots) { float d = dist(s, p); if (d < sd) { sd = d; ns = s; } }
        boolean predicted = false;
        int active = 0;
        for (Threat t : threats.values()) if (!t.resolved) { active++; if (ns != null && t.id == ns.id) predicted = true; }
        if (active > 0) {
            ctx.stats().dodgesFailed++;
            for (Threat t : threats.values()) t.resolved = true;
        }
        String cause;
        if (ne != null && nd < tile * 1.8f && (ns == null || nd < sd)) {
            cause = "contact with " + ne.label();
            mem.enemy(ne.sig).hurtNear++;
            mem.mistake("touched an enemy");
        } else if (ns != null && sd < tile * 2.5f) {
            cause = predicted ? "hit by projectile #" + ns.id + " that was predicted but not avoided" : "hit by projectile #" + ns.id + " noticed too late";
            mem.mistake(predicted ? "hit by a predicted projectile" : "hit by an unseen projectile");
            if (ne != null && nd < tile * 8) mem.enemy(ne.sig).hurtNear++;
        } else if (ne != null && nd < tile * 4) {
            cause = "probably " + ne.label() + " (" + fmt(nd / tile) + " tiles away)";
            mem.enemy(ne.sig).hurtNear++;
            mem.mistake("hurt by something not seen");
        } else {
            cause = "cause not visible (hazard or off-screen attack)";
            mem.mistake("hurt by something not seen");
        }
        ctx.stats().mistakes++;
        return cause;
    }

    static String hearts(int halfHearts) {
        if (halfHearts < 0) return "?";
        return halfHearts % 2 == 0 ? Integer.toString(halfHearts / 2) : (halfHearts / 2) + "½";
    }

    // =============================================================================== rooms

    private void updateRoom(Observation o, long now) {
        if (!o.map.ok) return;
        StringBuilder b = new StringBuilder();
        List<String> cells = new ArrayList<>();
        for (int[] c : o.map.visited) cells.add("v" + (c[0] - o.map.curX) + "," + (c[1] - o.map.curY));
        for (int[] c : o.map.unvisited) cells.add("u" + (c[0] - o.map.curX) + "," + (c[1] - o.map.curY));
        java.util.Collections.sort(cells);
        for (String c : cells) b.append(c).append(';');
        String sig = b.toString();
        int total = o.map.total();
        if (roomSig == null) {
            roomSig = sig; roomIndex = 1; roomEnterT = now; maxKnown = total;
            nav.enterRoom(null, now);
            nav.updateMap(o.map, now);
            ctx.stats().roomsEntered = 1;
            log(RunLogger.BASIC, "ROOM", "Starting room (#1); the map shows " + o.map.unvisited.size() + " unexplored room(s)", null);
            return;
        }
        if (sig.equals(roomSig)) { sigCandN = 0; nav.updateMap(o.map, now); maxKnown = Math.max(maxKnown, total); ctx.stats().mapRoomsKnown = Math.max(ctx.stats().mapRoomsKnown, nav.knownCount()); return; }
        if (sig.equals(sigCand)) sigCandN++; else { sigCand = sig; sigCandN = 1; }
        if (sigCandN < 4) return;
        // the map changed and stayed changed: which way did we move?
        int[] dir = guessMove(roomSig, sig);
        RunStats s = ctx.stats();
        long spent = now - roomEnterT;
        boolean hadVisited = roomSig.contains("v");
        if (o.map.visited.isEmpty() && hadVisited) {
            s.floorsCompleted++;
            s.floor++;
            nav.resetFloor();
            log(RunLogger.BASIC, "FLOOR", "Floor " + (s.floor - 1) + " complete — now on floor " + s.floor, null);
            dir = null;
            maxKnown = total;
        }
        if (roomHadEnemies && !roomClearedFlag && now - lastEnemyT > 600) {
            roomClearedFlag = true;
            s.roomsCleared++;
            log(RunLogger.BASIC, "ROOM", String.format(Locale.US, "Room #%d cleared in %.1f s", roomIndex, (lastEnemyT - roomEnterT) / 1000f), Json.map("room", roomIndex));
        }
        log(RunLogger.DETAILED, "ROOM", String.format(Locale.US, "Left room #%d after %.1f s%s", roomIndex, spent / 1000f, roomClearedFlag ? "" : " (not cleared)"),
                Json.map("room", roomIndex, "timeMs", spent, "cleared", roomClearedFlag));
        roomIndex++;
        s.roomsEntered++;
        roomSig = sig; sigCandN = 0; roomEnterT = now;
        roomHadEnemies = false; roomClearedFlag = false;
        chosenExit = null; pushDir = null; pushSince = -1;
        combat.forgetTargets();
        nav.enterRoom(dir, now);
        nav.updateMap(o.map, now);
        log(RunLogger.BASIC, "ROOM", "Entered room #" + roomIndex + (dir != null ? " through the " + sideName(dir) + " door" : "")
                + "; " + nav.unvisitedCount() + " unexplored room(s) left on the map", Json.map("room", roomIndex));
        resolveExplorePending(true);
    }

    /** Old map relative to the new one: the old room now sits one step opposite to where we went. */
    private static int[] guessMove(String oldSig, String newSig) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) if (newSig.contains("v" + (-d[0]) + "," + (-d[1]) + ";")) {
            // the room we came from is visited and adjacent opposite to the move
            if (oldSig.contains("u" + d[0] + "," + d[1] + ";") || oldSig.contains("v" + d[0] + "," + d[1] + ";")) return d;
        }
        for (int[] d : dirs) if (newSig.contains("v" + (-d[0]) + "," + (-d[1]) + ";")) return d;
        return null;
    }

    // =============================================================================== enemies

    private void updateEnemies(Observation o, long now) {
        RunStats s = ctx.stats();
        float tile = 19 * o.unit;
        if (o.sceneCut) { seen.clear(); }
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (Observation.Thing e : o.enemies) {
            present.add(e.id);
            Seen z = seen.get(e.id);
            if (z == null) { z = new Seen(); z.id = e.id; z.first = now; seen.put(e.id, z); }
            z.last = now; z.x = e.x; z.y = e.y; z.frames++; z.sig = e.sig;
            z.boss |= e.kind == Observation.Kind.BOSS;
            if (!z.confirmed && z.frames >= 4) {
                z.confirmed = true;
                s.enemiesSeen++;
                mem.enemy(e.sig).encounters++;
                roomHadEnemies = true;
                String where = o.player == null ? "" : " to the " + Action.compass(e.x - o.player.x, e.y - o.player.y)
                        + ", " + fmt(dist(e, o.player) / tile) + " tiles away";
                if (z.boss) {
                    s.bossesSeen++;
                    log(RunLogger.BASIC, "BOSS", "Boss encountered: " + e.sig + where, Json.map("id", e.id));
                } else {
                    log(RunLogger.DETAILED, "ENEMY", "Enemy detected: " + e.sig + where, Json.map("id", e.id, "danger", mem.danger(e.sig)));
                }
            }
        }
        for (Iterator<Map.Entry<Integer, Seen>> it = seen.entrySet().iterator(); it.hasNext(); ) {
            Seen z = it.next().getValue();
            if (present.contains(z.id) || now - z.last < 650) continue;
            it.remove();
            if (!z.confirmed) continue;
            boolean inside = o.walk == null || (z.x > o.roomX0 + tile && z.x < o.roomX1 - tile && z.y > o.roomY0 + tile && z.y < o.roomY1 - tile);
            boolean cut = now - lastSceneCutT < 1000;
            boolean abilityNear = now - lastAbilityT < 1800 && o.player != null && Math.hypot(z.x - o.player.x, z.y - o.player.y) < tile * 7;
            if ((z.hits > 0 || abilityNear) && inside && !cut) {
                s.enemiesDefeated++;
                Learner.EnemyStats es = mem.enemy(z.sig);
                es.kills++; es.killMsSum += z.last - z.first;
                if (z.boss) {
                    s.bossesDefeated++;
                    bossDefeatedT = now;
                    log(RunLogger.BASIC, "KILL", "Boss defeated (" + z.sig + ") after " + fmt((z.last - z.first) / 1000f) + " s", Json.map("id", z.id, "hits", z.hits));
                } else {
                    log(RunLogger.DETAILED, "KILL", "Enemy defeated: " + z.sig + " (" + z.hits + " hits, " + fmt((z.last - z.first) / 1000f) + " s)", Json.map("id", z.id, "hits", z.hits));
                }
                for (Pending p : pending) if (p.targetId == z.id && p.evalAt > now) p.evalAt = now;   // evaluate the target decision now
            } else {
                log(RunLogger.DEBUG, "ENEMY", "Lost sight of " + z.sig + " #" + z.id + (z.hits == 0 ? " (never hit — not counted as a kill)" : " (left the room or hidden)"), null);
            }
        }
        // room cleared?
        int activeNow = 0;
        for (Observation.Thing e : o.enemies) if (!Boolean.TRUE.equals(combat.ignored.get(e.id))) activeNow++;
        if (roomHadEnemies && !roomClearedFlag && activeNow == 0 && o.shots.isEmpty() && now - lastEnemyT > 1500 && lastEnemyT > roomEnterT) {
            roomClearedFlag = true;
            s.roomsCleared++;
            log(RunLogger.BASIC, "ROOM", String.format(Locale.US, "Room #%d cleared in %.1f s", roomIndex, (now - roomEnterT) / 1000f), Json.map("room", roomIndex));
        }
    }

    private void updateShots(Observation o, long now) {
        float tile = 19 * o.unit;
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (Observation.Thing sh : o.ownShots) {
            present.add(sh.id);
            ownShots.put(sh.id, new float[]{sh.x, sh.y, now});
            if (sh.ageFrames >= 3 && now - lastCalib > 300) {
                float v = (float) Math.hypot(sh.vx, sh.vy) / tile;
                if (v > 3 && v < 40) mem.calibrateShot(v);
            }
        }
        for (Iterator<Map.Entry<Integer, float[]>> it = ownShots.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, float[]> en = it.next();
            if (present.contains(en.getKey())) continue;
            float[] v = en.getValue();
            if (now - (long) v[2] < 120) continue;
            it.remove();
            // vanished on an enemy = a hit
            for (Observation.Thing e : o.enemies) {
                if (Math.hypot(e.x - v[0], e.y - v[1]) < e.r + tile * 0.7f) {
                    ctx.stats().hitsLanded++;
                    Seen z = seen.get(e.id);
                    if (z != null) z.hits++;
                    log(RunLogger.DEBUG, "ATTACK", "Note hit " + e.label(), null);
                    break;
                }
            }
        }
    }

    private void updatePickups(Observation o, long now) {
        float tile = 19 * o.unit;
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (Observation.Thing t : o.pickups) { present.add(t.id); pickups.put(t.id, new float[]{t.x, t.y, now}); }
        for (Iterator<Map.Entry<Integer, float[]>> it = pickups.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, float[]> en = it.next();
            if (present.contains(en.getKey())) continue;
            float[] v = en.getValue();
            if (now - (long) v[2] < 200) continue;
            it.remove();
            if (o.player != null && Math.hypot(o.player.x - v[0], o.player.y - v[1]) < tile * 1.6f) {
                ctx.stats().itemsCollected++;
                log(RunLogger.BASIC, "ITEM", "Item collected (coin/key) at the character's position", null);
            }
        }
    }

    private void updateThreats(Observation o, long now) {
        if (o.player == null) return;
        Observation.Thing p = o.player;
        float tile = 19 * o.unit;
        java.util.Set<Integer> present = new java.util.HashSet<>();
        for (Observation.Thing sh : o.shots) {
            present.add(sh.id);
            float[] ap = CombatDecisionSystem.approach(sh, p.x, p.y, 0, 0, tu.dodgeHorizonS);
            float R = p.r + sh.r + tile * 0.2f;
            Threat t = threats.get(sh.id);
            if (t == null && ap[0] < R && ap[1] > 0) {
                t = new Threat(); t.id = sh.id; t.first = now; t.dist = ap[0]; t.time = ap[1];
                threats.put(sh.id, t);
            } else if (t != null && !t.resolved && ap[1] == 0 && ap[0] > R * 1.5f) {
                // it's past us without a hit
                t.resolved = true;
                ctx.stats().dodgesOk++;
                log(RunLogger.DEBUG, "DODGE", "Dodged projectile #" + sh.id, null);
            }
        }
        for (Iterator<Map.Entry<Integer, Threat>> it = threats.entrySet().iterator(); it.hasNext(); ) {
            Threat t = it.next().getValue();
            if (present.contains(t.id)) continue;
            if (!t.resolved && now - lastHurtT > 250) {
                ctx.stats().dodgesOk++;
                log(RunLogger.DEBUG, "DODGE", "Projectile #" + t.id + " gone without hitting", null);
            }
            it.remove();
        }
    }

    // =============================================================================== decisions

    private Pending newPending(String kind, long now, long evalIn) {
        Pending p = new Pending();
        p.kind = kind; p.t = gt(); p.evalAt = now + evalIn;
        p.hp = hp; p.kills = ctx.stats().enemiesDefeated; p.rooms = ctx.stats().roomsEntered; p.dmg = ctx.stats().damageReceived;
        p.stuck = ctx.stats().timesStuck;
        pending.add(p);
        ctx.stats().keyDecisions++;
        return p;
    }

    private String situation(Observation o) {
        StringBuilder b = new StringBuilder();
        b.append(o.enemies.size()).append(o.enemies.size() == 1 ? " enemy" : " enemies");
        if (o.player != null && !o.enemies.isEmpty()) {
            List<String> dirs = new ArrayList<>();
            for (Observation.Thing e : o.enemies) {
                String d = Action.compass(e.x - o.player.x, e.y - o.player.y);
                if (!dirs.contains(d)) dirs.add(d);
            }
            b.append(" (").append(String.join(", ", dirs)).append(")");
        }
        b.append(", ").append(o.shots.size()).append(" enemy projectile(s) on screen, health ").append(hearts(hp)).append("/").append(hearts(hpMax));
        return b.toString();
    }

    private void logDodge(Observation o, CombatDecisionSystem.MoveChoice mv, long now) {
        lastDodgeLog = now;
        Pending pd = newPending("dodge", now, 900);
        float tile = 19 * o.unit;
        pd.situation = situation(o) + String.format(Locale.US, "; projectile #%d predicted to pass %.1f tiles from the character in %.2f s",
                mv.worstShotId, mv.worstShotDist / tile, mv.worstShotTime);
        pd.decision = mv.dx == 0 && mv.dy == 0 ? "Stand still" : "Move " + Action.compass(mv.dx, mv.dy);
        pd.reason = "Lowest predicted danger of 17 options: " + mv.why;
        lastDecision = pd.decision + " (dodge)";
        ctx.log(RunLogger.DETAILED, "AI", "AI moved " + Action.compass(mv.dx, mv.dy) + " to avoid projectile #" + mv.worstShotId, null);
    }

    private void onNewTarget(CombatDecisionSystem.TargetChoice tc, Observation o, long now) {
        lastTargetId = tc.target.id;
        Pending pd = newPending("target", now, 6000);
        pd.targetId = tc.target.id;
        pd.situation = situation(o);
        pd.decision = "Sing at " + tc.target.label();
        pd.reason = "Highest target score: " + tc.why;
        lastDecision = pd.decision;
        log(RunLogger.DETAILED, "ATTACK", "AI attacked " + tc.target.label(), null);
    }

    private void onObjectiveChange(Objective from, Objective to, Observation o, long now) {
        if (to == Objective.FIGHT || to == Objective.WAIT_PLAYER || to == Objective.BUTTON_TEST) return;
        if (now - lastObjectiveLog < 1500 && to == lastLoggedObjective) return;
        lastObjectiveLog = now;
        lastLoggedObjective = to;
        log(RunLogger.DETAILED, "AI", "Objective: " + from + " → " + to, null);
        if (to == Objective.LOOT) {
            Pending pd = newPending("loot", now, 4000);
            pd.situation = situation(o) + ", " + o.pickups.size() + " item(s) visible";
            pd.decision = "Walk to the nearest item";
            pd.reason = "no enemies left on screen; items are worth picking up (loot weight " + fmt(tu.lootWeight) + ")";
        }
    }

    private void onStuck(Observation o, Action a, long now) {
        ctx.stats().timesStuck++;
        mem.mistake("got stuck on an obstacle");
        ctx.stats().mistakes++;
        log(RunLogger.BASIC, "STUCK", "Stuck: pushed " + Action.compass(prevMx, prevMy) + " for 1.2 s without moving; side-stepping "
                + Action.compass(nav.escapeX, nav.escapeY) + " and avoiding that spot", null);
        Pending pd = newPending("stuck", now, 2500);
        pd.situation = "The character stopped moving while the stick was pushed " + Action.compass(prevMx, prevMy) + " (" + objectiveText + ")";
        pd.decision = "Side-step " + Action.compass(nav.escapeX, nav.escapeY) + " and re-plan around the blocked spot";
        pd.reason = "an obstacle the floor reader missed is in the way; marking it blocked for 8 s makes the path finder go around";
        lastDecision = pd.decision;
    }

    private void resolveExplorePending(boolean roomChanged) {
        for (Pending p : pending) if (p.kind.equals("explore")) p.evalAt = 0;
    }

    /** Fills in Result/Assessment for decisions whose wait is over, and writes them to the log. */
    public void evaluatePending(long now, boolean all) {
        RunStats s = ctx.stats();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (!all && now < p.evalAt) continue;
            if (p.situation == null) { it.remove(); continue; }
            it.remove();
            int lost = s.damageReceived - p.dmg;
            int kills = s.enemiesDefeated - p.kills;
            String result, assess;
            switch (p.kind) {
                case "dodge":
                    if (lost == 0) { result = "No damage taken in the next second"; assess = "Successful"; }
                    else { result = "Took " + lost + " half-heart(s) of damage anyway"; assess = lost == 1 && s.dodgesOk > 0 ? "Partially successful" : "Failed"; }
                    break;
                case "target":
                    if (kills > 0) { result = "Target defeated" + (lost > 0 ? ", but took " + lost + " half-heart(s)" : " without taking damage"); assess = lost > 0 ? "Partially successful" : "Successful"; }
                    else { result = "Target still alive after " + (now - p.evalAt + 6000) / 1000 + " s" + (lost > 0 ? ", took " + lost + " half-heart(s)" : ""); assess = lost > 0 ? "Failed" : "Partially successful"; }
                    break;
                case "ability":
                    if (kills > 0) { result = kills + " enemy(ies) defeated within 1.5 s"; assess = "Successful"; }
                    else if (lost > 0) { result = "No kill, and took " + lost + " half-heart(s)"; assess = "Failed"; }
                    else { result = "No kill, no damage taken"; assess = "Partially successful"; }
                    break;
                case "explore":
                    if (s.roomsEntered > p.rooms) { result = "Reached the next room"; assess = "Successful"; }
                    else { result = "Did not reach another room within 20 s"; assess = "Failed"; }
                    break;
                case "stuck":
                    if (s.timesStuck == p.stuck) { result = "Moving again"; assess = "Successful"; }
                    else { result = "Got stuck again"; assess = "Failed"; }
                    break;
                case "loot":
                    result = lost > 0 ? "Took damage on the way" : "Done";
                    assess = lost > 0 ? "Partially successful" : "Successful";
                    break;
                default:
                    result = "-"; assess = "-";
            }
            if (all && now < p.evalAt) { result = "Run ended before the result was known"; assess = "Unknown"; }
            ctx.log(RunLogger.BASIC, "DECISION", p.decision,
                    Json.map("kind", p.kind, "decidedAt", RunLogger.clock(p.t), "situation", p.situation, "decision", p.decision,
                            "reason", p.reason, "result", result, "assessment", assess));
        }
    }

    static String fmt(float f) { return String.format(Locale.US, "%.1f", f); }
}
