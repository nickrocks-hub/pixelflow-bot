package com.aigame.subwaysurfers.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gets the bot better after every crash. Pure Java, no Android.
 *
 * After a run ends in a crash it looks at what the bot did in the last ~1.5 s
 * (the brain's decision history) and blames the most likely cause:
 *
 *   LANE_CHANGE  crashed soon after changing lane     -> demand clearer lanes before moving
 *   JUMP_EARLY   crashed well after a barrier jump      -> jump later (we landed before it)
 *   JUMP_LATE    jumped when the barrier was very close -> jump earlier
 *   MISREAD      a timely barrier jump still crashed    -> treat more obstacles as trains
 *   BOXED_IN     jumped at a train with nowhere to go   -> look further ahead, accept tighter gaps
 *   HESITATED    waited for a gap that never came       -> accept tighter gaps, react earlier
 *   UNSEEN       did nothing at all                     -> react earlier
 *   INPUT        a swipe was not delivered / not taken  -> leave more time between swipes
 *
 * Each cause nudges one or two settings by a small, bounded step. It also keeps
 * the settings that survived longest; if the last few runs are all much worse
 * than that best, it goes back to the best settings (so bad nudges get undone).
 */
public final class Learner {

    public enum Cause { LANE_CHANGE, JUMP_EARLY, JUMP_LATE, MISREAD, BOXED_IN, HESITATED, UNSEEN, INPUT }

    /** Failure class of a cause: perception, planning, timing or input. */
    public static String category(Cause c) {
        switch (c) {
            case JUMP_EARLY: case JUMP_LATE: return "timing";
            case MISREAD: case UNSEEN: return "perception";
            case INPUT: return "input";
            default: return "planning";
        }
    }

    // learned settings
    public int reactMs, safeExtraMs, targetClearMs, jumpAtMs;
    public double tallDepth;
    // settings of the longest run so far
    private int bReact, bSafe, bClear, bJump;
    private double bTall;
    public double bestRunSec = 0;
    public int runs = 0;
    private final double[] recent = new double[3];
    private int recentN = 0;
    public final int[] causeCount = new int[Cause.values().length];
    public String lastLesson = "";

    /** Start from the given (user / default) settings. */
    public Learner(SurferBrain.Params base) {
        reactMs = base.reactMs; safeExtraMs = base.safeExtraMs; targetClearMs = base.targetClearMs;
        jumpAtMs = base.jumpAtMs; tallDepth = base.tallDepth;
        saveBest();
    }

    private void saveBest() {
        bReact = reactMs; bSafe = safeExtraMs; bClear = targetClearMs; bJump = jumpAtMs; bTall = tallDepth;
    }

    /** Put the learned settings into the brain's parameters. */
    public void applyTo(SurferBrain.Params p) {
        p.reactMs = reactMs; p.safeExtraMs = safeExtraMs; p.targetClearMs = targetClearMs;
        p.jumpAtMs = jumpAtMs; p.tallDepth = tallDepth;
    }

    /** Which cause best explains a crash at crashMs, given the decisions before it. */
    public static Cause blame(List<SurferBrain.Record> history, long crashMs) {
        SurferBrain.Record lastMove = null;
        boolean waited = false, inputFail = false;
        for (SurferBrain.Record r : history) {
            if (r.t > crashMs || r.t < crashMs - 1500) continue;
            if (r.e == SurferBrain.Event.WAIT_TRAIN) waited = true;
            else if (r.e == SurferBrain.Event.LANE_FAILED || r.e == SurferBrain.Event.GESTURE_FAILED) inputFail = true;
            else lastMove = r;
        }
        if (inputFail) return Cause.INPUT;
        if (lastMove != null) {
            long ago = crashMs - lastMove.t;
            switch (lastMove.e) {
                case DODGE: case COIN_MOVE: case MIDDLE_MOVE:
                    if (ago < 1100) return Cause.LANE_CHANGE;
                    break;
                case JUMP_BARRIER: case ROLL_BARRIER:
                    if (ago < 1200) {
                        if (ago > 700) return Cause.JUMP_EARLY;
                        if (lastMove.ownTti < 200) return Cause.JUMP_LATE;
                        return Cause.MISREAD;
                    }
                    break;
                case JUMP_BOXED:
                    if (ago < 1200) return Cause.BOXED_IN;
                    break;
                default:
            }
        }
        return waited ? Cause.HESITATED : Cause.UNSEEN;
    }

    /**
     * Learn from one crash. runSec = how long the run lasted. Returns a short
     * description of what changed, for the log and the floating panel.
     */
    public String learn(List<SurferBrain.Record> history, long crashMs, double runSec) {
        runs++;
        // the settings used for this run: remember them if this was the best run
        if (runSec > bestRunSec) { bestRunSec = runSec; saveBest(); }
        recent[recentN % 3] = runSec; recentN++;

        Cause c = blame(history, crashMs);
        causeCount[c.ordinal()]++;
        String what;
        switch (c) {
            case LANE_CHANGE:
                targetClearMs = clamp(targetClearMs + 100, 600, 1300);
                safeExtraMs = clamp(safeExtraMs + 50, 150, 600);
                what = "changed lane into danger → wants clearer lanes (" + targetClearMs + " ms)";
                break;
            case JUMP_EARLY:
                jumpAtMs = clamp(jumpAtMs - 40, 120, 700);
                what = "jumped too early → jumps later (" + jumpAtMs + " ms)";
                break;
            case JUMP_LATE:
                jumpAtMs = clamp(jumpAtMs + 40, 120, 700);
                what = "jumped too late → jumps earlier (" + jumpAtMs + " ms)";
                break;
            case MISREAD:
                tallDepth = Math.max(0.35, tallDepth - 0.1);
                what = "jumped something it couldn't → treats more obstacles as trains";
                break;
            case BOXED_IN:
                reactMs = clamp(reactMs + 60, 400, 1300);
                targetClearMs = clamp(targetClearMs - 50, 600, 2000);
                what = "got boxed in → looks further ahead (" + reactMs + " ms)";
                break;
            case HESITATED:
                targetClearMs = clamp(targetClearMs - 80, 600, 2000);
                reactMs = clamp(reactMs + 40, 400, 1300);
                what = "waited too long → takes tighter gaps (" + targetClearMs + " ms)";
                break;
            case INPUT:
                what = "a swipe didn't take effect (logged for checking)";
                break;
            default:
                reactMs = clamp(reactMs + 50, 400, 1300);
                what = "didn't react → reacts earlier (" + reactMs + " ms)";
        }
        // Undo drift: three runs in a row far below the best run -> back to the best settings.
        if (recentN >= 3 && bestRunSec > 10) {
            boolean allBad = true;
            for (double v : recent) if (v > bestRunSec * 0.5) allBad = false;
            if (allBad) {
                reactMs = bReact; safeExtraMs = bSafe; targetClearMs = bClear; jumpAtMs = bJump; tallDepth = bTall;
                recentN = 0;
                what += "; last runs were worse, back to the best settings";
            }
        }
        lastLesson = String.format(Locale.US, "Run %d (%.0fs, %s): %s", runs, runSec, category(c), what);
        return lastLesson;
    }

    public void resetTo(SurferBrain.Params base) {
        Learner fresh = new Learner(base);
        copyFrom(fresh);
    }

    private void copyFrom(Learner o) {
        reactMs = o.reactMs; safeExtraMs = o.safeExtraMs; targetClearMs = o.targetClearMs; jumpAtMs = o.jumpAtMs;
        tallDepth = o.tallDepth; bReact = o.bReact; bSafe = o.bSafe; bClear = o.bClear; bJump = o.bJump; bTall = o.bTall;
        bestRunSec = o.bestRunSec; runs = o.runs; recentN = o.recentN;
        System.arraycopy(o.recent, 0, recent, 0, 3);
        System.arraycopy(o.causeCount, 0, causeCount, 0, causeCount.length);
        lastLesson = o.lastLesson;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Human-readable summary for the app's settings screen. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "Runs learned from: %d · best run: %.0f s%n", runs, bestRunSec));
        sb.append(String.format(Locale.US, "Now: dodge trains %d ms ahead · jump %d ms ahead · lane must be clear %d ms%n",
                reactMs, jumpAtMs, targetClearMs));
        List<String> parts = new ArrayList<>();
        Cause[] cs = Cause.values();
        for (int i = 0; i < cs.length; i++) if (causeCount[i] > 0) parts.add(label(cs[i]) + " ×" + causeCount[i]);
        if (!parts.isEmpty()) sb.append("Crashes: ").append(String.join(", ", parts)).append('\n');
        if (!lastLesson.isEmpty()) sb.append("Last: ").append(lastLesson);
        return sb.toString().trim();
    }

    private static String label(Cause c) {
        switch (c) {
            case LANE_CHANGE: return "lane change";
            case JUMP_EARLY: return "jumped early";
            case JUMP_LATE: return "jumped late";
            case MISREAD: return "misread obstacle";
            case BOXED_IN: return "boxed in";
            case HESITATED: return "hesitated";
            case INPUT: return "swipe not taken";
            default: return "didn't react";
        }
    }

    // ---- save / load as one line of text
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "v3;%d;%d;%d;%d;%.3f;%d;%d;%d;%d;%.3f;%.2f;%d;%d;%.2f;%.2f;%.2f",
                reactMs, safeExtraMs, targetClearMs, jumpAtMs, tallDepth,
                bReact, bSafe, bClear, bJump, bTall, bestRunSec, runs, recentN, recent[0], recent[1], recent[2]));
        for (int c : causeCount) sb.append(';').append(c);
        sb.append(';').append(lastLesson.replace(';', ','));
        return sb.toString();
    }

    /** Load saved state, or start fresh from base if there is none / it's unreadable. */
    public static Learner load(String s, SurferBrain.Params base) {
        Learner l = new Learner(base);
        if (s == null || !s.startsWith("v3;")) return l;   // older versions learned from bugs since fixed: start fresh
        try {
            String[] f = s.split(";", -1);
            int i = 1;
            l.reactMs = Integer.parseInt(f[i++]); l.safeExtraMs = Integer.parseInt(f[i++]);
            l.targetClearMs = Integer.parseInt(f[i++]); l.jumpAtMs = Integer.parseInt(f[i++]);
            l.tallDepth = Double.parseDouble(f[i++]);
            l.bReact = Integer.parseInt(f[i++]); l.bSafe = Integer.parseInt(f[i++]);
            l.bClear = Integer.parseInt(f[i++]); l.bJump = Integer.parseInt(f[i++]);
            l.bTall = Double.parseDouble(f[i++]);
            l.bestRunSec = Double.parseDouble(f[i++]); l.runs = Integer.parseInt(f[i++]);
            l.recentN = Integer.parseInt(f[i++]);
            for (int k = 0; k < 3; k++) l.recent[k] = Double.parseDouble(f[i++]);
            for (int k = 0; k < l.causeCount.length; k++) l.causeCount[k] = Integer.parseInt(f[i++]);
            l.lastLesson = i < f.length ? f[i] : "";
            return l;
        } catch (RuntimeException e) {
            return new Learner(base);
        }
    }
}
