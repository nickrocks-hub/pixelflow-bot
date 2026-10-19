package com.aigame.subwaysurfers.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Real-time controller for Subway Surfers. Pure Java (no Android), so the
 * desktop tools replay recordings through exactly this code.
 *
 * Per frame:  perception (TrackVision) -> state update -> planning -> at most one gesture.
 *
 * State it keeps, all with monotonic millisecond timestamps:
 *  - game state: PLAYING / STOPPED (picture frozen: crash, pause, game over) / NO_TRACKS (menus)
 *  - lane: LEFT / MIDDLE / RIGHT, or TRANSITIONING to a target after our swipe. A lane change
 *    only counts once fresh frames show the camera settled on the new lane; if they show
 *    the old lane instead, the swipe was ignored or bounced off a train and we replan.
 *  - vertical: GROUNDED / JUMPING / ROLLING (from our delivered gestures and their
 *    durations), elevation ROOF (camera raised), FLYING (jetpack: camera very high)
 *  - obstacles: tracked across frames with an id, lane, type (TRAIN / BARRIER), depth,
 *    their own approach speed (oncoming trains are faster than the scenery) and time to
 *    collision. Only obstacles seen coming closer are acted on.
 *  - timing: frame age, perception time, gesture delivery time and lane-change time are
 *    measured and folded into every decision; decisions from stale frames are dropped.
 *
 * Planning compares STAY, LEFT, RIGHT, two-lane moves, JUMP and ROLL over the next
 * couple of seconds: a move must reach a lane free of trains for a while, through
 * lanes with no train alongside; barriers are jumped (or rolled) at a calibrated lead
 * time, once per obstacle; coins only count when the lane is safe; small moves and
 * the middle lane are preferred; a committed route is kept unless it becomes unsafe.
 *
 * Depth: a screen row y maps to D = (feetY - vpY) / (y - vpY) - 1, 0 at the runner's
 * feet, proportional to distance along the track.
 */
public final class SurferBrain {

    public enum Move { NONE, LEFT, RIGHT, JUMP, ROLL }

    public static final class Params {
        /** Start getting out of a train's lane when it is this close (ms, after all delays). */
        public int reactMs = 650;
        /** Delay estimate (capture + swipe) used until real measurements arrive. */
        public int latencyMs = 120;
        /** Steer towards lanes with coins and power-ups when it's safe. */
        public boolean seekCoins = true;
        /** Ignore coin value below this (a stray sparkle). */
        public double coinMin = 80;
        /** Extra margin (ms) a lane we pass through must stay free of trains. */
        public int safeExtraMs = 350;
        /** Minimum time between two lane swipes (the game needs distinct swipes). */
        public int laneCooldownMs = 230;
        /** Barriers: true = jump, false = roll. */
        public boolean jumpLowObstacles = true;
        /** Prefer the middle lane when everything is equal (more escape routes). */
        public boolean preferMiddle = true;
        /** Runner's horizontal position offset from screen centre, % of width. */
        public int runnerOffsetPct = 0;
        /** Starting guess for game speed, depth units per second (measured while playing). */
        public double startSpeed = 2.4;
        /** Lane pitch / (near row - horizon) below this = camera raised (train roof). */
        public double groundPitchRatio = 0.50;
        /** ... and below this = camera very high (jetpack). */
        public double flyingPitchRatio = 0.33;
        /** After arriving, a lane must stay free of trains this long (ms). */
        public int targetClearMs = 900;
        /** Jump / roll when the barrier is this far away (ms) at the moment the swipe lands. */
        public int jumpAtMs = 300;
        /** An obstacle whose blocked stretch is longer than this (depth units) is a train. */
        public double tallDepth = 0.9;
        /** Decisions from frames older than this (ms) are not acted on. */
        public int staleMs = 150;
        /** How long a jump / roll keeps the runner off its normal height (ms). */
        public int jumpMs = 650, rollMs = 550;
    }

    public enum Game { PLAYING, STOPPED, NO_TRACKS }
    public enum Vertical { GROUNDED, JUMPING, ROLLING }

