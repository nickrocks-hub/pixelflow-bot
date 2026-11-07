package com.nickrocks.aibot.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Turns the picture into combat choices: which way to step (predicting every enemy projectile's path
 * and testing 16 directions), whom to shoot, where to aim (leading moving enemies), and when to use
 * abilities. Each choice keeps the numbers that decided it, so the log can say why.
 */
public final class CombatDecisionSystem {

    public static final int DIRS = 16;
    private final Random rnd;

    public CombatDecisionSystem(Random rnd) { this.rnd = rnd; }

    // ------------------------------------------------------------------------------ threats

    /** Predicted closest approach of a projectile to a point moving with velocity (pvx, pvy). */
    public static float[] approach(Observation.Thing s, float px, float py, float pvx, float pvy, float horizon) {
        float rx = s.x - px, ry = s.y - py, vx = s.vx - pvx, vy = s.vy - pvy;
        float vv = vx * vx + vy * vy;
        float t = vv < 1e-3f ? 0 : -(rx * vx + ry * vy) / vv;
        t = Math.max(0, Math.min(horizon, t));
        float dx = rx + vx * t, dy = ry + vy * t;
        return new float[]{(float) Math.hypot(dx, dy), t};
    }

    public static final class MoveChoice {
        public float dx, dy;           // chosen direction (0,0 = stand)
        public float total, threat, enemy, wall, goal;
        public float standThreat, goalOnlyThreat;   // what standing still / following the goal would have cost
        public float goalDx, goalDy;
        public int worstShotId = -1;
        public float worstShotDist, worstShotTime;
        public boolean dodging;        // the threat term changed the direction
        public String why = "";
    }

    /**
     * Scores 16 directions + standing still. {@code goal} is the direction the objective wants
     * (may be zero). Speed is pixels/s at full stick.
     */
    public MoveChoice chooseMove(Observation o, AISettings.Tuning tu, Learner mem, float[] goal, float goalW,
                                 float speed, float prevDx, float prevDy, Map<Integer, Float> dangerById) {
        Observation.Thing p = o.player;
        float tile = 19 * o.unit;
        MoveChoice best = null;
        float horizon = tu.dodgeHorizonS;
        int steps = 6;
        float gx = goal == null ? 0 : goal[0], gy = goal == null ? 0 : goal[1];
        MoveChoice stand = null, goalOnly = null;
        float bestGoalDot = -2;
        for (int k = -1; k < DIRS; k++) {
            float dx = 0, dy = 0;
            if (k >= 0) { double a = 2 * Math.PI * k / DIRS; dx = (float) Math.cos(a); dy = (float) Math.sin(a); }
            MoveChoice c = new MoveChoice();
            c.dx = dx; c.dy = dy;
            // walk the candidate forward in time, stopping at walls
            float x = p.x, y = p.y;
            boolean hitWall = false, wallSoon = false;
            float threat = 0;
            float worstD = 1e9f, worstT = 0; int worstId = -1;
            for (int s = 1; s <= steps; s++) {
                float t = horizon * s / steps;
                float nx = p.x + dx * speed * t, ny = p.y + dy * speed * t;
                boolean wallHere = o.walk != null && !o.walkable(nx, ny + tile * 0.3f);
                if (wallHere && !hitWall && t <= 0.3f) wallSoon = true;    // only walls we'd hit right away cost
                if (wallHere) hitWall = true;
                if (!hitWall) { x = nx; y = ny; }
                float weight = 1.6f - s / (float) steps;      // sooner = more important
                for (Observation.Thing sh : o.shots) {
                    float sx = sh.x + sh.vx * t, sy = sh.y + sh.vy * t;
                    float d = (float) Math.hypot(sx - x, sy - y);
                    float R = p.r + sh.r + tile * 0.35f;
                    if (d < R) { float f = (R - d) / R; threat += weight * f * f * 10; }
                    if (d < worstD) { worstD = d; worstT = t; worstId = sh.id; }
                }
                // contact with enemies that walk into us
                for (Observation.Thing e : o.enemies) {
                    float ex = e.x + e.vx * t, ey = e.y + e.vy * t;
                    float d = (float) Math.hypot(ex - x, ey - y);
                    float R = p.r + e.r * 0.8f + tile * 0.2f;
                    if (d < R) { float f = (R - d) / R; threat += weight * f * f * 6; }
                }
            }
            c.threat = threat;
            c.worstShotId = worstId; c.worstShotDist = worstD; c.worstShotTime = worstT;
            // keep-away from enemies, more for dangerous types
            float enemy = 0;
            float tMid = horizon * 0.5f;
            for (Observation.Thing e : o.enemies) {
                float dz = dangerById != null && dangerById.containsKey(e.id) ? dangerById.get(e.id) : 0.3f;
                float keep = tu.keepDistTiles * tile * (tu.useEnemyMemory ? (0.8f + dz) : 1f) * (e.kind == Observation.Kind.BOSS ? 1.4f : 1f);
                float ex = e.x + e.vx * tMid, ey = e.y + e.vy * tMid;
                float d = (float) Math.hypot(ex - x, ey - y);
                if (d < keep) { float f = (keep - d) / keep; enemy += f * f * (1.5f + 2 * dz); }
            }
            c.enemy = enemy;
            float wall = 0;
            if (k >= 0) {
                if (wallSoon) wall += 3f;
                if (o.walk != null) {
                    float ax = p.x + dx * tile * 0.9f, ay = p.y + dy * tile * 0.9f + tile * 0.3f;
                    if (!o.walkable(ax, ay)) wall += 1.5f * tu.wallMargin;
                }
            }
            c.wall = wall;
            float goalDot = dx * gx + dy * gy;
            c.goal = -goalW * goalDot;
            float inertia = -0.12f * (dx * prevDx + dy * prevDy);
            c.total = tu.dodgeWeight * threat + enemy + wall + c.goal + inertia + (k < 0 ? 0.05f : 0);
            if (k < 0) stand = c;
            if (goalDot > bestGoalDot) { bestGoalDot = goalDot; goalOnly = c; }
            if (best == null || c.total < best.total) best = c;
        }
        best.standThreat = stand.threat;
        best.goalOnlyThreat = goalOnly == null ? stand.threat : goalOnly.threat;
        best.goalDx = gx; best.goalDy = gy;
        // "dodging" = without the projectile term we'd have gone elsewhere, and it mattered
        float goalPathThreat = (gx == 0 && gy == 0) ? stand.threat : best.goalOnlyThreat;
        best.dodging = goalPathThreat > 0.4f && goalPathThreat > best.threat + 0.3f && !o.shots.isEmpty();
        best.why = String.format(Locale.US, "danger %.2f (projectiles %.2f, enemies %.2f, walls %.2f, goal %.2f); staying put %.2f, straight to goal %.2f",
                best.total, best.threat, best.enemy, best.wall, best.goal, stand.threat * tu.dodgeWeight + stand.enemy, goalPathThreat * tu.dodgeWeight);
        return best;
    }

