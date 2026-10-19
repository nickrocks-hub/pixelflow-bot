package com.aigame.subwaysurfers.core;

/**
 * Decides what to do each frame: nothing, swipe left/right (change lane),
 * swipe up (jump) or swipe down (roll). Pure Java.
 *
 * Distances: a screen row y is turned into a "depth" D relative to the runner,
 * D = (feetY - vpY) / (y - vpY) - 1. D is 0 at the runner's feet and grows
 * towards the horizon, proportional to real distance along the track, so the
 * game's speed shows up as a steady dD/dt that the brain measures.
 */
public final class SurferBrain {

    public enum Move { NONE, LEFT, RIGHT, JUMP, ROLL }

    public static final class Params {
        /** Act when an obstacle in the runner's lane is closer than this many seconds. */
        public int reactMs = 420;
        /** A neighbouring lane must stay clear at least this much longer to be worth moving into. */
        public int safeExtraMs = 350;
        /** Wait after a lane change before the next one (the move animation). */
        public int laneCooldownMs = 230;
        /** Wait after a jump or roll. */
        public int airCooldownMs = 650;
        /** What to do about short obstacles when no lane is free: true = jump, false = roll. */
        public boolean jumpLowObstacles = true;
        /** Drift back to the middle lane when it is clear (more escape routes). */
        public boolean preferMiddle = true;
        /** Runner's horizontal position offset from screen centre, % of width. */
        public int runnerOffsetPct = 0;
        /** Starting guess for game speed, depth units per second (refined while playing). */
        public double startSpeed = 2.4;
    }

    public final Params p;
    public final TrackVision vision = new TrackVision();

    // smoothed geometry
    private double vpX = Double.NaN, vpY = Double.NaN;
    private int noTrackFrames;
    // speed estimate (depth units / s)
    private double speed;
    // lane belief: -1 left, 0 middle, +1 right
    private int lane = 0;
    private int laneVotes;
    private int pendingLaneMove;    // expected change from our own swipe
    private long pendingUntil;
    private long nextLaneOk, nextAirOk;

    // last-frame info for the overlay / replay tool
    public String status = "";
    public TrackVision.Result last;
    public final double[] laneTti = new double[3];
    /** Per lane: depth last frame and how many frames in a row it has been approaching. */
    private final double[] prevD = {Double.NaN, Double.NaN, Double.NaN};
    private final int[] approach = new int[3];
    private long prevFrameMs;
    public final boolean[] laneConfirmed = new boolean[3];
    public final boolean[] laneTall = new boolean[3];
    public int lastLane;

    public SurferBrain(Params p) { this.p = p; reset(); }

    public void reset() {
        speed = p.startSpeed;
        lane = 0; laneVotes = 0; pendingLaneMove = 0;
        nextLaneOk = nextAirOk = 0;
        noTrackFrames = 0; prevFrameMs = 0;
        for (int k = 0; k < 3; k++) { prevD[k] = Double.NaN; approach[k] = 0; }
        vpX = vpY = Double.NaN;
    }

    private double depth(double y, double feetY) {
        double dy = y - vpY;
        if (dy <= 1) return 99;
        return (feetY - vpY) / dy - 1;
    }