    /** A tracked obstacle. */
    public static final class Obstacle {
        public int id, lane;            // lane: -1 left, 0 middle, 1 right
        public boolean train, oncoming, actedOn;
        public double d, v;             // depth now, approach speed (depth / s)
        public int hits;
        public long seen;               // capture time of the last sighting
        public int yTop, yBottom;       // screen rows of the blocked stretch (last sighting)
        public double fitErr = 1;       // scatter of the sightings around the fitted approach line
        final long[] ht = new long[8];  // recent sightings: time, depth
        final double[] hd = new double[8];
        int hn;
        public double tti(double worldSpeed) { return Math.max(0, d) / Math.max(v, worldSpeed * 0.8) * 1000.0; }
        /** Seen coming closer, consistently, over several frames. */
        public boolean confirmed(double worldSpeed) {
            return hits >= 3 && v > 0.35 * worldSpeed && fitErr < 0.12 + 0.08 * d;
        }

        /** Add a sighting and refit speed as the slope of depth over the last few sightings. */
        void add(long t, double depth) {
            int i = hn % ht.length; ht[i] = t; hd[i] = depth; hn++;
            int n = Math.min(hn, ht.length);
            double mt = 0, md = 0;
            for (int k = 0; k < n; k++) { mt += ht[k]; md += hd[k]; }
            mt /= n; md /= n;
            double sxx = 0, sxy = 0;
            for (int k = 0; k < n; k++) { double dx = (ht[k] - mt) / 1000.0; sxx += dx * dx; sxy += dx * (hd[k] - md); }
            if (n >= 2 && sxx > 1e-6) {
                double slope = sxy / sxx;                      // depth per second (negative = approaching)
                v = -slope;
                double e = 0;
                for (int k = 0; k < n; k++) { double r = hd[k] - (md + slope * (ht[k] - mt) / 1000.0); e += r * r; }
                fitErr = Math.sqrt(e / n);
            }
            d = depth; seen = t; hits++;
        }
    }

    /** Result of one gesture, reported back by the gesture owner (thread-safe). */
    public static final class GestureAck {
        public final Move move; public final long requested, dispatched, done; public final boolean ok;
        public final String note;
        public GestureAck(Move m, long requested, long dispatched, long done, boolean ok, String note) {
            this.move = m; this.requested = requested; this.dispatched = dispatched; this.done = done; this.ok = ok; this.note = note;
        }
    }

    // ---- decision history, so a crash can be traced back to what the bot did
    public enum Event { DODGE, COIN_MOVE, MIDDLE_MOVE, JUMP_BARRIER, ROLL_BARRIER, JUMP_BOXED, WAIT_TRAIN, LANE_FAILED, GESTURE_FAILED }

    /** One decision: when, what, and how far away (ms) the obstacle in our lane was. */
    public static final class Record {
        public final long t; public final Event e; public final double ownTti;
        Record(long t, Event e, double ownTti) { this.t = t; this.e = e; this.ownTti = ownTti; }
    }

    public final Params p;
    public final TrackVision vision = new TrackVision();

    // ---- state
    public Game game = Game.NO_TRACKS;
    public int lane = 0;                     // confirmed lane
    public int targetLane = 0;               // where pending / unconfirmed swipes are taking us
    public boolean transitioning;
    private int transFrom;
    private long transDone = -1;             // when the lane swipe finished (0 = not yet delivered)
    private int sameObs, lastObs = 99;
    public Vertical vertical = Vertical.GROUNDED;
    private long verticalUntil;
    public boolean onRoof, flying;
    public double worldSpeed;
    private final List<Obstacle> obstacles = new ArrayList<>();
    private int nextId = 1;
    private final boolean[] blockedBeside = new boolean[3];   // absolute lanes: train alongside right now
    private final boolean[] laneSeen = new boolean[3];
    public final double[] laneReward = new double[3];

    // ---- timing (smoothed, ms)
    public double gestureMs = Double.NaN, laneChangeMs = 250, frameAgeMs, perceptionMs;
    private Move pendingMove = Move.NONE;
    private long pendingSince;
    private long lastLaneSwipe = -100000;
    private final ConcurrentLinkedQueue<GestureAck> acks = new ConcurrentLinkedQueue<>();
    /** Desktop replays: treat every gesture as delivered after this many ms (no phone). */
    public int simulateAckMs = -1;
    private long simAckAt;

