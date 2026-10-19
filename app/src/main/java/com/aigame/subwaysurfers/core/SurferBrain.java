package com.aigame.subwaysurfers.core;

/**
 * Decides what to do each frame: nothing, swipe left/right (change lane),
 * swipe up (jump) or swipe down (roll). Pure Java.
 *
 * Distances: a screen row y is turned into a "depth" D relative to the runner,
 * D = (feetY - vpY) / (y - vpY) - 1. D is 0 at the runner's feet and grows
 * towards the horizon, proportional to real distance along the track, so the
 * game's speed shows up as a steady dD/dt that the brain measures.
 *
 * Lanes: the vision reports tracks relative to the runner (left, own, right).
 * Which real lane the runner is in (left / middle / right) is a belief: runs
 * start in the middle, our own swipes shift it, and rails seen where a track
 * can only exist from certain lanes correct it. The bot never swipes towards a
 * side where there may be no track, nor into a lane with a train alongside.
 */
public final class SurferBrain {

    public enum Move { NONE, LEFT, RIGHT, JUMP, ROLL }

    public static final class Params {
        /** Act when an obstacle in the runner's lane is closer than this many ms. */
        public int reactMs = 650;
        /** Phone delay (screen capture + swipe) added on top of the reaction time. */
        public int latencyMs = 120;
        /** Steer towards lanes with coins and power-ups when it's safe. */
        public boolean seekCoins = true;
        /** A lane needs this much more coin "value" than ours (x) before we move for it. */
        public double coinRatio = 1.6;
        /** Ignore coin value below this (a stray sparkle). */
        public double coinMin = 80;
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
        /** Only move towards a side if we're at least this sure a track exists there. */
        public double laneSure = 0.8;
        /** Lane pitch / (near row - horizon) above this = camera at ground level (roofs ~0.43, ground ~0.56). */
        public double groundPitchRatio = 0.50;
        /** A lane we move into must be clear at least this long (ms). */
        public int targetClearMs = 900;
    }

    public final Params p;
    public final TrackVision vision = new TrackVision();

    private int noTrackFrames;
    private double pitch = Double.NaN;
    // speed estimate (depth units / s)
    private double speed;
    /** Belief that the runner is in the left / middle / right lane. */
    public final double[] belief = new double[3];
    private long laneChangeUntil;      // camera is panning after our swipe
    private long nextLaneOk, nextAirOk, nextCoinOk;

    // last-frame info for the overlay / replay tool
    public String status = "";
    public TrackVision.Result last;
    public final double[] laneTti = new double[3];
    public final double[] laneReward = new double[3];
    /** Per lane: depth last frame and how many frames in a row it has been approaching. */
    private final double[] prevD = {Double.NaN, Double.NaN, Double.NaN};
    private final int[] approach = new int[3];
    private long prevFrameMs;
    public final boolean[] laneConfirmed = new boolean[3];
    public final boolean[] laneTall = new boolean[3];
    public final boolean[] laneOk = new boolean[3];
    public int lastLane;

    public SurferBrain(Params p) { this.p = p; reset(); }

    public void reset() {
        speed = p.startSpeed;
        belief[0] = 0.05; belief[1] = 0.9; belief[2] = 0.05;   // every run starts in the middle
        laneChangeUntil = 0;
        nextLaneOk = nextAirOk = nextCoinOk = 0;
        noTrackFrames = 0; prevFrameMs = 0;
        pitch = Double.NaN;
        vision.hintX = vision.hintY = Double.NaN;
        vision.pitchHint = Double.NaN;
        for (int k = 0; k < 3; k++) { prevD[k] = Double.NaN; approach[k] = 0; laneReward[k] = 0; }
    }

    private double depth(double y, double feetY, double vpY) {
        double dy = y - vpY;
        if (dy <= 1) return 99;
        return (feetY - vpY) / dy - 1;
    }