    public Move onFrame(int[] px, int w, int h, long nowMs) {
        TrackVision.Result r = vision.analyse(px, w, h);
        last = r;
        if (!r.ok || r.tracks.size() != 3) {
            noTrackFrames++;
            if (noTrackFrames > 3) { vision.hintX = Double.NaN; vision.hintY = Double.NaN; }
            status = noTrackFrames > 10 ? "Looking for the tracks…" : status;
            return Move.NONE;
        }
        noTrackFrames = 0;
        vision.hintX = r.vpX; vision.hintY = r.vpY;
        // smooth the vanishing point a little (it jitters by a pixel or two)
        if (Double.isNaN(vpX)) { vpX = r.vpX; vpY = r.vpY; }
        else { vpX += 0.5 * (r.vpX - vpX); vpY += 0.5 * (r.vpY - vpY); }
        double feetY = h * vision.runnerFeet;

        // ---- which lane is the runner in? The track nearest the runner's screen x.
        double runnerX = w * (0.5 + p.runnerOffsetPct / 100.0);
        int seen = 0; double best = 1e9;
        for (int k = 0; k < 3; k++) {
            double d = Math.abs(r.tracks.get(k).centerAtFeet - runnerX);
            if (d < best) { best = d; seen = k - 1; }
        }
        if (seen == lane) laneVotes = 0;
        else {
            boolean expected = nowMs < pendingUntil && seen == lane + pendingLaneMove;
            if (expected || ++laneVotes >= 4) { lane = seen; laneVotes = 0; pendingLaneMove = 0; }
        }
        lastLane = lane;

        // ---- obstacles per lane, as time to impact
        double dt = prevFrameMs == 0 ? 0 : Math.min(0.25, (nowMs - prevFrameMs) / 1000.0);
        prevFrameMs = nowMs;
        double vSum = 0; int vN = 0;
        for (int k = 0; k < 3; k++) {
            TrackVision.Track t = r.tracks.get(k);
            if (t.obstacleY < 0) {
                laneTti[k] = Double.POSITIVE_INFINITY; laneTall[k] = false;
                prevD[k] = Double.NaN; approach[k] = 0; laneConfirmed[k] = false;
                continue;
            }
            double d0 = depth(t.obstacleY, feetY);
            double d1 = depth(t.obstacleY - t.obstacleSpan, feetY);
            laneTti[k] = Math.max(0, d0) / speed * 1000.0;
            laneTall[k] = !t.railsResume || (d1 - d0) > 0.9;
            // A real obstacle comes towards us at game speed; things fixed on screen
            // (the runner, a train roof we stand on, sparkles) do not.
            if (!Double.isNaN(prevD[k]) && dt > 0) {
                double v = (prevD[k] - d0) / dt;
                if (v > 0.35 * speed && v < 3 * speed + 2) {
                    approach[k]++;
                    if (k == lane + 1 && approach[k] >= 3 && !t.blockedAtStart && d0 > 0.3 && d0 < 2.5
                            && v > 0.6 * speed && v < 1.6 * speed) { vSum += v; vN++; }
                } else if (prevD[k] - d0 < -0.5) approach[k] = 0;   // jumped further away: new obstacle
                else approach[k] = Math.max(0, approach[k] - 1);
            }
            prevD[k] = d0;
            laneConfirmed[k] = approach[k] >= 2;
        }
        int own = lane + 1;
        TrackVision.Track ownT = r.tracks.get(own);
        if (vN > 0) speed += 0.05 * (vSum / vN - speed);
        speed = Math.max(p.startSpeed * 0.8, Math.min(p.startSpeed * 2.5, speed));

        // ---- our own rails hidden right in front of us: standing on a train roof
        // (or already too late). Either way, don't swipe.
        boolean onRoof = ownT.blockedAtStart;

        double threat = laneTti[own];
        Move m = Move.NONE;
        String why;
        if (onRoof) {
            why = "on a train roof";
        } else if (threat < p.reactMs && laneConfirmed[own]) {
            // try a neighbouring lane
            int bestDir = 0; double bestTti = threat + p.safeExtraMs;
            for (int dir = -1; dir <= 1; dir += 2) {
                int k = own + dir;
                if (k < 0 || k > 2) continue;
                TrackVision.Track nt = r.tracks.get(k);
                if (nt.blockedAtStart) continue;
                if (laneTti[k] > bestTti) { bestTti = laneTti[k]; bestDir = dir; }
            }
            if (bestDir != 0 && nowMs >= nextLaneOk) {
                m = bestDir < 0 ? Move.LEFT : Move.RIGHT;
                why = "dodge " + (bestDir < 0 ? "left" : "right");
            } else if (bestDir == 0 && nowMs >= nextAirOk) {
                if (laneTall[own]) { m = Move.JUMP; why = "boxed in, jump"; }
                else { m = p.jumpLowObstacles ? Move.JUMP : Move.ROLL; why = "barrier: " + (m == Move.JUMP ? "jump" : "roll"); }
            } else why = "waiting";
        } else if (p.preferMiddle && lane != 0 && nowMs >= nextLaneOk
                && laneTti[1] > p.reactMs + 2 * p.safeExtraMs && threat > p.reactMs + p.safeExtraMs
                && !r.tracks.get(1).blockedAtStart) {
            m = lane < 0 ? Move.RIGHT : Move.LEFT;
            why = "back to middle";
        } else why = "clear";

        if (m == Move.LEFT || m == Move.RIGHT) {
            int dir = m == Move.LEFT ? -1 : 1;
            nextLaneOk = nowMs + p.laneCooldownMs;
            pendingLaneMove = dir;
            pendingUntil = nowMs + 600;
            // trust our own move straight away; vision confirms or corrects it
            lane = Math.max(-1, Math.min(1, lane + dir));
            laneVotes = 0;
        } else if (m == Move.JUMP || m == Move.ROLL) {
            nextAirOk = nowMs + p.airCooldownMs;
        }
        status = String.format("%s · lane %s · %s", why,
                lane < 0 ? "L" : lane > 0 ? "R" : "M",
                Double.isInfinite(threat) ? "clear ahead" : String.format("%.1fs ahead", threat / 1000));
        return m;
    }

    public double getSpeed() { return speed; }
}