    // ---- game-state detection
    private long playingSince = -1, stillSince = -1, lastTracked = -1;
    /** Set when a crash is detected: when the picture stopped moving after play. Consumed by the app. */
    public volatile long crashAt = -1;
    public double playedSec;

    // ---- committed route
    private int commitLane = 99;
    private long commitUntil;

    // ---- history / outputs
    private final ArrayDeque<Record> history = new ArrayDeque<>();
    private final ArrayDeque<String> notes = new ArrayDeque<>();
    public String status = "";
    public TrackVision.Result last;
    public String lastReason = "";
    // per-lane views (left / own / right relative to the runner) for the replay tools
    public final double[] laneTti = new double[3];
    public final boolean[] laneTall = new boolean[3];
    public int lastLane;

    public SurferBrain(Params p) { this.p = p; reset(); }

    public void reset() {
        worldSpeed = p.startSpeed;
        lane = targetLane = 0;                     // every run starts in the middle lane
        transitioning = false;
        vertical = Vertical.GROUNDED;
        obstacles.clear();
        pendingMove = Move.NONE;
        commitLane = 99;
        history.clear();
        playingSince = stillSince = lastTracked = -1;
        crashAt = -1;
        playedSec = 0;
        vision.hintX = vision.hintY = Double.NaN;
        vision.pitchHint = Double.NaN;
        vision.freeFit = false;
        for (int k = 0; k < 3; k++) { laneReward[k] = 0; laneSeen[k] = false; blockedBeside[k] = false; }
    }

    /** Thread-safe: the gesture owner reports what happened to a swipe. */
    public void onGesture(GestureAck a) { acks.add(a); }

    private void record(long t, Event e, double tti) {
        history.addLast(new Record(t, e, tti));
        while (history.size() > 80) history.removeFirst();
    }

    private void note(String s) {
        notes.addLast(s);
        while (notes.size() > 20) notes.removeFirst();
    }

    /** Notes since the last call (gesture results, lane confirmations, failures) for the log. */
    public String drainNotes() {
        if (notes.isEmpty()) return "";
        String s = String.join(" ; ", notes);
        notes.clear();
        return s;
    }

    /** Decisions from the last few seconds (oldest first). */
    public List<Record> history() { return new ArrayList<>(history); }

    public double getSpeed() { return worldSpeed; }

    public int lane() { return lane; }

    /** Frame captured "now". */
    public Move onFrame(int[] px, int w, int h, long nowMs) { return onFrame(px, w, h, nowMs, nowMs); }

    /**
     * @param captureMs when the frame was captured (monotonic ms, same clock as nowMs)
     * @param nowMs     when analysis started
     */
    public Move onFrame(int[] px, int w, int h, long captureMs, long nowMs) {
        long t0 = System.nanoTime();
        frameAgeMs += 0.2 * (Math.max(0, nowMs - captureMs) - frameAgeMs);

        vision.runnerOffset = p.runnerOffsetPct / 100.0;
        vision.freeFit = transitioning && (transDone <= 0 || captureMs < transDone + 350);
        vision.frameTimeMs = captureMs;
        TrackVision.Result r = vision.analyse(px, w, h);
        return step(r, h, captureMs, nowMs, t0);
    }

    /** True when the next frame should be fitted freely (camera panning after our lane swipe). */
    public boolean wantsFreeFit(long captureMs) {
        return transitioning && (transDone <= 0 || captureMs < transDone + 350);
    }