    // ------------------------------------------------------------------------------ targets

    public static final class TargetChoice {
        public Observation.Thing target;
        public float aimX, aimY;       // unit vector
        public float score;
        public boolean lineOfSight;
        public String why = "";
    }

    private final Map<Integer, Long> targetSince = new HashMap<>();
    private final Map<Integer, Integer> hitsAtStart = new HashMap<>();
    public final Map<Integer, Boolean> ignored = new HashMap<>();
    private int lastTargetId = -1;

    public void forgetTargets() { targetSince.clear(); hitsAtStart.clear(); ignored.clear(); lastTargetId = -1; }

    /** Line of sight on the floor grid (furniture and walls block notes). */
    public static boolean los(Observation o, float x0, float y0, float x1, float y1) {
        if (o.walk == null) return true;
        float d = (float) Math.hypot(x1 - x0, y1 - y0);
        int n = Math.max(1, (int) (d / (o.cell * 0.7f)));
        int blockedRun = 0;
        for (int i = 1; i < n; i++) {
            float x = x0 + (x1 - x0) * i / n, y = y0 + (y1 - y0) * i / n;
            if (!o.walkable(x, y)) { if (++blockedRun >= 2) return false; } else blockedRun = 0;
        }
        return true;
    }

    public TargetChoice chooseTarget(Observation o, AISettings.Tuning tu, Map<Integer, Float> dangerById,
                                     float shotSpeed, long now, int hitsSoFar) {
        Observation.Thing p = o.player;
        float tile = 19 * o.unit;
        TargetChoice best = null;
        for (Observation.Thing e : o.enemies) {
            if (Boolean.TRUE.equals(ignored.get(e.id))) continue;
            float d = (float) Math.hypot(e.x - p.x, e.y - p.y);
            float dz = dangerById.containsKey(e.id) ? dangerById.get(e.id) : 0.3f;
            boolean sight = los(o, p.x, p.y, e.x, e.y);
            float s = (0.4f + (tu.useEnemyMemory ? dz : 0.3f)) * (e.kind == Observation.Kind.BOSS ? 2f : 1f)
                    * (e.seenMoving ? 1f : 0.6f) / (1 + d / (tile * 5)) * (sight ? 1f : 0.35f);
            if (e.id == lastTargetId) s *= 1.35f;          // don't flip-flop between targets
            if (best == null || s > best.score) {
                best = new TargetChoice();
                best.target = e; best.score = s; best.lineOfSight = sight;
                best.why = String.format(Locale.US, "%s at %.1f tiles, danger %.2f%s%s", e.label(), d / tile, dz,
                        sight ? "" : ", no clear shot", e.kind == Observation.Kind.BOSS ? ", boss" : "");
            }
        }
        if (best == null) { lastTargetId = -1; return null; }
        Observation.Thing e = best.target;
        // targets that never take damage are probably furniture the reader mistook for an enemy
        if (e.id != lastTargetId) { targetSince.put(e.id, now); hitsAtStart.put(e.id, hitsSoFar); }
        lastTargetId = e.id;
        long since = now - targetSince.getOrDefault(e.id, now);
        if (since > 7000 && hitsSoFar == hitsAtStart.getOrDefault(e.id, hitsSoFar) && !e.seenMoving) {
            ignored.put(e.id, true);
            best.why += " — IGNORED from now on: 7 s of singing at it landed no hits";
        }
        // aim, leading a moving target
        float ax = e.x, ay = e.y;
        if (tu.leadShots && shotSpeed > 1) {
            for (int it = 0; it < 2; it++) {
                float t = (float) Math.hypot(ax - p.x, ay - p.y) / shotSpeed;
                ax = e.x + e.vx * t * tu.leadFactor;
                ay = e.y + e.vy * t * tu.leadFactor;
            }
        }
        float dx = ax - p.x, dy = ay - p.y;
        double ang = Math.atan2(dy, dx) + Math.toRadians(rnd.nextGaussian() * tu.aimNoiseDeg);
        best.aimX = (float) Math.cos(ang); best.aimY = (float) Math.sin(ang);
        return best;
    }

