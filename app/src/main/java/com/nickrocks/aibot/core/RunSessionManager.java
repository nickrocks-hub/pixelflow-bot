package com.nickrocks.aibot.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One AI run from start to summary.
 *
 * IDLE → INITIALIZING (waiting for the game screen) → RUNNING ⇄ PAUSED → FINALIZING → COMPLETED,
 * or ERROR. Runs left RUNNING by a crash are marked INTERRUPTED by the repository on next start.
 * The run is finalized exactly once, whatever ends it.
 *
 * Not thread-safe: call everything from the capture thread (stop/pause requests are flags).
 */
public final class RunSessionManager implements RunContext {

    public enum State { IDLE, INITIALIZING, RUNNING, PAUSED, FINALIZING, COMPLETED, ERROR, INTERRUPTED }
    public enum Outcome { VICTORY("Victory"), DEATH("Death"), STOPPED("Stopped"), ERROR("Error");
        public final String label; Outcome(String l) { label = l; } }

    public interface Listener {
        void onState(State s, String note);
        void onFinished(Map<String, Object> meta);
    }

    private final RunHistoryRepository repo;
    private final AISettings settings;
    private final ScreenLayout layout;
    private final Learner learner;
    private final Listener listener;
    private final RunStats stats = new RunStats();
    private RunLogger logger;
    private AutonomousAgent agent;
    private GameStateObserver observer;
    private float[] learned;

    private volatile State state = State.IDLE;
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private volatile boolean stopRequested, pauseRequested;
    private String stopReason = "";
    private String pauseNote = "";

    public String runId = "";
    private long startWall, lastTickWall = -1, gameMs, lastMetaWall;
    private long hudLostSince = -1, gamePausedSince = -1, waitingSince = -1, lastWaitLog;
    private boolean userPaused, gamePaused;
    private String endReason = "";
    private Map<String, Object> finalMeta;

    public RunSessionManager(RunHistoryRepository repo, AISettings settings, ScreenLayout layout, Listener listener) {
        this.repo = repo;
        this.settings = settings;
        this.layout = layout;
        this.learner = repo.loadLearner();
        this.listener = listener;
    }

    // ------------------------------------------------------------------------- RunContext
    @Override public void log(int level, String type, String msg, Map<String, Object> data) {
        if (logger != null) logger.event(level, gameMs, type, msg, data);
    }
    @Override public RunStats stats() { return stats; }
    @Override public Learner learner() { return learner; }
    @Override public long gameTimeMs() { return gameMs; }

    public State state() { return state; }
    public AutonomousAgent agent() { return agent; }
    public GameStateObserver observer() { return observer; }
    public long gameMs() { return gameMs; }
    public boolean isLive() { return state == State.INITIALIZING || state == State.RUNNING || state == State.PAUSED; }

    // ------------------------------------------------------------------------- lifecycle

    /** Creates the run, its log and the agent. Call from the capture thread. */
    public void start(long wallNow) {
        if (state != State.IDLE) return;
        runId = repo.nextRunId();
        repo.recoverInterrupted(runId);
        startWall = wallNow;
        logger = new RunLogger(repo.runDir(runId), runId, settings.logLevel);
        setState(State.INITIALIZING, "waiting for the game screen");
        learned = settings.learning ? learner.beginRun(settings.skill == AISettings.Skill.ADAPTIVE, new Random(wallNow)) : null;
        observer = new GameStateObserver(layout);
        agent = new AutonomousAgent(settings, layout, this, learned, wallNow);
        log(RunLogger.BASIC, "STATE", "RUN ID: " + runId + " — status RUNNING", null);
        log(RunLogger.BASIC, "AI", "AI initialized: skill " + settings.skill + ", style " + settings.style + ", "
                + settings.decisionHz + " decisions/s, logging " + settings.logLevel, Json.map("tuning", agent.tuning().describe()));
        if (learned != null) log(RunLogger.BASIC, "AI", "Adaptive strategy for this run: \"" + Learner.ARM_NAMES[learner.currentArm] + "\" ("
                + describe(learned) + "); chosen from the scores of " + learner.policyRuns + " earlier Adaptive run(s)", null);
        writeMeta(wallNow, "RUNNING");
        logger.flush(wallNow, 0);
    }

    public void requestStop(String reason) { stopRequested = true; stopReason = reason; }
    public void requestPause(boolean p) { pauseRequested = p; }