    /**
     * Everything after perception. Uses r plus vision.motion / bestCam / gridP / freeFit.
     * Public so tests can feed hand-made scenes.
     */
    public Move step(TrackVision.Result r, int h, long captureMs, long nowMs, long t0) {
        processAcks(nowMs);
        last = r;
        boolean ok = r.ok && r.all.size() == 5;
        double feetY = h * vision.runnerFeet;

        // ---------------- game state
        double motion = vision.motion;
        if (ok) lastTracked = captureMs;
        boolean moving = Double.isNaN(motion) || motion > 4.0;      // NaN: no reference yet
        if (moving) stillSince = -1; else if (stillSince < 0) stillSince = captureMs;
        Game prev = game;
        if (stillSince >= 0 && captureMs - stillSince > 250) game = Game.STOPPED;
        else if (lastTracked < 0 || captureMs - lastTracked > 400) game = Game.NO_TRACKS;
        else game = Game.PLAYING;
        if (game == Game.PLAYING && playingSince < 0) playingSince = captureMs;
        if (prev == Game.PLAYING && game == Game.STOPPED && playingSince >= 0 && stillSince - playingSince > 2000) {
            crashAt = stillSince;                                   // the picture stopped: crash / game over / pause
            playedSec = (stillSince - playingSince) / 1000.0;
            note("STOPPED after " + Math.round(playedSec) + "s of play");
        }
        if (game != Game.PLAYING) playingSince = -1;
        if (game != Game.PLAYING) {
            lastReason = game == Game.STOPPED ? "stopped (crash / pause / menu): no input" : "no tracks (menu?): no input";
            status = lastReason;
            perceptionMs = (System.nanoTime() - t0) / 1e6;
            return Move.NONE;
        }
        if (!ok) {
            lastReason = "lost the tracks this frame";
            status = lastReason;
            ageObstacles(captureMs);
            return Move.NONE;
        }
        vision.hintX = r.vpX; vision.hintY = r.vpY;
        vision.pitchHint = Double.isNaN(vision.pitchHint) ? vision.gridP : vision.pitchHint + 0.3 * (vision.gridP - vision.pitchHint);

        // ---------------- vertical
        if (vertical != Vertical.GROUNDED && captureMs > verticalUntil) vertical = Vertical.GROUNDED;

        // ---------------- elevation / flying
        double pitchRatio = vision.gridP / (h * vision.rowNear - r.vpY);
        onRoof = pitchRatio < p.groundPitchRatio;
        flying = pitchRatio < p.flyingPitchRatio;

        // ---------------- lane from the camera position (+ verification of our swipes)
        // The camera's three resting positions are measured at ground level; on roofs and
        // mid-jump the camera is raised and readings aren't reliable, so keep the lane we know.
        boolean camReliable = !onRoof && vertical == Vertical.GROUNDED && captureMs > verticalUntil + 300;
        int obs = vision.bestCam > 50 || !camReliable ? 99 : (int) Math.round(vision.bestCam / TrackVision.CAM[2]);
        if (obs == lastObs) sameObs++; else { sameObs = 1; lastObs = obs; }
        updateLane(obs, captureMs);

        // ---------------- obstacles (only when lanes are known: not mid-pan)
        if (!vision.freeFit) {
            updateObstacles(r, feetY, captureMs);
            for (int k = 0; k < 3; k++) laneSeen[k] = false;
            for (int k = 0; k < 5; k++) {
                int a = lane + (k - 2);
                if (a < -1 || a > 1) continue;
                TrackVision.Track t = r.all.get(k);
                blockedBeside[a + 1] = k != 2 && (t.sideBlocked || t.blockedAtStart);
                laneSeen[a + 1] = true;
                if (k >= 1 && k <= 3) laneReward[a + 1] += 0.5 * (r.reward[k - 1] - laneReward[a + 1]);
            }
        } else ageObstacles(captureMs);
        fillLegacy();

        // ---------------- timing
        double gMs = Double.isNaN(gestureMs) ? p.latencyMs : gestureMs;
        perceptionMs = (System.nanoTime() - t0) / 1e6;
        double lead = (nowMs - captureMs) + perceptionMs + gMs + 17;   // until a swipe made now takes effect
        boolean stale = nowMs - captureMs > p.staleMs;

        Move m = Move.NONE;
        String why;
        if (!moving) why = "picture not moving (crash / pause?): no input";
        else if (flying) why = "flying (jetpack): no input";
        else if (stale) { why = "stale frame (" + (nowMs - captureMs) + " ms old): not acting"; note("STALE frame " + (nowMs - captureMs) + "ms"); }
        else if (pendingMove != Move.NONE) why = "waiting for " + pendingMove + " to be delivered";
        else {
            String[] reason = new String[1];
            m = plan(captureMs, lead, reason);
            why = reason[0];
        }
        if (m != Move.NONE) issue(m, nowMs);
        lastReason = why;
        lastLane = lane;
        status = String.format(Locale.US, "%s · lane %s%s%s", why, laneName(lane),
                transitioning ? "→" + laneName(targetLane) : "", onRoof ? " · roof" : "");
        return m;
    }

