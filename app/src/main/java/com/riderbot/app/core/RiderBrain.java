package com.riderbot.app.core;

import java.util.Locale;

/**
 * Turns frames into a single decision: hold the screen or not.
 *
 * Rider's only control is "hold": on the ground it accelerates, in the air it
 * rotates the car backwards (counter-clockwise on screen). A landing is safe
 * when the wheels meet the track roughly upright. The brain therefore:
 *  - holds whenever the car is on the track;
 *  - in the air, starts/continues a backflip only if the estimated time until
 *    landing leaves room for a full rotation plus a safety margin, and lets go
 *    just before the car is upright again (lead = rotation speed x latency).
 *
 * Pure Java, no Android imports, so it can be tested on recorded frames.
 */
public final class RiderBrain {

    /** Tunables (the settings screen writes these). */
    public static final class Params {
        /** Screen-to-touch latency to compensate for, ms. */
        public double latencyMs = 90;
        /** Extra time the bot wants left over after a flip, ms. */
        public double flipMarginMs = 160;
        /** How far from upright (deg) still counts as a safe landing angle. */
        public double uprightTolDeg = 28;
        /** Do backflips for points (off = only keep the car upright). */
        public boolean doFlips = true;
        /** Max backflips per jump, 0 = unlimited. */
        public int maxFlipsPerJump = 0;
        /** Starting guess for rotation speed while holding, deg/s. */
        public double omegaDegPerSec = 450;
        /** Starting guess for gravity, in car-lengths/s^2. */
        public double gravityLengths = 12;
        /** Release after the car has been missing this long (game over / menus), ms. */
        public double lostReleaseMs = 700;
    }

    public final Params params;
    private final RiderVision vision = new RiderVision();

    // --- tracking state ---
    private boolean haveCar = false;
    private double lastX = Double.NaN, lastY = Double.NaN;
    private double lastLength = 30;
    private double theta = 0;          // unwrapped facing angle, deg CCW (0 = driving right)
    private long lastSeenMs = -1;
    private int flipSuspect = 0;

    // --- physics state ---
    private boolean grounded = true;
    private int airFrames = 0, groundFrames = 0;
    private double lastHeight = Double.NaN;
    private long lastHeightMs = -1;
    private double riseRate = 0;       // px/s, + = moving away from the track
    private double omega;              // deg/s measured
    private double lastThetaForOmega = Double.NaN;
    private long lastOmegaMs = -1;
    private long holdSinceMs = -1;

    // --- control state ---
    private boolean hold = false;
    private int flipsThisJump = 0;
    private double takeoffTheta = 0;

    // --- debug ---
    public String status = "";
    public double lastT = Double.NaN, lastDevOut = Double.NaN, lastHeightOut = Double.NaN;
    public boolean lastGrounded = true;
    public int lastUnder;
    public RiderVision.Car lastCar;

    public RiderBrain(Params p) {
        this.params = p;
        this.omega = p.omegaDegPerSec;
    }

    public boolean isHolding() { return hold; }

    public void reset() {
        haveCar = false; lastX = lastY = Double.NaN; lastSeenMs = -1;
        grounded = true; airFrames = groundFrames = 0; lastHeight = Double.NaN;
        riseRate = 0; omega = params.omegaDegPerSec; lastThetaForOmega = Double.NaN;
        hold = false; flipsThisJump = 0; holdSinceMs = -1; flipSuspect = 0;
    }