    /**
     * One capture frame. Returns the controls to hold (never null; idle when not playing).
     */
    public Action onFrame(int[] px, int w, int h, long frameMs, long wallNow) {
        if (state == State.IDLE || finalized.get()) return Action.idle();
        Observation o;
        try {
            o = observer.observe(px, w, h, frameMs);
        } catch (RuntimeException e) {
            log(RunLogger.BASIC, "ERROR", "Screen reader failed: " + e, Json.map("stack", stack(e)));
            finish(Outcome.ERROR, "Technical error in the screen reader: " + e, wallNow);
            return Action.idle();
        }
        return onObservation(o, wallNow);
    }

    /** Same as {@link #onFrame} for an observation that is already read (used by the simulator). */
    public Action onObservation(Observation o, long wallNow) {
        if (state == State.IDLE || finalized.get()) return Action.idle();
        long frameMs = o.timeMs;
        long dt = lastTickWall < 0 ? 0 : Math.max(0, Math.min(1000, wallNow - lastTickWall));
        lastTickWall = wallNow;
        try {
            if (stopRequested) { finish(Outcome.STOPPED, stopReason.isEmpty() ? "Stopped by you" : stopReason, wallNow); return Action.idle(); }

            // pause: by you, or by the game's own menu
            if (pauseRequested != userPaused) {
                userPaused = pauseRequested;
                log(RunLogger.BASIC, "STATE", userPaused ? "Paused by you" : "Resumed by you", null);
            }
            boolean menu = o.paused;
            if (menu != gamePaused) {
                if (menu) { gamePausedSince = wallNow; }
                gamePaused = menu;
                if (!menu) log(RunLogger.BASIC, "STATE", "Game resumed", null);
            }
            if (gamePaused && gamePausedSince >= 0 && wallNow - gamePausedSince > 400 && !"menu".equals(pauseNote)) {
                pauseNote = "menu";
                // a darkened game right after losing the last hearts is the game-over screen, not a pause
                if (agent.hp >= 0 && agent.hpAfterLastHurt >= 0 && agent.hpAfterLastHurt <= 1 && frameMs - agent.lastHurtT < 5000) {
                    finish(Outcome.DEATH, "Character died (health " + AutonomousAgent.hearts(agent.hpAfterLastHurt) + " hearts, then the game screen darkened)", wallNow);
                    return Action.idle();
                }
                log(RunLogger.BASIC, "STATE", "Game menu open (paused)", null);
            }
            if (!gamePaused) pauseNote = "";
            boolean paused = userPaused || gamePaused;

            if (state == State.INITIALIZING) {
                if (o.hudVisible && o.player != null) {
                    setState(State.RUNNING, "playing");
                    log(RunLogger.BASIC, "STATE", "Character spawned — AI has control", null);
                } else {
                    if (waitingSince < 0) waitingSince = wallNow;
                    if (wallNow - lastWaitLog > 10000) {
                        lastWaitLog = wallNow;
                        log(RunLogger.DETAILED, "STATE", "Waiting for the game: " + (o.hudVisible ? "HUD found, character not found yet" : "game HUD not on screen"), null);
                    }
                    flushMaybe(wallNow);
                    return Action.idle();
                }
            }
            if (paused) {
                if (state == State.RUNNING) setState(State.PAUSED, userPaused ? "paused by you" : "game menu open");
                flushMaybe(wallNow);
                return Action.idle();
            }
            if (state == State.PAUSED) setState(State.RUNNING, "resumed");

            gameMs += dt;

            // end conditions
            if (!o.hudVisible) {
                if (hudLostSince < 0) hudLostSince = wallNow;
                long gone = wallNow - hudLostSince;
                boolean justHurt = agent.hpAfterLastHurt >= 0 && agent.hpAfterLastHurt <= 2 && frameMs - agent.lastHurtT < 6000;
                if (gone > 2500 && justHurt) { finish(Outcome.DEATH, "Character died (last health " + AutonomousAgent.hearts(agent.hpAfterLastHurt) + " hearts, then the game screen changed)", wallNow); return Action.idle(); }
                if (gone > 4000 && agent.bossDefeatedT >= 0 && frameMs - agent.bossDefeatedT < 90000 && agent.hp > 0) {
                    finish(Outcome.VICTORY, "A boss was defeated and the game left the dungeon screen (treated as the victory screen)", wallNow);
                    return Action.idle();
                }
                if (gone > 30000) { finish(Outcome.ERROR, "The game screen was not recognised for 30 s (another app, a menu the bot can't read, or a new screen layout)", wallNow); return Action.idle(); }
                flushMaybe(wallNow);
                return Action.idle();
            }
            hudLostSince = -1;
            if (agent.hp == 0) { finish(Outcome.DEATH, "Character died (all hearts empty)", wallNow); return Action.idle(); }

            Action a = agent.step(o);
            flushMaybe(wallNow);
            return a;
        } catch (RuntimeException e) {
            log(RunLogger.BASIC, "ERROR", "Unexpected error: " + e, Json.map("stack", stack(e)));
            finish(Outcome.ERROR, "Technical error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), wallNow);
            return Action.idle();
        }
    }

    /** For runs ended from outside the frame loop (capture stopped, service destroyed). */
    public void finishNow(Outcome outcome, String reason, long wallNow) {
        if (state == State.IDLE) return;
        finish(outcome, reason, wallNow);
    }

    private void finish(Outcome outcome, String reason, long wallNow) {
        if (!finalized.compareAndSet(false, true)) return;   // one completion, ever
        setState(State.FINALIZING, reason);
        endReason = reason;
        if (agent != null) agent.evaluatePending(Long.MAX_VALUE / 4, true);
        String end;
        switch (outcome) {
            case VICTORY: end = "Victory!"; break;
            case DEATH: end = "Character died"; break;
            case STOPPED: end = "Run stopped"; break;
            default: end = "Run ended with an error";
        }
        log(RunLogger.BASIC, "STATE", end + " — " + reason, null);
        float fit = stats.fitness(outcome == Outcome.VICTORY, gameMs);
        String learnNote = "learning is off";
        if (settings.learning) {
            learnNote = learner.endRun(fit, settings.skill == AISettings.Skill.ADAPTIVE && learned != null, runNumber());
            repo.saveLearner(learner);
        }
        log(RunLogger.BASIC, "AI", "Learning: " + learnNote, null);
        log(RunLogger.BASIC, "STATE", "RUN COMPLETE", null);
        logger.flush(wallNow, 0);
        State fin = outcome == Outcome.ERROR ? State.ERROR : State.COMPLETED;
        Map<String, Object> m = writeMeta(wallNow, fin.name());
        m.put("outcome", outcome.label);
        m.put("endReason", reason);
        m.put("fitness", (double) fit);
        m.put("learning", learnNote);
        m.put("highlights", new java.util.ArrayList<Object>(PerformanceAnalyzer.highlights(repo.events(runId), 40)));
        logger.writeMeta(m);
        repo.enforceLimit(settings.maxLogMb * 1024L * 1024L, runId);
        finalMeta = m;
        setState(fin, reason);
        if (listener != null) listener.onFinished(m);
    }

    public Map<String, Object> finalMeta() { return finalMeta; }

    /** Writes everything logged so far (used when the app is about to crash; the run stays RUNNING
     *  on disk and is marked INTERRUPTED at the next start). */
    public void emergencyFlush(long wallNow) {
        if (logger == null || finalized.get()) return;
        log(RunLogger.BASIC, "ERROR", "The app is crashing — saving the log", null);
        logger.flush(wallNow, 0);
        writeMeta(wallNow, "RUNNING");
    }

    public RunStats liveStats() { return stats; }

    private int runNumber() {
        try { return Integer.parseInt(runId.replaceAll("\\D", "")); } catch (NumberFormatException e) { return 0; }
    }

    private void flushMaybe(long wallNow) {
        logger.flush(wallNow, 1000);
        if (wallNow - lastMetaWall > 4000) writeMeta(wallNow, state == State.PAUSED ? "PAUSED" : state.name());
    }

    private Map<String, Object> writeMeta(long wallNow, String status) {
        lastMetaWall = wallNow;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("status", status);
        m.put("outcome", isLive() ? "In progress" : "");
        m.put("startWall", (double) startWall);
        m.put("updatedWall", (double) wallNow);
        m.put("durationMs", (double) gameMs);
        m.put("skill", settings.skill.name());
        m.put("style", settings.style.name());
        m.put("settings", settings.toMap());
        if (agent != null) m.put("tuning", agent.tuning().describe());
        if (learned != null) { m.put("learnedParams", describe(learned)); if (learner.currentArm >= 0) m.put("strategy", Learner.ARM_NAMES[learner.currentArm]); }
        m.put("stats", stats.toMap());
        m.put("fitness", (double) stats.fitness(false, gameMs));
        m.put("events", logger.eventCount());
        logger.writeMeta(m);
        return m;
    }

    private void setState(State s, String note) {
        state = s;
        if (listener != null) listener.onState(s, note);
    }

    private static String describe(float[] p) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < p.length; i++) b.append(String.format(Locale.US, "%s %+.2f%s", Learner.PARAM_NAMES[i], p[i], i < p.length - 1 ? ", " : ""));
        return b.toString();
    }

    private static String stack(Throwable e) {
        StringBuilder b = new StringBuilder();
        for (StackTraceElement s : e.getStackTrace()) { b.append(s).append('\n'); if (b.length() > 1500) break; }
        return b.toString();
    }

    public static List<Map<String, Object>> history(RunHistoryRepository r) { return r.list(); }
}