    /** -1 left, 0 middle, +1 right: the most likely lane. */
    public int lane() {
        int b = 1;
        for (int k = 0; k < 3; k++) if (belief[k] > belief[b]) b = k;
        return b - 1;
    }

    private void normalise() {
        double t = belief[0] + belief[1] + belief[2];
        for (int k = 0; k < 3; k++) belief[k] = Math.max(0.01, belief[k] / t);
        t = belief[0] + belief[1] + belief[2];
        for (int k = 0; k < 3; k++) belief[k] /= t;
    }

    /** Our swipe moved the runner one lane (most likely; a bump would bounce it back). */
    private void shiftBelief(int dir) {
        double l = belief[0], m = belief[1], r = belief[2];
        if (dir < 0) { belief[0] = l + 0.9 * m; belief[1] = 0.1 * m + 0.9 * r; belief[2] = 0.1 * r; }
        else { belief[2] = r + 0.9 * m; belief[1] = 0.1 * m + 0.9 * l; belief[0] = 0.1 * l; }
        normalise();
    }

    /** Runner's track offset from screen centre (lane widths) in each real lane. */
    public double sideOffset = 0.29;

    /**
     * The camera only partly follows the runner, so the runner's track sits about
     * 0.29 lane widths left of the screen centre in the left lane, centred in the
     * middle lane and 0.29 right in the right lane.
     */
    private void observeLanes(TrackVision.Result r) {
        double e = r.ownOffset;
        double half = sideOffset / 2;
        int seen;
        if (Math.abs(e) < half - 0.04) seen = 0;
        else if (e <= -half - 0.04 && e > -sideOffset - 0.2) seen = -1;
        else if (e >= half + 0.04 && e < sideOffset + 0.2) seen = 1;
        else return;                                   // in between: no opinion
        for (int k = 0; k < 3; k++) belief[k] *= (k - 1 == seen) ? 1.0 : 0.45;
        normalise();
    }

    public Move onFrame(int[] px, int w, int h, long nowMs) {
        vision.runnerOffset = p.runnerOffsetPct / 100.0;
        vision.pitchHint = pitch;
        TrackVision.Result r = vision.analyse(px, w, h);
        last = r;
        if (!r.ok || r.tracks.size() != 3) {
            noTrackFrames++;
            if (noTrackFrames > 3) { vision.hintX = Double.NaN; vision.hintY = Double.NaN; }
            if (noTrackFrames > 10) pitch = Double.NaN;
            for (int k = 0; k < 3; k++) { prevD[k] = Double.NaN; approach[k] = 0; laneConfirmed[k] = false; }
            status = noTrackFrames > 10 ? "Looking for the tracks…" : status;
            return Move.NONE;
        }
        noTrackFrames = 0;
        vision.hintX = r.vpX; vision.hintY = r.vpY;
        pitch = Double.isNaN(pitch) ? vision.gridP : pitch + 0.3 * (vision.gridP - pitch);
        double feetY = h * vision.runnerFeet;
        boolean panning = nowMs < laneChangeUntil;
        // On train roofs the camera is higher (smaller lane pitch for the same horizon) and
        // the runner's offset no longer matches the ground pattern: don't read lanes then.
        double pitchRatio = vision.gridP / (h * vision.rowNear - r.vpY);
        boolean groundCamera = pitchRatio > p.groundPitchRatio;
        if (!panning && groundCamera && !r.tracks.get(1).blockedAtStart) observeLanes(r);
        int lane = lane();
        lastLane = lane;
        boolean leftExists = belief[0] < 1 - p.laneSure;
        boolean rightExists = belief[2] < 1 - p.laneSure;

        // ---- obstacles per lane (0 = left of us, 1 = ours, 2 = right), as time to impact
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
            double d0 = depth(t.obstacleY, feetY, r.vpY);
            double d1 = depth(t.obstacleY - t.obstacleSpan, feetY, r.vpY);
            laneTti[k] = Math.max(0, d0) / speed * 1000.0;
            laneTall[k] = !t.railsResume || (d1 - d0) > 0.9;
            // A real obstacle comes towards us at game speed; things fixed on screen
            // (the runner, a train roof we stand on, sparkles) do not.
            if (!Double.isNaN(prevD[k]) && dt > 0) {
                double v = (prevD[k] - d0) / dt;
                if (v > 0.35 * speed && v < 3 * speed + 2) {
                    approach[k]++;
                    if (k == 1 && approach[k] >= 3 && !t.blockedAtStart && d0 > 0.3 && d0 < 2.5
                            && v > 0.6 * speed && v < 1.6 * speed) { vSum += v; vN++; }
                } else if (prevD[k] - d0 < -0.5) approach[k] = 0;   // jumped further away: new obstacle
                else approach[k] = Math.max(0, approach[k] - 1);
            }
            prevD[k] = d0;
            laneConfirmed[k] = approach[k] >= 2;
        }
        if (vN > 0) speed += 0.05 * (vSum / vN - speed);
        speed = Math.max(p.startSpeed * 0.8, Math.min(p.startSpeed * 2.5, speed));
        TrackVision.Track ownT = r.tracks.get(1);