    // ------------------------------------------------------------------------------ abilities

    public static final class AbilityChoice {
        public String role;      // DIVE, SMASH, DROP, SCREAM, ULT
        public String why;
    }

    public AbilityChoice chooseAbility(Observation o, AISettings.Tuning tu, MoveChoice mv, Learner mem,
                                       int diveBtn, ScreenLayout L, long[] readyAt, long now, int hp) {
        if (!tu.abilities && !(o.ultReady)) return null;
        Observation.Thing p = o.player;
        float tile = 19 * o.unit;
        int near2 = 0, near4 = 0; boolean boss = false; float closest = 1e9f;
        for (Observation.Thing e : o.enemies) {
            float d = (float) Math.hypot(e.x - p.x, e.y - p.y);
            if (d < tile * 2) near2++;
            if (d < tile * 4) near4++;
            if (e.kind == Observation.Kind.BOSS && d < tile * 7) boss = true;
            closest = Math.min(closest, d);
        }
        float eager = tu.abilityEagerness;
        // ULT: big moments only
        if (o.ultReady && now >= readyAt[4] && (boss || o.enemies.size() >= (eager > 0.7f ? 2 : 3) || (hp >= 0 && hp <= 2 && !o.enemies.isEmpty()))) {
            AbilityChoice a = new AbilityChoice(); a.role = "ULT";
            a.why = boss ? "ULT is charged and a boss is in range" : hp >= 0 && hp <= 2 ? "ULT is charged and health is low" : "ULT is charged and " + o.enemies.size() + " enemies are on screen";
            return a;
        }
        if (!tu.abilities) return null;
        // DIVE: a projectile will hit whatever we do with the stick
        if (diveBtn >= 0 && now >= readyAt[diveBtn] && mv != null && mv.threat * tu.dodgeWeight > 1.2f && mv.worstShotTime < 0.35f) {
            AbilityChoice a = new AbilityChoice(); a.role = "DIVE";
            a.why = String.format(Locale.US, "projectile #%d arrives in %.2f s and even the best step still scores danger %.2f", mv.worstShotId, mv.worstShotTime, mv.threat);
            return a;
        }
        String[] order = {"SMASH", "DROP", "SCREAM"};
        for (String role : order) {
            int b = L.buttonFor(role);
            if (b < 0 || b == diveBtn || now < readyAt[b]) continue;
            float value = mem.buttonValue(b);
            if (value < 0.15f + (1 - eager) * 0.2f) continue;
            if (role.equals("SMASH") && near2 >= 1) {
                AbilityChoice a = new AbilityChoice(); a.role = role;
                a.why = String.format(Locale.US, "enemy %.1f tiles away (melee range); past value of this button %.2f", closest / tile, value);
                return a;
            }
            if (role.equals("DROP") && near4 >= (eager > 0.7f ? 2 : 3)) {
                AbilityChoice a = new AbilityChoice(); a.role = role;
                a.why = String.format(Locale.US, "%d enemies within 4 tiles; past value %.2f", near4, value);
                return a;
            }
            if (role.equals("SCREAM") && (boss || o.enemies.size() >= (eager > 0.7f ? 3 : 4))) {
                AbilityChoice a = new AbilityChoice(); a.role = role;
                a.why = boss ? "boss within 7 tiles" : o.enemies.size() + " enemies on screen";
                a.why += String.format(Locale.US, "; past value %.2f", value);
                return a;
            }
        }
        return null;
    }
}
