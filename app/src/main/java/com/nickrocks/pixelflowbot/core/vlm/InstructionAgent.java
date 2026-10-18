package com.nickrocks.pixelflowbot.core.vlm;

import com.nickrocks.pixelflowbot.core.GameAction;
import com.nickrocks.pixelflowbot.core.GameProfile;
import com.nickrocks.pixelflowbot.core.StateObservation;
import com.nickrocks.pixelflowbot.learn.Json;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plays any app from the owner's written instructions, using a vision-language model.
 *
 * Each step: screenshot (shrunk) + instructions + confirmed calibration + rules learned earlier
 * + the last few steps → the model answers with one JSON object: what screen this is, what
 * changed after the last action, and the next action (tap / long press / swipe / drag / wait /
 * stop) in screenshot pixels. This class turns that into a {@link StateObservation} and a
 * {@link GameAction} in screen pixels, and refuses anything unsafe.
 *
 * What it is: planning with a general model, plus "rule learning" as written notes the model
 * keeps about the game (saved in the profile, shown to you, editable). What it is not:
 * reinforcement learning — no weights are trained here.
 */
public final class InstructionAgent {
    public static final int IMG_W = 540;

    public final GameProfile profile;
    private final VisionModel model;
    public final Pricing.Model price;

    // Budget and pacing (set from the user's settings).
    public double maxUsd = 0.50;
    public int maxCallsPerMinute = 20;
    public double spentUsd;
    public int calls;
    private final ArrayDeque<Long> recentCalls = new ArrayDeque<>();

    /** Session memory: the last steps, fed back to the model. */
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private final List<String> sessionLog = new ArrayList<>();
    private int step;

    public InstructionAgent(GameProfile profile, VisionModel model, Pricing.Model price) {
        this.profile = profile;
        this.model = model;
        this.price = price;
    }

    // ------------------------------------------------------------------ prompts

    static final String SYSTEM =
            "You operate a mobile game on an Android phone for the phone's owner, by looking at screenshots. "
                    + "You can tap, long-press, swipe or drag, or wait. The owner's instructions describe the game and the goal; follow them. "
                    + "Coordinates are pixels in the screenshot you are given (origin top-left).\n"
                    + "Safety rules, always: never tap ads, purchase or payment screens, 'buy' buttons, coin/gem/gold shops, offers, "
                    + "login or sign-in, permission prompts, rating requests, links that leave the game, or the phone's system bars. "
                    + "If one of those is showing, answer with action \"stop\" and say what it is. "
                    + "If you are unsure what to do, prefer \"wait\" or \"stop\" over guessing. "
                    + "Reply with exactly one JSON object and nothing else.";

    String context() {
        StringBuilder sb = new StringBuilder();
        sb.append("Game: ").append(profile.name).append(profile.packageName != null ? " (" + profile.packageName + ")" : "").append('\n');
        String instr = profile.settings.get("objective");
        sb.append("Owner's instructions:\n").append(instr == null || instr.trim().isEmpty() ? "(none given — work out the goal from the screen)" : instr.trim()).append('\n');
        String cal = profile.settings.get("calibration");
        if (cal != null && !cal.isEmpty() && "yes".equals(profile.settings.get("calibration_confirmed"))) {
            sb.append("Layout confirmed by the owner:\n").append(cal).append('\n');
        }
        String learned = profile.settings.get("learned");
        if (learned != null && !learned.isEmpty()) {
            sb.append("Notes from earlier sessions (hypotheses — may be wrong, revise them if the screen disagrees):\n").append(learned).append('\n');
        }
        return sb.toString();
    }

    String calibratePrompt(int w, int h) {
        return context() + "\nThe screenshot is " + w + "x" + h + " px. Look at it and describe how this game is played on this screen. Reply as JSON:\n"
                + "{\"screen\":\"gameplay|menu|level_complete|loss|loading|ad|purchase|login|dialog|other\","
                + "\"summary\":\"what is on screen\","
                + "\"objective\":\"the goal, from the instructions and what you see\","
                + "\"board\":{\"x\":0,\"y\":0,\"w\":0,\"h\":0,\"rows\":0,\"cols\":0} or null,"
                + "\"controls\":[\"how the player acts, e.g. swipe two adjacent candies to swap them\"],"
                + "\"hud\":[\"score, moves, lives, timers, goals you can see, with their values\"],"
                + "\"questions\":[\"anything you need the owner to tell you\"],"
                + "\"confidence\":0.0}";
    }