        // which neighbours could we move into right now?
        laneOk[1] = true;
        laneOk[0] = leftExists && !r.tracks.get(0).sideBlocked && !r.tracks.get(0).blockedAtStart;
        laneOk[2] = rightExists && !r.tracks.get(2).sideBlocked && !r.tracks.get(2).blockedAtStart;

        // ---- our own rails hidden right in front of us: standing on a train roof
        // (or already too late). Either way, don't swipe.
        boolean onRoof = ownT.blockedAtStart;

        for (int k = 0; k < 3; k++) laneReward[k] += 0.5 * (r.reward[k] - laneReward[k]);

        double threat = laneTti[1];
        double react = p.reactMs + p.latencyMs;
        Move m = Move.NONE;
        String why;
        if (panning) {
            why = "changing lane";
        } else if (onRoof) {
            why = "on a train roof";
        } else if (threat < react && laneConfirmed[1]) {
            // A barrier: jump it (or roll). A train: move to a lane that is clear well ahead.
            int bestDir = 0; double bestScore = -1;
            double need = Math.max(p.targetClearMs, threat + p.safeExtraMs);
            for (int k = 0; k <= 2; k += 2) {
                if (!laneOk[k]) continue;
                if (laneTti[k] < need) continue;
                double score = Math.min(laneTti[k], 5000) + (p.seekCoins ? laneReward[k] * 2 : 0);
                if (score > bestScore) { bestScore = score; bestDir = k - 1; }
            }
            boolean train = laneTall[1];
            if (!train && nowMs >= nextAirOk) {
                m = p.jumpLowObstacles ? Move.JUMP : Move.ROLL;
                why = "barrier: " + (m == Move.JUMP ? "jump" : "roll");
            } else if (train && bestDir != 0 && nowMs >= nextLaneOk) {
                m = bestDir < 0 ? Move.LEFT : Move.RIGHT;
                why = "train: dodge " + (bestDir < 0 ? "left" : "right");
            } else if (train && bestDir == 0 && threat < react * 0.6 && nowMs >= nextAirOk) {
                // nowhere to go: jump as late as possible (it may be a ramp, or we may land on it)
                m = Move.JUMP; why = "boxed in, jump";
            } else why = train ? "train ahead, waiting for a gap" : "waiting";
        } else if (nowMs >= nextLaneOk && nowMs >= nextCoinOk && threat > react + p.safeExtraMs) {
            // free to roam: go for coins / power-ups, else drift back to the middle
            int dir = 0;
            String reason = "";
            if (p.seekCoins) {
                double bestV = Math.max(p.coinMin, laneReward[1] * p.coinRatio);
                for (int k = 0; k <= 2; k += 2) {
                    // only into a lane at least as clear as ours (and clear for targetClearMs)
                    if (!laneOk[k] || laneTti[k] < Math.max(p.targetClearMs, Math.min(threat, 2000))) continue;
                    if (laneReward[k] > bestV) { bestV = laneReward[k]; dir = k - 1; reason = "coins " + (k == 0 ? "left" : "right"); }
                }
            }
            if (dir == 0 && p.preferMiddle && lane != 0 && laneReward[1] < p.coinMin && belief[lane + 1] > 0.85) {
                int k = lane < 0 ? 2 : 0;           // towards the middle
                if (laneOk[k] && laneTti[k] >= Math.max(p.targetClearMs, Math.min(threat, 2000))) { dir = k - 1; reason = "back to middle"; }
            }
            if (dir != 0) {
                m = dir < 0 ? Move.LEFT : Move.RIGHT;
                why = reason;
                nextCoinOk = nowMs + 450;
            } else why = laneReward[1] >= p.coinMin ? "collecting" : "clear";
        } else why = "clear";

