import com.nickrocks.pixelflowbot.core.*;
import com.nickrocks.pixelflowbot.core.vlm.*;
import com.nickrocks.pixelflowbot.learn.Json;

import java.util.*;

/**
 * Tests for the AI vision agent without network or API key: request shape, reply parsing,
 * coordinate scaling, safety refusals, budget and rate limits, session memory and rule learning.
 */
public class VisionTests {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL: " + what); }
    }

    /** A scripted model: returns the queued replies, charging a fixed cost per call. */
    static final class Fake implements VisionModel {
        final ArrayDeque<String> replies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        double cost = 0.001;
        @Override public Reply ask(String system, byte[] jpeg, String prompt, int maxTokens) {
            prompts.add(prompt);
            Reply r = new Reply();
            r.text = replies.isEmpty() ? "{}" : replies.removeFirst();
            r.costUsd = cost;
            return r;
        }
        @Override public String name() { return "fake"; }
    }

    public static void main(String[] a) throws Exception {
        GameProfile p = new GameProfile();
        p.id = "block-blast";
        p.name = "Block Blast";
        p.packageName = "com.block.blast";
        p.settings.put("objective", "Drag pieces onto the board to fill rows and columns.");
        p.version = 3;

        // ---- request shape
        ClaudeVision cv = new ClaudeVision("sk-ant-test", Pricing.HAIKU);
        Map<String, Object> body = Json.obj(Json.parse(cv.body("SYS", new byte[]{1, 2, 3}, "hello \"there\"\nline", 600)));
        check("claude-haiku-5-5".equals(body.get("model")) && ((Double) body.get("max_tokens")) == 600, "model and max_tokens");
        check("low".equals(Json.obj(body.get("output_config")).get("effort")), "low effort for speed");
        List<?> content = (List<?>) Json.obj(((List<?>) body.get("messages")).get(0)).get("content");
        Map<String, Object> img = Json.obj(content.get(0)), src = Json.obj(img.get("source"));
        check("image".equals(img.get("type")) && "base64".equals(src.get("type")) && "image/jpeg".equals(src.get("media_type"))
                && "AQID".equals(src.get("data")), "image block first, base64 JPEG");
        check("hello \"there\"\nline".equals(Json.obj(content.get(1)).get("text")), "text block escaped correctly");
        check(!cv.body("S", null, "x", 10).contains("\"image\""), "text-only request has no image");
        boolean threw = false;
        try { new ClaudeVision(" ", Pricing.HAIKU); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "refuses an empty API key");

        // ---- reply parsing and cost
        VisionModel.Reply r = ClaudeVision.parse("{\"id\":\"msg_1\",\"type\":\"message\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"…\"},"
                + "{\"type\":\"text\",\"text\":\"{\\\"a\\\":1}\"}],\"usage\":{\"input_tokens\":2700,\"output_tokens\":300}}");
        check("{\"a\":1}".equals(r.text) && r.inputTokens == 2700 && r.outputTokens == 300, "reply text and usage");
        check(Math.abs(Pricing.HAIKU.cost(2700, 300) - 0.00042) < 1e-9, "Haiku cost: 2700 in + 300 out = $0.00042");
        check(Pricing.imageTokens(540, 1260) == 900, "540×1260 screenshot = 900 image tokens");
        check("overloaded".equals(errorOf("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}")), "API error message read");

        // ---- interpreting decisions (screenshot 540×1260, screen 1080×2520 → ×2)
        InstructionAgent ag = new InstructionAgent(p, new Fake(), Pricing.HAIKU);
        InstructionAgent.Decision d = ag.interpret("```json\n{\"screen\":\"gameplay\",\"observation\":\"board\",\"last_action_effect\":\"none\","
                + "\"action\":{\"type\":\"tap\",\"x\":100,\"y\":600},\"reason\":\"tap piece\",\"confidence\":0.8}\n```", 540, 1260, 1080, 2520);
        GameAction.Stroke s0 = d.action.strokes().get(0);
        check(!d.stop && d.action.kind == GameAction.Kind.TAP && s0.xy[0] == 200 && s0.xy[1] == 1200, "tap scaled to screen pixels (code fence ok)");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"drag\",\"x\":100,\"y\":1100,\"x2\":300,\"y2\":500,\"ms\":600},\"confidence\":0.9}", 540, 1260, 1080, 2520);
        check(d.action.kind == GameAction.Kind.DRAG && d.action.strokes().get(0).xy[3] == 1000 && d.action.durationMs() == 600, "drag with path and duration");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"swipe\",\"x\":10,\"y\":600,\"x2\":900,\"y2\":600},\"confidence\":0.9}", 540, 1260, 1080, 2520);
        check(d.stop && d.problem.contains("off the screenshot"), "refuses a point off the screenshot");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"tap\",\"x\":270,\"y\":10},\"confidence\":0.9}", 540, 1260, 1080, 2520);
        check(d.stop && d.problem.contains("status bar"), "refuses the status bar");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"tap\",\"x\":270,\"y\":1250},\"confidence\":0.9}", 540, 1260, 1080, 2520);
        check(d.stop && d.problem.contains("navigation bar"), "refuses the navigation bar");
        d = ag.interpret("{\"screen\":\"purchase\",\"action\":{\"type\":\"tap\",\"x\":270,\"y\":600},\"confidence\":0.9}", 540, 1260, 1080, 2520);
        check(d.stop && d.action == null && d.obs.screen == StateObservation.Screen.BLOCKED, "never taps on a purchase screen even if asked");
        d = ag.interpret("{\"screen\":\"ad\",\"action\":{\"type\":\"tap\",\"x\":500,\"y\":60}}", 540, 1260, 1080, 2520);
        check(d.stop && d.action == null, "never taps an ad (e.g. its close button) — pauses for you");
        d = ag.interpret("{\"screen\":\"loading\",\"action\":{\"type\":\"wait\"},\"confidence\":0.7}", 540, 1260, 1080, 2520);
        check(d.wait && !d.stop, "wait");
        d = ag.interpret("I think you should tap the middle", 540, 1260, 1080, 2520);
        check(d.stop && d.obs.confidence == 0, "non-JSON reply → stop, zero confidence");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"teleport\",\"x\":1,\"y\":600}}", 540, 1260, 1080, 2520);
        check(d.stop, "unknown action type → stop");
        check(InstructionAgent.rewardOf("worked") > 0 && InstructionAgent.rewardOf("no_effect") < 0 && InstructionAgent.rewardOf("unclear") == 0, "reward from the model's report");

        // ---- guard for a walk-through agent
        SessionGuard g = new SessionGuard();
        g.minConfidence = 0.5f;
        g.playable.add(StateObservation.Screen.MENU);
        SessionGuard.Status st = new SessionGuard.Status();
        st.captureOn = st.controlOn = true;
        st.foregroundPackage = p.packageName;
        st.latest = ag.interpret("{\"screen\":\"menu\",\"action\":{\"type\":\"tap\",\"x\":270,\"y\":900},\"confidence\":0.8}", 540, 1260, 1080, 2520).obs;
        check(g.check(p, st) == null, "menus allowed for the walk-through agent");
        st.latest = ag.interpret("{\"screen\":\"menu\",\"action\":{\"type\":\"tap\",\"x\":270,\"y\":900},\"confidence\":0.3}", 540, 1260, 1080, 2520).obs;
        check(g.check(p, st) != null, "low confidence still pauses");
        st.latest = ag.interpret("{\"screen\":\"login\"}", 540, 1260, 1080, 2520).obs;
        check(g.check(p, st) != null, "login screen pauses");

        // ---- budget and rate limit
        Fake f = new Fake();
        f.cost = 0.2;
        InstructionAgent b = new InstructionAgent(p, f, Pricing.HAIKU);
        b.maxUsd = 0.5;
        for (int i = 0; i < 3; i++) f.replies.add("{\"screen\":\"gameplay\",\"action\":{\"type\":\"wait\"}}");
        b.decide(new byte[1], 540, 1260, 1080, 2520);
        b.decide(new byte[1], 540, 1260, 1080, 2520);
        threw = false;
        try { b.decide(new byte[1], 540, 1260, 1080, 2520); } catch (java.io.IOException e) { threw = e.getMessage().contains("budget"); }
        check(threw && b.calls == 2, "stops at the session budget");
        Fake f2 = new Fake();
        InstructionAgent rl = new InstructionAgent(p, f2, Pricing.HAIKU);
        rl.maxCallsPerMinute = 2;
        rl.decide(new byte[1], 540, 1260, 1080, 2520);
        rl.decide(new byte[1], 540, 1260, 1080, 2520);
        threw = false;
        try { rl.decide(new byte[1], 540, 1260, 1080, 2520); } catch (java.io.IOException e) { threw = e.getMessage().contains("rate"); }
        check(threw, "rate limit per minute");

        // ---- prompts: instructions, confirmed calibration only, learned notes, session memory
        Fake f3 = new Fake();
        p.settings.put("calibration", "Board: 8×8 in the middle");
        p.settings.put("calibration_confirmed", "no");
        p.settings.put("learned", "• Pieces snap to the grid [steps 2, 4]");
        InstructionAgent m = new InstructionAgent(p, f3, Pricing.HAIKU);
        for (int i = 0; i < 6; i++) f3.replies.add("{\"screen\":\"gameplay\",\"observation\":\"o" + i + "\",\"last_action_effect\":\"" + (i % 2 == 0 ? "worked" : "no_effect")
                + "\",\"action\":{\"type\":\"tap\",\"x\":200,\"y\":600},\"reason\":\"r" + i + "\",\"confidence\":0.9}");
        for (int i = 0; i < 6; i++) m.decide(new byte[1], 540, 1260, 1080, 2520);
        String last = f3.prompts.get(f3.prompts.size() - 1);
        check(last.contains("Drag pieces onto the board"), "prompt carries your instructions");
        check(!last.contains("Board: 8×8"), "unconfirmed calibration is not presented as fact");
        check(last.contains("Pieces snap to the grid") && last.contains("hypotheses"), "learned notes included, labelled as hypotheses");
        check(last.contains("step 5: saw o4"), "session memory: recent steps included");
        p.settings.put("calibration_confirmed", "yes");
        check(new InstructionAgent(p, f3, Pricing.HAIKU).decidePromptForTest().contains("Board: 8×8"), "confirmed calibration is included");
        f3.replies.add("{\"rules\":[\"Tapping a piece selects it [steps 1, 3]\",\"Empty cells accept pieces [step 5]\"]}");
        String rules = m.learnRules();
        check(rules != null && rules.startsWith("• Tapping a piece") && f3.prompts.get(f3.prompts.size() - 1).contains("effect: no_effect"), "rule learning uses the steps and their effects");

        // ---- calibration parsing
        Fake f4 = new Fake();
        f4.replies.add("{\"screen\":\"gameplay\",\"summary\":\"8x8 grid with 3 pieces below\",\"objective\":\"fill lines\",\"board\":{\"x\":20,\"y\":300,\"w\":500,\"h\":500,\"rows\":8,\"cols\":8},"
                + "\"controls\":[\"drag a piece onto the grid\"],\"hud\":[\"score 120\"],\"questions\":[\"Can pieces rotate?\"],\"confidence\":0.7}");
        InstructionAgent.Calibration c = new InstructionAgent(p, f4, Pricing.HAIKU).calibrate(new byte[1], 540, 1260, 1080, 2520);
        check(c.board != null && c.board[0] == 40 && c.board[1] == 600 && c.board[2] == 1000 && c.board[4] == 8, "board rectangle scaled to screen pixels");
        check(c.controls.size() == 1 && c.questions.get(0).contains("rotate") && c.text().contains("Board: 8×8"), "controls, questions, readable text");

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static String errorOf(String s) throws Exception {
        java.lang.reflect.Method mm = ClaudeVision.class.getDeclaredMethod("errorMessage", String.class);
        mm.setAccessible(true);
        return (String) mm.invoke(null, s);
    }
}