    private static String laneName(int l) { return l < 0 ? "L" : l > 0 ? "R" : "M"; }

    // ================================================================== lanes

    private void updateLane(int obs, long t) {
        if (obs == 99) return;
        if (!transitioning) {
            // outside our own moves, a different reading must hold for a few frames
            if (obs != lane && sameObs >= 6) {
                note("lane re-read " + laneName(lane) + "->" + laneName(obs));
                lane = targetLane = obs;
                obstacles.clear();
            }
            return;
        }
        if (transDone <= 0) return;                                   // swipe not delivered yet
        if (obs == targetLane && sameObs >= 2) {
            double took = t - transDone;
            if (took > 0 && took < 1000) laneChangeMs += 0.3 * (took - laneChangeMs);
            note("lane change confirmed " + laneName(transFrom) + "->" + laneName(targetLane) + " in " + Math.round(took) + "ms");
            lane = targetLane; transitioning = false; obstacles.clear();
        } else if (obs == transFrom && sameObs >= 3 && t > transDone + 450) {
            note("lane change FAILED (still " + laneName(transFrom) + "): swipe ignored or bounced off a train");
            record(t, Event.LANE_FAILED, Double.NaN);
            lane = targetLane = transFrom; transitioning = false; commitLane = 99;
        } else if (t > transDone + 900) {
            note("lane change unverified, assuming " + laneName(targetLane));
            lane = targetLane; transitioning = false; obstacles.clear();
        }
    }

    // ============================================================== obstacles

    private double depth(double y, double feetY, double vpY) {
        double dy = y - vpY;
        if (dy <= 1) return 99;
        return (feetY - vpY) / dy - 1;
    }

    private void ageObstacles(long t) {
        obstacles.removeIf(o -> t - o.seen > 400);
    }

    private void updateObstacles(TrackVision.Result r, double feetY, long t) {
        double vSum = 0; int vN = 0;
        for (int k = 0; k < 5; k++) {
            int a = lane + (k - 2);
            if (a < -1 || a > 1) continue;
            TrackVision.Track tr = r.all.get(k);
            if (tr.obstacleY < 0 || tr.blockedAtStart) continue;
            double d0 = depth(tr.obstacleY, feetY, r.vpY);
            double d1 = depth(tr.obstacleY - tr.obstacleSpan, feetY, r.vpY);
            boolean train = !tr.railsResume || (d1 - d0) > p.tallDepth;
            // associate with the track in this lane whose predicted depth is closest
            Obstacle best = null; double bestErr = 1e9;
            for (Obstacle o : obstacles) {
                if (o.lane != a) continue;
                double dt = (t - o.seen) / 1000.0;
                double pred = o.d - Math.max(Math.min(o.v, 4 * worldSpeed), 0) * dt;
                double err = Math.abs(pred - d0);
                if (err < 0.3 + 0.2 * d0 && err < bestErr) { bestErr = err; best = o; }
            }
            if (best == null) {
                best = new Obstacle();
                best.id = nextId++; best.lane = a; best.train = train;
                best.add(t, d0);
                obstacles.add(best);
            } else if (t > best.seen) {
                best.add(t, d0);
                if (train) best.train = true;                       // once a train, always a train
            }
            best.yTop = tr.obstacleY - tr.obstacleSpan; best.yBottom = tr.obstacleY;
            if (best.confirmed(worldSpeed) && best.d > 0.3 && best.d < 2.5 && best.hn >= 5 && !best.oncoming) { vSum += best.v; vN++; }
        }
        ageObstacles(t);
        // world speed from the obstacles that move with the scenery; clearly faster = oncoming train
        if (vN > 0) {
            double mean = vSum / vN;
            double lo = Math.min(mean, worldSpeed * 1.25);
            worldSpeed += 0.05 * (lo - worldSpeed);
            worldSpeed = Math.max(p.startSpeed * 0.8, Math.min(p.startSpeed * 2.5, worldSpeed));
        }
        for (Obstacle o : obstacles) o.oncoming = o.train && o.confirmed(worldSpeed) && o.hn >= 5 && o.v > worldSpeed * 1.35;
    }