    String decidePrompt(int w, int h) {
        StringBuilder sb = new StringBuilder(context());
        if (!history.isEmpty()) {
            sb.append("\nYour last steps (oldest first):\n");
            for (String s : history) sb.append("- ").append(s).append('\n');
        }
        sb.append("\nThe screenshot is ").append(w).append('x').append(h).append(" px. Choose the next action. Reply as JSON:\n")
                .append("{\"screen\":\"gameplay|menu|level_complete|loss|loading|ad|purchase|login|dialog|other\",")
                .append("\"observation\":\"what you see and what changed since your last action\",")
                .append("\"last_action_effect\":\"worked|no_effect|unclear|none\",")
                .append("\"action\":{\"type\":\"tap|long_press|swipe|drag|wait|stop\",\"x\":0,\"y\":0,\"x2\":0,\"y2\":0,\"ms\":300},")
                .append("\"reason\":\"one sentence\",")
                .append("\"confidence\":0.0}");
        return sb.toString();
    }

    // ------------------------------------------------------------------ calls

    /** Null if a call is allowed now; otherwise why not. */
    public String budgetProblem() {
        // Expected cost of the next call: the estimate, or the real average so far if that's higher.
        double est = Math.max(Pricing.stepEstimate(price, IMG_W, IMG_W * 21 / 9), calls == 0 ? 0 : spentUsd / calls);
        if (spentUsd + est > maxUsd) return "session budget reached (" + Pricing.usd(spentUsd) + " of " + Pricing.usd(maxUsd) + ")";
        long now = System.currentTimeMillis();
        while (!recentCalls.isEmpty() && now - recentCalls.peekFirst() > 60_000) recentCalls.removeFirst();
        if (recentCalls.size() >= maxCallsPerMinute) return "rate limit (" + maxCallsPerMinute + " calls/min)";
        return null;
    }

    private VisionModel.Reply call(byte[] jpeg, String prompt, int maxTokens) throws IOException {
        String b = budgetProblem();
        if (b != null) throw new IOException(b);
        recentCalls.addLast(System.currentTimeMillis());
        VisionModel.Reply r = model.ask(SYSTEM, jpeg, prompt, maxTokens);
        calls++;
        spentUsd += r.costUsd;
        return r;
    }

    // ------------------------------------------------------------------ calibration

    public static final class Calibration {
        public String screen = "", summary = "", objective = "";
        public int[] board;                 // x, y, w, h in SCREEN pixels, rows, cols — or null
        public final List<String> controls = new ArrayList<>(), hud = new ArrayList<>(), questions = new ArrayList<>();
        public float confidence;
        public String raw = "";

