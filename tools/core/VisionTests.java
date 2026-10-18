import com.nickrocks.pixelflowbot.core.*;
import com.nickrocks.pixelflowbot.core.vlm.*;
import com.nickrocks.pixelflowbot.learn.Json;

import java.util.*;

/**
 * Tests for the on-device AI agent with a scripted model: grid cells, coordinate scaling, safety
 * refusals, step and pace limits, session memory, calibration and rule learning.
 */
public class VisionTests {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL: " + what); }
    }

    /** A scripted model: returns the queued replies. */
    static final class Fake implements VisionModel {
        final ArrayDeque<String> replies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        @Override public Reply ask(String system, byte[] jpeg, String prompt, int maxTokens) {
            prompts.add(prompt);
            Reply r = new Reply();
            r.text = replies.isEmpty() ? "{}" : replies.removeFirst();
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

        // ---- the labelled grid (540×1260 screenshot, 9 columns → 60 px cells, 21 rows)
        ScreenGrid gr = InstructionAgent.gridFor(540, 1260);
        check(gr.cols == 9 && gr.rows == 21 && gr.cellW == 60 && gr.cellH == 60, "grid 9×21 of 60 px cells");
        check("C7".equals(gr.label(6, 2)) && Arrays.equals(gr.center("c7"), new float[]{150, 390}), "C7 is column 3, row 7; centre at (150, 390)");
        check(gr.center("J1") == null && gr.center("A22") == null && gr.center("A0") == null && gr.center("7C") == null && gr.center("") == null, "names outside the grid rejected");
        check(Arrays.equals(gr.center(" B-12 "), new float[]{90, 690}), "lenient spelling");
        check(gr.describe().contains("A–I") && gr.describe().contains("1–21"), "grid described in the prompt");
        boolean threw;

        // ---- interpreting decisions (screenshot 540×1260, screen 1080×2520 → ×2)
        InstructionAgent ag = new InstructionAgent(p, new Fake());
        InstructionAgent.Decision d = ag.interpret("```json\n{\"screen\":\"gameplay\",\"observation\":\"board\",\"last_action_effect\":\"none\","
                + "\"action\":{\"type\":\"tap\",\"x\":100,\"y\":600},\"reason\":\"tap piece\",\"confidence\":0.8}\n```", 540, 1260, 1080, 2520);
        GameAction.Stroke s0 = d.action.strokes().get(0);
        check(!d.stop && d.action.kind == GameAction.Kind.TAP && s0.xy[0] == 200 && s0.xy[1] == 1200, "tap scaled to screen pixels (code fence ok)");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"drag\",\"x\":100,\"y\":1100,\"x2\":300,\"y2\":500,\"ms\":600},\"confidence\":0.9}", 540, 1260, 1080, 2520);
        check(d.action.kind == GameAction.Kind.DRAG && d.action.strokes().get(0).xy[3] == 1000 && d.action.durationMs() == 600, "drag with path and duration");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"swipe\",\"x\":10,\"y\":600,\"x2\":900,\"y2\":600},\"confidence\":0.9}", 540, 1260, 1080, 2520);
        check(d.stop && d.problem.contains("off the screenshot"), "refuses a point off the screenshot");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"tap\",\"at\":\"C7\"},\"confidence\":0.8}", 540, 1260, 1080, 2520);
        s0 = d.action.strokes().get(0);
        check(!d.stop && s0.xy[0] == 300 && s0.xy[1] == 780, "tap a grid cell → its centre in screen pixels");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"swipe\",\"at\":\"B10\",\"to\":\"C10\",\"ms\":200},\"confidence\":0.8}", 540, 1260, 1080, 2520);
        check(!d.stop && d.action.kind == GameAction.Kind.SWIPE && d.action.strokes().get(0).xy[2] == 300, "swipe between grid cells");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"tap\",\"at\":\"Z99\"},\"confidence\":0.8}", 540, 1260, 1080, 2520);
        check(d.stop && d.action == null, "a cell that doesn't exist → stop, no tap");
        d = ag.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"tap\",\"at\":\"r2c3\"},\"confidence\":0.8}", 540, 1260, 1080, 2520);
        check(d.stop, "board cells need a confirmed board");
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

        // ---- step and pace limits
        Fake f = new Fake();
        InstructionAgent b = new InstructionAgent(p, f);
        b.maxCalls = 2;
        b.decide(new byte[1], 540, 1260, 1080, 2520);
        b.decide(new byte[1], 540, 1260, 1080, 2520);
        threw = false;
        try { b.decide(new byte[1], 540, 1260, 1080, 2520); } catch (java.io.IOException e) { threw = e.getMessage().contains("step limit"); }
        check(threw && b.calls == 2, "stops at the session step limit");
        Fake f2 = new Fake();
        InstructionAgent rl = new InstructionAgent(p, f2);
        rl.maxCallsPerMinute = 2;
        rl.decide(new byte[1], 540, 1260, 1080, 2520);
        rl.decide(new byte[1], 540, 1260, 1080, 2520);
        threw = false;
        try { rl.decide(new byte[1], 540, 1260, 1080, 2520); } catch (java.io.IOException e) { threw = e.getMessage().contains("pace"); }
        check(threw, "pace limit per minute");

        // ---- prompts: instructions, confirmed calibration only, learned notes, session memory
        Fake f3 = new Fake();
        p.settings.put("calibration", "Board: 8×8 in the middle");
        p.settings.put("calibration_confirmed", "no");
        p.settings.put("learned", "• Pieces snap to the grid [steps 2, 4]");
        InstructionAgent m = new InstructionAgent(p, f3);
        for (int i = 0; i < 6; i++) f3.replies.add("{\"screen\":\"gameplay\",\"observation\":\"o" + i + "\",\"last_action_effect\":\"" + (i % 2 == 0 ? "worked" : "no_effect")
                + "\",\"action\":{\"type\":\"tap\",\"x\":200,\"y\":600},\"reason\":\"r" + i + "\",\"confidence\":0.9}");
        for (int i = 0; i < 6; i++) m.decide(new byte[1], 540, 1260, 1080, 2520);
        String last = f3.prompts.get(f3.prompts.size() - 1);
        check(last.contains("Drag pieces onto the board"), "prompt carries your instructions");
        check(!last.contains("Board: 8×8"), "unconfirmed calibration is not presented as fact");
        check(last.contains("Pieces snap to the grid") && last.contains("hypotheses"), "learned notes included, labelled as hypotheses");
        check(last.contains("step 5: saw o4"), "session memory: recent steps included");
        p.settings.put("calibration_confirmed", "yes");
        check(new InstructionAgent(p, f3).decidePromptForTest().contains("Board: 8×8"), "confirmed calibration is included");
        // confirmed board → board cells: 8×8 board at x 40–1040, y 600–1600 (125 px cells)
        p.settings.put("board", "40,600,1000,1000,8,8");
        InstructionAgent bc = new InstructionAgent(p, f3);
        check(bc.decidePromptForTest().contains("r1c1") && bc.decidePromptForTest().contains("r8c8"), "board cells offered once the board is confirmed");
        d = bc.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"swipe\",\"at\":\"r2c3\",\"to\":\"r2 c4\"},\"confidence\":0.8}", 540, 1260, 1080, 2520);
        s0 = d.action.strokes().get(0);
        check(!d.stop && Math.abs(s0.xy[0] - 352.5f) < 0.6 && Math.abs(s0.xy[1] - 787.5f) < 0.6 && Math.abs(s0.xy[2] - 477.5f) < 0.6, "board cell r2c3 → centre (352.5, 787.5)");
        d = bc.interpret("{\"screen\":\"gameplay\",\"action\":{\"type\":\"tap\",\"at\":\"r9c1\"},\"confidence\":0.8}", 540, 1260, 1080, 2520);
        check(d.stop, "board cell outside the board → stop");
        p.settings.remove("board");
        f3.replies.add("{\"rules\":[\"Tapping a piece selects it [steps 1, 3]\",\"Empty cells accept pieces [step 5]\"]}");
        String rules = m.learnRules();
        check(rules != null && rules.startsWith("• Tapping a piece") && f3.prompts.get(f3.prompts.size() - 1).contains("effect: no_effect"), "rule learning uses the steps and their effects");

        // ---- calibration parsing
        Fake f4 = new Fake();
        f4.replies.add("{\"screen\":\"gameplay\",\"summary\":\"8x8 grid with 3 pieces below\",\"objective\":\"fill lines\",\"board\":{\"from\":\"A6\",\"to\":\"I14\",\"rows\":8,\"cols\":8},"
                + "\"controls\":[\"drag a piece onto the grid\"],\"hud\":[\"score 120\"],\"questions\":[\"Can pieces rotate?\"],\"confidence\":0.7}");
        InstructionAgent.Calibration c = new InstructionAgent(p, f4).calibrate(new byte[1], 540, 1260, 1080, 2520);
        check(c.board != null && c.board[0] == 0 && c.board[1] == 600 && c.board[2] == 1080 && c.board[3] == 1080 && c.board[4] == 8, "board from grid cells A6–I14, scaled to screen pixels");
        check(new InstructionAgent(p, f4).calibratePromptForTest().contains("grid"), "calibration prompt explains the grid");
        check(c.controls.size() == 1 && c.questions.get(0).contains("rotate") && c.text().contains("Board: 8×8"), "controls, questions, readable text");

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

}