    /** Nearest confirmed train in an absolute lane, as time to collision at capture time (ms). */
    private double trainTti(int a) {
        double b = Double.POSITIVE_INFINITY;
        for (Obstacle o : obstacles) if (o.lane == a && o.train && o.confirmed(worldSpeed)) b = Math.min(b, o.tti(worldSpeed));
        return b;
    }

    private Obstacle nearestBarrier(int a) {
        Obstacle b = null;
        for (Obstacle o : obstacles)
            if (o.lane == a && !o.train && o.confirmed(worldSpeed) && (b == null || o.d < b.d)) b = o;
        return b;
    }

    private Obstacle nearestTrain(int a) {
        Obstacle b = null;
        for (Obstacle o : obstacles)
            if (o.lane == a && o.train && o.confirmed(worldSpeed) && (b == null || o.d < b.d)) b = o;
        return b;
    }

    // ================================================================ planning

    private Move plan(long t, double lead, String[] reason) {
        int c = targetLane;
        double ownTrain = trainTti(c) - lead;            // ms left once a swipe made now lands
        Obstacle ownBarrier = nearestBarrier(c);
        Obstacle ownT = nearestTrain(c);

        // 1) barrier in our lane: jump / roll at the calibrated lead, once per obstacle
        if (ownBarrier != null && !ownBarrier.actedOn && (ownT == null || ownBarrier.d < ownT.d)) {
            double left = ownBarrier.tti(worldSpeed) - lead;
            if (left <= p.jumpAtMs && vertical == Vertical.GROUNDED && !transitioning) {
                ownBarrier.actedOn = true;
                Move mv = p.jumpLowObstacles ? Move.JUMP : Move.ROLL;
                record(t, mv == Move.JUMP ? Event.JUMP_BARRIER : Event.ROLL_BARRIER, left + lead);
                reason[0] = String.format(Locale.US, "barrier #%d: %s (%.0f ms away when it lands)", ownBarrier.id,
                        mv == Move.JUMP ? "jump" : "roll", left);
                return mv;
            }
        }

        // 2) evaluate routes over the next ~2 s
        double stayCost = laneCost(c);
        boolean stayUnsafe = ownTrain < p.reactMs;
        int bestLane = c; double bestCost = stayUnsafe ? 1e6 + stayCost : stayCost;
        String blockedWhy = "";
        if (!transitioning && t - lastLaneSwipe >= p.laneCooldownMs) {
            for (int a = -1; a <= 1; a++) {
                if (a == c) continue;
                int steps = Math.abs(a - c);
                double arrive = lead + steps * laneChangeMs;
                String bad = routeProblem(c, a, arrive, lead);
                if (bad != null) { if (steps == 1) blockedWhy += (blockedWhy.isEmpty() ? "" : ", ") + laneName(a) + ": " + bad; continue; }
                double cost = laneCost(a) + 0.6 * steps;
                if (cost < bestCost) { bestCost = cost; bestLane = a; }
            }
            // commitment: keep heading for a committed lane while the route stays safe
            if (commitLane != 99 && commitLane != c && t < commitUntil && bestLane != commitLane) {
                int steps = Math.abs(commitLane - c);
                if (routeProblem(c, commitLane, lead + steps * laneChangeMs, lead) == null) bestLane = commitLane;
            }
            // hysteresis: leaving a safe lane needs a clear gain
            if (bestLane != c && !stayUnsafe && stayCost - bestCost < 0.5) bestLane = c;
        }

        if (bestLane != c) {
            int dir = bestLane < c ? -1 : 1;
            commitLane = bestLane; commitUntil = t + 1500;
            boolean coins = !stayUnsafe && laneReward[bestLane + 1] > p.coinMin;
            String because = stayUnsafe ? String.format(Locale.US, "train #%d in %.0f ms", ownT != null ? ownT.id : 0, ownTrain)
                    : coins ? "coins" : "clearer lane";
            record(t, stayUnsafe ? Event.DODGE : coins ? Event.COIN_MOVE : Event.MIDDLE_MOVE, ownTrain + lead);
            reason[0] = "→ " + laneName(bestLane) + (Math.abs(bestLane - c) == 2 ? " (2 lanes)" : "") + ": " + because;
            return dir < 0 ? Move.LEFT : Move.RIGHT;
        }
        if (commitLane == c || t >= commitUntil) commitLane = 99;

        // 3) stuck with a train coming: last-resort jump (a ramp or sneakers may save it), once per train
        if (stayUnsafe && ownT != null) {
            if (!ownT.actedOn && ownTrain <= p.jumpAtMs && vertical == Vertical.GROUNDED && !transitioning) {
                ownT.actedOn = true;
                record(t, Event.JUMP_BOXED, ownTrain + lead);
                reason[0] = String.format(Locale.US, "train #%d, no safe lane (%s): jump", ownT.id, blockedWhy.isEmpty() ? "-" : blockedWhy);
                return Move.JUMP;
            }
            record(t, Event.WAIT_TRAIN, ownTrain + lead);
            reason[0] = String.format(Locale.US, "train #%d in %.0f ms, no safe lane yet (%s)", ownT.id, ownTrain, blockedWhy.isEmpty() ? "-" : blockedWhy);
            return Move.NONE;
        }
        reason[0] = ownBarrier != null ? String.format(Locale.US, "barrier #%d ahead", ownBarrier.id)
                : Double.isInfinite(ownTrain) ? "clear" : String.format(Locale.US, "train %.1fs ahead", ownTrain / 1000);
        return Move.NONE;
    }