        if (m == Move.LEFT || m == Move.RIGHT) {
            int dir = m == Move.LEFT ? -1 : 1;
            nextLaneOk = nowMs + p.laneCooldownMs;
            laneChangeUntil = nowMs + 280;
            shiftBelief(dir);
            // lanes are relative to us: after the move, forget per-lane tracking
            for (int k = 0; k < 3; k++) { prevD[k] = Double.NaN; approach[k] = 0; laneReward[k] = 0; }
        } else if (m == Move.JUMP || m == Move.ROLL) {
            nextAirOk = nowMs + p.airCooldownMs;
        }
        int ln = lane();
        status = String.format(java.util.Locale.US, "%s · lane %s%s · %s", why,
                ln < 0 ? "L" : ln > 0 ? "R" : "M", belief[ln + 1] < 0.7 ? "?" : "",
                Double.isInfinite(threat) ? "clear ahead" : String.format(java.util.Locale.US, "%.1fs ahead", threat / 1000));
        return m;
    }

    public double getSpeed() { return speed; }

    /** One compact line describing this frame, for the on-phone log. */
    public String logLine(Move m) {
        TrackVision.Result r = last;
        StringBuilder sb = new StringBuilder(200);
        if (r == null || !r.ok) {
            sb.append("-- ").append(r == null ? "" : r.why);
        } else {
            sb.append(String.format(java.util.Locale.US, "vp=%.0f,%.0f P=%.0f c=%.0f v=%.2f bel=%.2f/%.2f/%.2f rails=%.0f/%.0f/%.0f/%.0f/%.0f",
                    r.vpX, r.vpY, vision.gridP, vision.gridC, speed, belief[0], belief[1], belief[2],
                    r.slotScore[0], r.slotScore[1], r.slotScore[2], r.slotScore[3], r.slotScore[4]));
            sb.append(String.format(java.util.Locale.US, " own=%.2f", r.ownOffset));
            for (int k = 0; k < r.tracks.size() && k < 3; k++) {
                TrackVision.Track t = r.tracks.get(k);
                sb.append(String.format(java.util.Locale.US, " |%s y%d s%d%s%s%s%s tti=%s c=%.0f",
                        k == 0 ? "L" : k == 1 ? "O" : "R", t.obstacleY, t.obstacleSpan,
                        t.blockedAtStart ? " AT" : "", k != 1 && t.sideBlocked ? " SIDE" : "",
                        laneConfirmed[k] ? " C" : "", laneTall[k] ? " T" : "",
                        Double.isInfinite(laneTti[k]) ? "-" : String.valueOf((int) laneTti[k]), laneReward[k]));
            }
        }
        if (m != Move.NONE) sb.append(" => ").append(m);
        sb.append(" | ").append(status);
        return sb.toString();
    }
}