    /**
     * @param px     packed 0xRRGGBB pixels, row-major, stride == width
     * @param nowMs  frame timestamp
     * @return true to keep a finger on the screen
     */
    public boolean onFrame(int[] px, int width, int height, long nowMs) {
        boolean recent = haveCar && lastSeenMs >= 0 && nowMs - lastSeenMs < 400;
        RiderVision.Car car = recent
                ? vision.findCar(px, width, height, lastX, lastY, 2.5 * lastLength)
                : vision.findCar(px, width, height, Double.NaN, Double.NaN);
        lastCar = car;
        if (car == null) {
            if (lastSeenMs >= 0 && nowMs - lastSeenMs > params.lostReleaseMs) {
                haveCar = false;
                setHold(false, nowMs);
                status = "no car (menu / game over?)";
            } else {
                status = "car lost briefly";
            }
            return hold;
        }

        // ---- orientation: unwrap the 180-deg ambiguous axis by continuity ----
        if (!haveCar || nowMs - lastSeenMs > 400) {
            theta = car.axisDeg; // assume upright when (re)acquired
            grounded = true; airFrames = 0; flipsThisJump = 0;
        } else {
            double d = wrap(car.axisDeg - theta, 180);
            theta += d;
        }
        haveCar = true; lastSeenMs = nowMs; lastX = car.cx; lastY = car.cy; lastLength = car.length;

        // ---- contact with the track ----
        double L = car.length;
        int need = Math.max(3, (int) Math.round(L * 0.25));
        int under = vision.wheelSideTrack(px, width, height, car, theta);
        lastUnder = under;
        boolean contact = under >= need;
        if (!contact) {
            // Wrong half-turn? If the roof side touches track that is right below us, flip estimate.
            int roof = vision.wheelSideTrack(px, width, height, car, theta + 180);
            double below = vision.trackBelow(px, width, height, car.cx, car.cy + 0.33 * L);
            if (roof >= need && below >= 0 && below - car.cy < 0.75 * L) {
                if (++flipSuspect >= 4) { theta += 180; flipSuspect = 0; contact = true; }
            } else flipSuspect = 0;
        } else flipSuspect = 0;

        if (contact) { groundFrames++; airFrames = 0; } else { airFrames++; groundFrames = 0; }
        boolean wasGrounded = grounded;
        if (grounded && airFrames >= 2) grounded = false;
        if (!grounded && groundFrames >= 2) grounded = true;
        if (grounded && !wasGrounded) flipsThisJump = 0;
        if (grounded) takeoffTheta = theta;
        lastGrounded = grounded;

        // ---- height above the track straight below, and its rate ----
        // Start the scan below the car's own (rotated) outline so its tail/wheels are not taken for track.
        double ar = Math.toRadians(car.axisDeg);
        double extent = Math.max(0.37 * L, 0.5 * L * Math.abs(Math.sin(ar)) + 0.3 * L * Math.abs(Math.cos(ar)));
        double belowY = vision.trackBelow(px, width, height, car.cx, car.cy + extent);
        double height_ = belowY < 0 ? Double.NaN : Math.max(0, belowY - car.cy - extent);
        if (!Double.isNaN(height_) && !Double.isNaN(lastHeight) && lastHeightMs >= 0) {
            double dt = (nowMs - lastHeightMs) / 1000.0;
            double jump = Math.abs(height_ - lastHeight);
            if (dt > 0 && dt < 0.3 && jump < 2.5 * L) {
                double r = (height_ - lastHeight) / dt;
                riseRate = 0.6 * riseRate + 0.4 * r;
            }
        }
        if (!Double.isNaN(height_)) { lastHeight = height_; lastHeightMs = nowMs; }
        if (grounded) riseRate = 0;
        lastHeightOut = height_;

        // ---- measured rotation speed while holding in the air ----
        if (!grounded && hold && holdSinceMs >= 0 && nowMs - holdSinceMs > params.latencyMs + 40
                && !Double.isNaN(lastThetaForOmega)) {
            double dt = (nowMs - lastOmegaMs) / 1000.0;
            if (dt > 0.005 && dt < 0.2) {
                double w = (theta - lastThetaForOmega) / dt;
                if (w > 150 && w < 1500) omega = Math.max(250, Math.min(900, 0.8 * omega + 0.2 * w));
            }
        }
        lastThetaForOmega = theta; lastOmegaMs = nowMs;

        // ---- decide ----
        if (grounded) {
            setHold(true, nowMs);
            status = String.format(Locale.US, "GROUND  ang %4.0f", norm(theta));
            lastT = Double.NaN;
            return hold;
        }

        double slope = vision.slopeBelow(px, width, height, car);
        double target = (Double.isNaN(slope) || Math.abs(slope) > 60) ? 0 : slope;
        double dev = wrap(theta - target, 360);          // + = nose up / past upright
        double g = params.gravityLengths * L;           // px/s^2
        double hgt = Double.isNaN(height_) ? 0.35 * height : height_; // no track in view: assume a long drop
        double T = (riseRate + Math.sqrt(riseRate * riseRate + 2 * g * hgt)) / g; // s until landing
        double lat = params.latencyMs / 1000.0;
        double margin = params.flipMarginMs / 1000.0;
        double tol = params.uprightTolDeg;
        double lead = Math.min(tol, omega * lat + 6);   // release this many degrees early
        int flipsDone = (int) Math.floor((theta - takeoffTheta + tol) / 360.0);
        if (flipsDone < 0) flipsDone = 0;
        flipsThisJump = flipsDone;
        boolean flipsLeft = params.doFlips
                && (params.maxFlipsPerJump <= 0 || flipsDone < params.maxFlipsPerJump);
        // time to rotate CCW from here to upright, and to upright plus one more full turn
        double toUpright = (dev < 0 ? -dev : 360 - dev) / omega;
        double toUprightPlusTurn = (dev < 0 ? 360 - dev : 720 - dev) / omega;
        double spare = T - lat - margin;

        boolean h;
        if (dev < -lead) {
            // nose down / mid-flip on the way back up: keep rotating toward upright
            h = true;
        } else if (dev <= tol) {
            // (nearly) upright: start or continue another backflip only if a whole turn fits
            if (dev < 0) h = flipsLeft && spare > toUprightPlusTurn;
            else h = flipsLeft && spare > toUpright;
        } else {
            // nose up past upright (steep takeoff or overshoot): carry on round only if it fits
            h = spare > toUpright && (flipsLeft || dev > 75);
        }
        // just before touchdown, stop rotating unless badly nose-down
        if (T < lat + 0.03 && dev > -params.uprightTolDeg) h = false;
        setHold(h, nowMs);
        lastT = T; lastDevOut = dev;
        status = String.format(Locale.US, "AIR  dev %4.0f  land %.2fs  w %3.0f  flips %d",
                dev, T, omega, flipsThisJump);
        return hold;
    }

    private void setHold(boolean h, long nowMs) {
        if (h && !hold) holdSinceMs = nowMs;
        if (!h) holdSinceMs = -1;
        hold = h;
    }

    /** Wraps an angle difference to [-period/2, period/2). */
    static double wrap(double a, double period) {
        double half = period / 2;
        double r = (a + half) % period;
        if (r < 0) r += period;
        return r - half;
    }

    static double norm(double a) {
        double r = a % 360;
        return r < 0 ? r + 360 : r;
    }

    public double getTheta() { return theta; }
}