    /** Why we can't move from lane c to lane a (arriving after 'arrive' ms), or null if we can. */
    private String routeProblem(int c, int a, double arrive, double lead) {
        int dir = a < c ? -1 : 1;
        for (int x = c + dir; ; x += dir) {
            if (x < -1 || x > 1) return "no track";
            if (!laneSeen[x + 1]) return "not seen";
            if (blockedBeside[x + 1]) return "train alongside";
            double tt = trainTti(x) - lead;                         // after our swipe lands
            double passBy = Math.abs(x - c) * laneChangeMs;
            if (x != a && tt < passBy + p.safeExtraMs) return "train in the way";
            if (x == a) {
                if (tt < arrive - lead + p.targetClearMs) return String.format(Locale.US, "train in %.0f ms", tt);
                Obstacle b = nearestBarrier(x);
                if (b != null && b.tti(worldSpeed) - lead < arrive - lead + 150) return "barrier right there";
                return null;
            }
        }
    }

    /** Lower = better. Trains soon = bad, barriers = a little bad, coins / middle = a little good. */
    private double laneCost(int a) {
        double cost = 0;
        double tt = trainTti(a);
        if (tt < 2500) cost += (2500 - Math.max(0, tt)) / 2500 * 3;
        if (nearestBarrier(a) != null) cost += 0.3;
        if (p.seekCoins && laneReward[a + 1] > p.coinMin) cost -= Math.min(0.8, laneReward[a + 1] / 2500);
        if (p.preferMiddle && a == 0) cost -= 0.15;
        return cost;
    }

    // ================================================================ gestures

    private void issue(Move m, long now) {
        pendingMove = m; pendingSince = now;
        if (simulateAckMs >= 0) simAckAt = now + simulateAckMs;
        if (m == Move.LEFT || m == Move.RIGHT) {
            int dir = m == Move.LEFT ? -1 : 1;
            if (!transitioning) transFrom = lane;
            targetLane = Math.max(-1, Math.min(1, targetLane + dir));
            transitioning = true; transDone = 0;
            lastLaneSwipe = now;
        }
    }

