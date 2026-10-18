import com.nickrocks.pixelflowbot.core.*;
import com.nickrocks.pixelflowbot.game.blocks.*;
import com.nickrocks.pixelflowbot.game.dodge.*;
import com.nickrocks.pixelflowbot.learn.*;

import java.util.*;

/** Rule and platform tests for the lab games (Block Puzzle, Dodge). */
public class LabTests {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL: " + what); }
    }

    public static void main(String[] a) throws Exception {
        blocks();
        dodge();
        platform();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void blocks() {
        boolean[] b = new boolean[64];
        for (int c = 0; c < 7; c++) b[c] = true;                       // row 0 missing col 7
        for (int r = 1; r < 8; r++) b[r * 8 + 7] = true;               // col 7 missing row 0
        Blocks.Placed p = Blocks.place(b, 0, 0, 7);                     // the dot completes both
        check(p.lines == 2 && p.points == 1 + 20 + 10, "row+column clear at once, combo points");
        boolean empty = true;
        for (boolean x : p.board) if (x) empty = false;
        check(empty, "cleared board is empty");
        check(!Blocks.fits(new boolean[64], 7, 0, 4) && Blocks.fits(new boolean[64], 7, 0, 3), "I5 must stay on the board");
        BlockEnv env = new BlockEnv();
        BlockState s1 = env.reset(new Random(5)), s2 = new BlockEnv().reset(new Random(5));
        check(Arrays.equals(s1.tray, s2.tray), "same seed, same pieces");
        Env.Outcome<BlockState> bad = env.step(s1, BlockState.encode(5, 0, 0));
        check(bad.next == s1 && bad.reward < 0, "invalid placement rejected, board unchanged");
        // play randomly to the end: must terminate with game over or the cap
        BlockGame g = new BlockGame();
        BlockState s = s1;
        Random rnd = new Random(1);
        int n = 0;
        boolean done = false;
        while (!done && n < 1000) {
            List<GameAdapter.Candidate> c = g.adapter().candidates(s);
            if (c.isEmpty()) break;
            Env.Outcome<BlockState> o = env.step(s, c.get(rnd.nextInt(c.size())).action);
            s = o.next;
            done = o.done;
            n++;
        }
        check(done && (s.over || s.placed >= BlockEnv.MAX_PLACEMENTS), "episode ends");
        check(g.adapter().candidates(s).isEmpty() || !s.over, "no moves offered after game over");
    }

    static void dodge() {
        // The corridor guarantee: the hand-written rule never meets a row with every lane blocked.
        DodgeEnv env = new DodgeEnv();
        DodgeState s = env.reset(new Random(3));
        boolean fullRow = false;
        for (int t = 0; t < 3000; t++) {
            Env.Outcome<DodgeState> o = env.step(s, 0);
            s = o.next;
            for (int r = 0; r < DodgeState.ROWS; r++) {
                boolean all = true;
                for (int l = 0; l < DodgeState.LANES; l++) if (!s.at(r, l)) all = false;
                if (all) fullRow = true;
            }
            if (o.done) s = env.reset(new Random(t));
        }
        check(!fullRow, "no row is ever fully blocked");
        // Crash when moving into an obstacle in the player's row.
        boolean[] ob = new boolean[DodgeState.LANES * DodgeState.ROWS];
        DodgeEnv e2 = new DodgeEnv();
        e2.reset(new Random(1));
        Env.Outcome<DodgeState> o = e2.step(null, 1);
        check(!o.done && o.next.lane == 3, "move right one lane");
        // Latency: with 2 ticks of observation delay the agent sees an old picture.
        DodgeEnv e3 = new DodgeEnv(2, 0);
        DodgeState first = e3.reset(new Random(9));
        DodgeState seen1 = e3.step(first, 1).next;
        check(seen1.tick == 0, "observation lags behind the game");
        e3.step(seen1, 0);
        DodgeState seen3 = e3.step(seen1, 0).next;
        check(seen3.tick == 1 && e3.truth().tick == 3, "lag is exactly 2 ticks");
        DodgeEnv e4 = new DodgeEnv(0, 2);
        e4.reset(new Random(9));
        DodgeState t1 = e4.step(null, 1).next;
        check(t1.lane == 2, "tap lag: the move hasn't happened yet");
        e4.step(null, 0);
        DodgeState t3 = e4.step(null, 0).next;
        check(t3.lane == 3, "tap lag: the move lands 2 ticks later");
        check(Arrays.equals(new DodgeEnv().reset(new Random(4)).obstacles, new DodgeEnv().reset(new Random(4)).obstacles), "dodge seeds reproducible");
    }

    static void platform() {
        // Training and evaluation seed streams differ.
        Set<Long> ev = new HashSet<>();
        for (int i = 0; i < 1000; i++) ev.add(Lab.evalSeed(BlockGame.ID, i));
        int clash = 0;
        for (int i = 0; i < 100000; i++) if (ev.contains(Lab.trainSeed(BlockGame.ID, i))) clash++;
        check(clash == 0, "training seeds never equal evaluation seeds");
        // Evaluation never changes the model.
        BlockGame g = new BlockGame();
        Agent ag = new Agent(g.id(), g.adapter().featureNames(), 1);
        g.configure(ag);
        Lab.Trainer<BlockState> tr = new Lab.Trainer<>(g, 0);
        ag.mode = Agent.Mode.TRAINING;
        for (int k = 0; k < 20; k++) tr.episode(ag, g.maxSteps(), null);
        float[] before = ag.model.w.clone();
        long upd = ag.model.updates;
        Lab.Report r1 = Lab.evaluate(g, "x", ag, 10, g.maxSteps(), null);
        Lab.Report r2 = Lab.evaluate(g, "x", ag, 10, g.maxSteps(), null);
        check(Arrays.equals(before, ag.model.w) && upd == ag.model.updates && ag.mode == Agent.Mode.TRAINING, "evaluation leaves weights and mode alone");
        check(r1.meanScore == r2.meanScore, "evaluation is repeatable");
        check(tr.lastScore() >= 0, "trainer reports episode score");
        // Simulator experience records the env's reward and its source.
        GameProfile p = Adapters.builtInProfiles().get(2);
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "labtests" + System.nanoTime());
        ExperienceStore st = new ExperienceStore(dir);
        KnownReward kr = new KnownReward();
        EpisodeRecorder rec = new EpisodeRecorder(st, p, "USER", kr);
        BlockEnv env = new BlockEnv();
        BlockState s = env.reset(new Random(2));
        rec.begin(g.perception().observe(s, p), "t");
        GameAdapter.Candidate c = g.adapter().candidates(s).get(0);
        Env.Outcome<BlockState> o = env.step(s, c.action);
        kr.next(o.reward, "Block Puzzle env: " + o.note, o.done, o.win);
        rec.step(GameAction.logical(c.label), 0, 0, g.perception().observe(o.next, p));
        rec.end("test", null);
        List<Experience> es = st.recent(p.id, 10);
        check(es.size() == 3 && es.get(1).reward == o.reward && es.get(1).rewardSource.startsWith("Block Puzzle env"), "sim experience stored with reward source");
        check("block-puzzle".equals(p.id) && p.pace == GameProfile.Pace.TURN_BASED
                && Adapters.builtInProfiles().get(3).pace == GameProfile.Pace.REAL_TIME, "profiles: one turn-based, one real-time");
    }
}