        /** Readable text saved in the profile (and shown to the owner to confirm or correct). */
        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("Screen: ").append(screen).append(" — ").append(summary).append('\n');
            if (!objective.isEmpty()) sb.append("Goal: ").append(objective).append('\n');
            if (board != null) sb.append(String.format(Locale.US, "Board: %d×%d cells at x %d–%d, y %d–%d (screen px)%n",
                    board[5], board[4], board[0], board[0] + board[2], board[1], board[1] + board[3]));
            for (String c : controls) sb.append("Control: ").append(c).append('\n');
            for (String c : hud) sb.append("On screen: ").append(c).append('\n');
            return sb.toString().trim();
        }
    }

    public Calibration calibrate(byte[] jpeg, int imgW, int imgH, int screenW, int screenH) throws IOException {
        VisionModel.Reply r = call(jpeg, calibratePrompt(imgW, imgH), 900);
        Calibration c = new Calibration();
        c.raw = r.text;
        Map<String, Object> m = jsonIn(r.text);
        c.screen = Json.str(m, "screen", "other");
        c.summary = Json.str(m, "summary", "");
        c.objective = Json.str(m, "objective", "");
        c.confidence = (float) clamp(Json.num(m, "confidence", 0), 0, 1);
        strings(m.get("controls"), c.controls);
        strings(m.get("hud"), c.hud);
        strings(m.get("questions"), c.questions);
        Object b = m.get("board");
        if (b instanceof Map) {
            Map<String, Object> bm = Json.obj(b);
            float sx = screenW / (float) imgW, sy = screenH / (float) imgH;
            int x = (int) (Json.num(bm, "x", 0) * sx), y = (int) (Json.num(bm, "y", 0) * sy);
            int w = (int) (Json.num(bm, "w", 0) * sx), h = (int) (Json.num(bm, "h", 0) * sy);
            int rows = (int) Json.num(bm, "rows", 0), cols = (int) Json.num(bm, "cols", 0);
            if (w > 0 && h > 0) c.board = new int[]{x, y, w, h, rows, cols};
        }
        return c;
    }

    // ------------------------------------------------------------------ playing

    public static final class Decision {
        public StateObservation obs;
        public GameAction action;     // null for wait / stop
        public boolean stop, wait;
        public String reason = "", observation = "", effect = "none", problem;
        public float confidence;
        public VisionModel.Reply reply;
    }

    public Decision decide(byte[] jpeg, int imgW, int imgH, int screenW, int screenH) throws IOException {
        VisionModel.Reply r = call(jpeg, decidePrompt(imgW, imgH), 600);
        Decision d = interpret(r.text, imgW, imgH, screenW, screenH);
        d.reply = r;
        step++;
        String act = d.stop ? "stop" : d.wait ? "wait" : d.action.toString();
        String line = String.format(Locale.US, "step %d: saw %s; did %s (%s)", step, d.observation, act, d.reason);
        history.addLast(line);
        while (history.size() > 8) history.removeFirst();
        sessionLog.add(line + " → effect reported next step");
        if (sessionLog.size() > 1) {
            int k = sessionLog.size() - 2;
            sessionLog.set(k, sessionLog.get(k).replace("effect reported next step", "effect: " + d.effect));
        }
        return d;
    }

    /** Parses and checks a model reply. Pure function of the text (unit-tested). */
    public Decision interpret(String text, int imgW, int imgH, int screenW, int screenH) {
        Decision d = new Decision();
        StateObservation o = new StateObservation(profile.id, profile.version, System.currentTimeMillis());
        d.obs = o;
        Map<String, Object> m;
        try {
            m = jsonIn(text);
        } catch (IOException e) {
            o.screen = StateObservation.Screen.UNKNOWN;
            o.confidence = 0;
            o.problem = "model reply wasn't JSON";
            d.stop = true;
            d.problem = o.problem;
            return d;
        }
        String screen = Json.str(m, "screen", "other");
        o.screen = screenOf(screen);
        d.confidence = (float) clamp(Json.num(m, "confidence", 0), 0, 1);
        o.confidence = d.confidence;
        d.observation = Json.str(m, "observation", "");
        d.effect = Json.str(m, "last_action_effect", "none");
        d.reason = Json.str(m, "reason", "");
        o.add("screen (model)", screen, d.confidence, StateObservation.Source.INFERRED);
        o.add("what the model sees", d.observation, d.confidence, StateObservation.Source.INFERRED);
        o.add("effect of last action (model)", d.effect, d.confidence, StateObservation.Source.INFERRED);
        Map<String, Object> a = m.get("action") instanceof Map ? Json.obj(m.get("action")) : null;
        String type = a == null ? "stop" : Json.str(a, "type", "stop");
        if (o.screen == StateObservation.Screen.BLOCKED) {
            d.stop = true;
            d.problem = "an " + screen + " screen is showing — the bot never touches those";
            return d;
        }
        if (type.equals("stop")) { d.stop = true; d.problem = "model chose to stop: " + d.reason; return d; }
        if (type.equals("wait")) { d.wait = true; return d; }
        float sx = screenW / (float) imgW, sy = screenH / (float) imgH;
        float x = (float) Json.num(a, "x", -1), y = (float) Json.num(a, "y", -1);
        float x2 = (float) Json.num(a, "x2", -1), y2 = (float) Json.num(a, "y2", -1);
        long ms = (long) clamp(Json.num(a, "ms", 300), 10, 3000);
        String bad = pointProblem(x, y, imgW, imgH);
        boolean twoPoint = type.equals("swipe") || type.equals("drag");
        if (bad == null && twoPoint) bad = pointProblem(x2, y2, imgW, imgH);
        if (bad != null) { d.stop = true; d.problem = "model asked for an unsafe point: " + bad; return d; }
        String label = d.reason.length() > 80 ? d.reason.substring(0, 80) : d.reason;
        switch (type) {
            case "tap": d.action = GameAction.tap(x * sx, y * sy, label); break;
            case "long_press": d.action = GameAction.longPress(x * sx, y * sy, Math.max(500, ms), label); break;
            case "swipe": d.action = GameAction.swipe(x * sx, y * sy, x2 * sx, y2 * sy, Math.max(80, ms), label); break;
            case "drag": d.action = GameAction.drag(new float[]{x * sx, y * sy, x2 * sx, y2 * sy}, Math.max(250, ms), label); break;
            default: d.stop = true; d.problem = "unknown action type '" + type + "'"; return d;
        }
        d.action.source = "vision model";
        return d;
    }

    /** Points must be on the screenshot and away from the status bar (top 3%) and navigation bar (bottom 4%). */
    static String pointProblem(float x, float y, int w, int h) {
        if (x < 0 || y < 0 || x >= w || y >= h) return String.format(Locale.US, "(%.0f, %.0f) is off the screenshot", x, y);
        if (y < h * 0.03f) return "it is on the status bar";
        if (y > h * 0.96f) return "it is on the navigation bar";
        return null;
    }

    static StateObservation.Screen screenOf(String s) {
        switch (s) {
            case "gameplay": return StateObservation.Screen.GAMEPLAY;
            case "menu": return StateObservation.Screen.MENU;
            case "level_complete": return StateObservation.Screen.LEVEL_COMPLETE;
            case "loss": return StateObservation.Screen.LOSS;
            case "loading": return StateObservation.Screen.TRANSITION;
            case "ad": case "purchase": case "login": case "dialog": return StateObservation.Screen.BLOCKED;
            default: return StateObservation.Screen.UNKNOWN;
        }
    }

    /** Reward for the previous action, from the model's own report — labelled as such. */
    public static float rewardOf(String effect) {
        switch (effect) {
            case "worked": return 1f;
            case "no_effect": return -0.5f;
            default: return 0f;
        }
    }

    // ------------------------------------------------------------------ rule learning

    /**
     * Asks the model to turn this session's steps into a short list of rules it now believes
     * about the game, each with the steps that support it. Returns null if there's too little.
     */
    public String learnRules() throws IOException {
        if (sessionLog.size() < 5) return null;
        StringBuilder sb = new StringBuilder(context());
        sb.append("\nSteps from this session:\n");
        int from = Math.max(0, sessionLog.size() - 60);
        for (int i = from; i < sessionLog.size(); i++) sb.append(sessionLog.get(i)).append('\n');
        sb.append("\nWrite up to 8 short rules you now believe about this game's controls, goal and screens, merged with the earlier notes. "
                + "Each rule ends with its evidence in brackets, e.g. [steps 3, 7]. Keep only what the steps support; drop earlier notes they contradict. "
                + "Reply as JSON: {\"rules\":[\"...\"]}");
        VisionModel.Reply r = call(null, sb.toString(), 700);
        List<String> rules = new ArrayList<>();
        strings(jsonIn(r.text).get("rules"), rules);
        if (rules.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (String s : rules) out.append("• ").append(s).append('\n');
        return out.toString().trim();
    }

    public int steps() { return step; }

    /** For tests: the prompt the next decision would send. */
    public String decidePromptForTest() { return decidePrompt(IMG_W, 1260); }

    // ------------------------------------------------------------------ helpers

    /** The first {...} JSON object in a reply (models sometimes wrap it in text or code fences). */
    static Map<String, Object> jsonIn(String text) throws IOException {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IOException("no JSON in the reply");
        try {
            return Json.obj(Json.parse(text.substring(a, b + 1)));
        } catch (RuntimeException e) {
            throw new IOException("bad JSON in the reply: " + e.getMessage());
        }
    }

    static void strings(Object o, List<String> out) {
        if (o instanceof List) for (Object x : (List<?>) o) if (x != null) out.add(String.valueOf(x));
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