    private void processAcks(long now) {
        if (simulateAckMs >= 0 && pendingMove != Move.NONE && now >= simAckAt)
            acks.add(new GestureAck(pendingMove, pendingSince, pendingSince, simAckAt, true, "simulated"));
        GestureAck a;
        while ((a = acks.poll()) != null) {
            if (a.move != pendingMove) { note("result for " + a.move + " (not pending) ignored"); continue; }
            pendingMove = Move.NONE;
            if (a.ok) {
                double took = a.done - a.requested;
                if (took >= 0 && took < 1000) gestureMs = Double.isNaN(gestureMs) ? took : gestureMs + 0.3 * (took - gestureMs);
                note(String.format(Locale.US, "%s delivered in %d ms%s", a.move, a.done - a.requested,
                        a.note == null || a.note.isEmpty() ? "" : " (" + a.note + ")"));
                if (a.move == Move.LEFT || a.move == Move.RIGHT) transDone = a.done;
                else if (a.move == Move.JUMP) { vertical = Vertical.JUMPING; verticalUntil = a.done + p.jumpMs; }
                else if (a.move == Move.ROLL) { vertical = Vertical.ROLLING; verticalUntil = a.done + p.rollMs; }
            } else {
                note(a.move + " NOT delivered: " + a.note);
                record(now, Event.GESTURE_FAILED, Double.NaN);
                if (a.move == Move.LEFT || a.move == Move.RIGHT) {
                    // the swipe never happened: undo the expected move and replan
                    targetLane = lane = transFrom; transitioning = false; commitLane = 99;
                }
                for (Obstacle o : obstacles) o.actedOn = false;
            }
        }
        // a gesture that never reports back: stop waiting (and say so)
        if (pendingMove != Move.NONE && now - pendingSince > 600) {
            note(pendingMove + " never confirmed by the gesture service");
            if (pendingMove == Move.LEFT || pendingMove == Move.RIGHT) transDone = pendingSince;
            pendingMove = Move.NONE;
        }
    }

    // ================================================================ outputs

    private void fillLegacy() {
        for (int k = 0; k < 3; k++) {
            int a = lane + (k - 1);
            laneTti[k] = a < -1 || a > 1 ? Double.POSITIVE_INFINITY : trainTti(a);
            laneTall[k] = laneTti[k] < Double.POSITIVE_INFINITY;
            Obstacle b = a < -1 || a > 1 ? null : nearestBarrier(a);
            if (b != null && b.tti(worldSpeed) < laneTti[k]) { laneTti[k] = b.tti(worldSpeed); laneTall[k] = false; }
        }
    }

    /** One line describing this frame for the log: timing, state, tracked obstacles, decision, gesture results. */
    public String logLine(Move m) {
        TrackVision.Result r = last;
        StringBuilder sb = new StringBuilder(260);
        sb.append(String.format(Locale.US, "age=%.0f perc=%.0f g=%s lc=%.0f | %s lane=%s%s %s%s%s v=%.2f",
                frameAgeMs, perceptionMs, Double.isNaN(gestureMs) ? "-" : String.valueOf(Math.round(gestureMs)), laneChangeMs,
                game, laneName(lane), transitioning ? "->" + laneName(targetLane) : "", vertical,
                onRoof ? " ROOF" : "", flying ? " FLY" : "", worldSpeed));
        if (r != null && r.ok) {
            sb.append(String.format(Locale.US, " | vp=%.0f,%.0f P=%.0f cam=%s mot=%.0f", r.vpX, r.vpY, vision.gridP,
                    vision.bestCam > 50 ? "free" : String.format(Locale.US, "%.2f", vision.bestCam), vision.motion));
            sb.append(" side=").append(blockedBeside[0] ? "X" : "-").append(blockedBeside[1] ? "X" : "-").append(blockedBeside[2] ? "X" : "-");
        } else if (r != null) sb.append(String.format(Locale.US, " | -- %s mot=%.0f", r.why, vision.motion));
        for (Obstacle o : obstacles) {
            sb.append(String.format(Locale.US, " | #%d %s %s d=%.2f v=%.2f e=%.2f tti=%.0f h=%d y=%d-%d%s%s", o.id, laneName(o.lane),
                    o.train ? (o.oncoming ? "TRAIN>" : "TRAIN") : "BAR", o.d, o.v, o.fitErr, o.tti(worldSpeed), o.hits, o.yTop, o.yBottom,
                    o.confirmed(worldSpeed) ? " C" : "", o.actedOn ? " done" : ""));
        }
        if (m != Move.NONE) sb.append(" => ").append(m);
        sb.append(" | ").append(lastReason);
        String n = drainNotes();
        if (!n.isEmpty()) sb.append(" ## ").append(n);
        return sb.toString();
    }
}
