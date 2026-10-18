import com.nickrocks.pixelflowbot.core.*;
import com.nickrocks.pixelflowbot.game.nonogram.*;
import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.ModelStore;
import com.nickrocks.pixelflowbot.nono.NonoComAdapter;
import com.nickrocks.pixelflowbot.vision.Frame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Desktop tests for the shared agent core and the two Nonogram adapters (same Java as the app).
 *
 *   S=app/src/main/java/com/nickrocks/pixelflowbot
 *   javac -d /tmp/ct $S/core/*.java $S/learn/*.java $S/game/nonogram/*.java $S/nono/*.java $S/vision/Frame.java tools/core/CoreTests.java
 *   java -cp /tmp/ct CoreTests tools/nono/samples
 */
public class CoreTests {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("FAIL: " + what); }
    }

    public static void main(String[] args) throws Exception {
        File samples = new File(args.length > 0 ? args[0] : "tools/nono/samples");
        File tmp = Files.createTempDirectory("coretests").toFile();
        profiles(new File(tmp, "profiles"));
        actions();
        experience(new File(tmp, "exp"));
        guard();
        models(new File(tmp, "models"));
        nonoCom(samples, new File(tmp, "exp2"));
        nonoSim();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static void profiles(File dir) throws Exception {
        ProfileStore ps = new ProfileStore(dir);
        for (GameProfile p : Adapters.builtInProfiles()) ps.ensure(p);
        for (GameProfile p : Adapters.builtInProfiles()) ps.ensure(p);       // idempotent
        check(ps.list().size() == 2, "two built-in profiles");
        GameProfile com = ps.get("nonogram-com");
        check(com != null && com.version == 1 && "com.easybrain.nonogram".equals(com.packageName), "built-in nonogram-com v1");
        com.settings.put("objective", "fill the picture");
        GameProfile v2 = ps.save(com);
        check(v2.version == 2 && ps.versions("nonogram-com").size() == 2, "save bumps version and keeps history");
        check("fill the picture".equals(ps.get("nonogram-com").settings.get("objective")), "settings persist");
        check(ps.version("nonogram-com", 1).settings.isEmpty(), "old version unchanged");
        GameProfile u = new GameProfile();
        u.name = "Block Puzzle!";
        u.id = ps.freeId(u.name);
        u.packageName = "com.example.block";
        GameProfile su = ps.save(u);
        check("block-puzzle".equals(su.id) && su.support == GameProfile.Support.CALIBRATING, "user game starts calibrating");
        check("block-puzzle-2".equals(ps.freeId("Block Puzzle")), "free id avoids clash");
        check(!ps.delete("nonogram-com") && ps.delete("block-puzzle") && ps.get("block-puzzle") == null, "delete: built-in refused, user ok");
        check(GameProfile.fromJson(com.toJson()).settings.equals(com.settings), "json round trip");
    }

    static void actions() {
        GameAction t = GameAction.tap(10, 20, "t");
        check(t.checkBounds(100, 100) == null && t.checkBounds(5, 100) != null, "tap bounds");
        GameAction s = GameAction.swipe(0, 0, 50, 50, 300, "s");
        check(s.durationMs() == 300 && s.strokes().get(0).xy.length == 4, "swipe path/duration");
        check(GameAction.longPress(1, 1, 100, "l").durationMs() >= 500, "long press ≥ 500 ms");
        check(GameAction.hold(1, 1, 200, "h").strokes().get(0).continues, "hold keeps finger down");
        GameAction m = GameAction.multi(java.util.Arrays.asList(
                new GameAction.Stroke(new float[]{1, 1}, 0, 100, false), new GameAction.Stroke(new float[]{5, 5, 9, 9}, 50, 200, false)), "two");
        check(m.durationMs() == 250 && m.strokes().size() == 2, "multi-touch timing");
        boolean threw = false;
        try { new GameAction.Stroke(new float[]{1}, 0, 10, false); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "odd coordinate list rejected");
        check(m.toJson().contains("\"kind\":\"MULTI\""), "action json");
    }

    static void experience(File dir) throws Exception {
        ExperienceStore st = new ExperienceStore(dir);
        GameProfile p = Adapters.builtInProfiles().get(1);
        p.version = 3;
        RewardModel rm = (b, a, n) -> new RewardModel.Reward(n.intValue("x", 0) - b.intValue("x", 0), "x went up", false, false);
        EpisodeRecorder rec = new EpisodeRecorder(st, p, "PLAY", rm);
        rec.begin(obs(p, 0), "test");
        rec.step(GameAction.tap(1, 1, "a"), 1000, 40, obs(p, 2));
        rec.step(GameAction.tap(2, 2, "b"), 1100, 35, obs(p, 5));
        rec.end("solved", null);
        List<Experience> es = st.recent(p.id, 100);
        check(es.size() == 4, "4 lines: start, 2 steps, end");
        check(es.get(0).boundary == Experience.Boundary.START && es.get(3).boundary == Experience.Boundary.END
                && "solved".equals(es.get(3).endReason), "episode boundaries");
        Experience s2 = es.get(2);
        check(s2.reward == 3 && "x went up".equals(s2.rewardSource) && s2.actionLatencyMs == 35 && s2.profileVersion == 3
                && s2.step == 2 && s2.actionStartMs == 1100, "step fields: reward+source, timing, profile version");
        String raw = new String(Files.readAllBytes(st.file(p.id).toPath()));
        check(raw.contains("\"state\":{") && raw.contains("\"next\":{") && raw.contains("\"action\":{"), "state, action and next state stored");
        check(st.nextEpisode(p.id) == 2, "episode ids continue");
        ExperienceStore.Summary sum = st.summary(p.id);
        check(sum.episodes == 1 && sum.steps == 2 && sum.totalReward == 5, "summary");
        st.clear(p.id);
        check(st.recent(p.id, 10).isEmpty(), "clear");
    }

    static StateObservation obs(GameProfile p, int x) {
        StateObservation s = new StateObservation(p.id, p.version, System.currentTimeMillis());
        s.screen = StateObservation.Screen.GAMEPLAY;
        s.confidence = 0.9f;
        s.add("x", x, 1, StateObservation.Source.OBSERVED);
        return s;
    }

    static void guard() {
        GameProfile p = Adapters.builtInProfiles().get(1);
        SessionGuard g = new SessionGuard();
        SessionGuard.Status s = new SessionGuard.Status();
        s.captureOn = s.controlOn = true;
        s.ownPackage = "me";
        s.foregroundPackage = p.packageName;
        s.latest = obs(p, 1);
        check(g.check(p, s) == null, "all good → act");
        s.foregroundPackage = "me";
        check(g.check(p, s) == null, "own overlay in front is fine");
        s.foregroundPackage = "com.android.vending";
        check(g.check(p, s) != null && g.check(p, s).contains("another app"), "other app → pause");
        s.foregroundPackage = p.packageName;
        s.latest.confidence = 0.4f;
        check(g.check(p, s) != null && g.check(p, s).contains("not sure"), "low confidence → pause");
        s.latest.confidence = 0.9f;
        s.latest.screen = StateObservation.Screen.BLOCKED;
        check(g.check(p, s) != null && g.check(p, s).contains("unrelated"), "ad/purchase screen → pause");
        s.latest.screen = StateObservation.Screen.MENU;
        check(g.check(p, s) != null, "menu → pause");
        s.latest.screen = StateObservation.Screen.GAMEPLAY;
        s.captureOn = false;
        check("screen capture is off".equals(g.check(p, s)), "capture lost → pause");
        s.captureOn = true;
        s.controlOn = false;
        check("the tap service is off".equals(g.check(p, s)), "control lost → pause");
        s.controlOn = true;
        s.actionsWithoutEffect = 4;
        check(g.check(p, s) != null, "no-effect loop → pause");
        s.actionsWithoutEffect = 0;
        s.stopRequested = true;
        check("stopped by you".equals(g.check(p, s)), "stop → pause");
    }

    static void models(File dir) throws Exception {
        ModelStore ms = new ModelStore(dir, "t");
        Agent a = new Agent("t", new String[]{"a", "b"}, 1);
        a.model.w[0] = 1f;
        int v1 = ms.saveVersion(a, "good").version;
        ms.markStable(v1);
        a.model.w[0] = -7f;                       // a bad update
        ms.saveVersion(a, "bad");
        check(ms.stableVersion() == v1, "stable version remembered");
        int back = ms.rollbackToStable(a);
        check(back == v1 && a.model.w[0] == 1f, "rollback restores the stable weights");
        Agent b = new Agent("t", new String[]{"a", "b"}, 2);
        ms.loadLatest(b);
        check(b.model.w[0] == 1f, "rollback also becomes the autosave");
        boolean threw = false;
        try { ms.markStable(99); } catch (java.io.IOException e) { threw = true; }
        check(threw, "can't mark a missing version stable");
    }

    static Frame load(File f) throws Exception {
        BufferedImage im = ImageIO.read(f);
        int w = im.getWidth(), h = im.getHeight();
        return new Frame(w, h, im.getRGB(0, 0, w, h, null, 0, w));
    }

    static void nonoCom(File samples, File expDir) throws Exception {
        GameProfile p = Adapters.builtInProfiles().get(1);
        p.version = 1;
        NonoComAdapter ad = new NonoComAdapter();
        StateObservation ch = ad.observe(load(new File(samples, "challenge_10x10.png")), p);
        check(ch.screen == StateObservation.Screen.GAMEPLAY && ch.confidence > 0.8f, "challenge: gameplay, confident (" + ch.summary() + ")");
        check("10x10".equals(ch.value("grid", "")) && "6 2".equals(ch.value("row 2", "")) && "1 4 2".equals(ch.value("col 8", "")), "challenge: clues");
        check(ch.count(StateObservation.Source.HIDDEN) == 0, "challenge: nothing hidden");
        StateObservation l8 = ad.observe(load(new File(samples, "level8_frozen.png")), p);
        check(l8.count(StateObservation.Source.HIDDEN) == 4 && l8.fact("col 3").source == StateObservation.Source.HIDDEN, "level 8: 4 frozen clues are HIDDEN");
        check(l8.intValue("hearts", -1) == 3, "level 8: 3 hearts seen");
        // Not a game: a blank white frame.
        int[] px = new int[1080 * 2520];
        java.util.Arrays.fill(px, 0xFFFFFFFF);
        StateObservation blank = ad.observe(new Frame(1080, 2520, px), p);
        check(blank.screen == StateObservation.Screen.UNKNOWN && blank.confidence == 0f, "blank screen: unknown, zero confidence");
        SessionGuard.Status st = new SessionGuard.Status();
        st.captureOn = st.controlOn = true;
        st.latest = blank;
        check(new SessionGuard().check(p, st) != null, "guard refuses to act on a non-game screen");
        // Rewards from the adapter's own signals.
        StateObservation more = NonoComAdapter.fromReading((com.nickrocks.pixelflowbot.nono.NonoVision.Reading) ch.detail, p);
        more.add("filled", 0, 1, StateObservation.Source.OBSERVED);   // (first match wins: same as before)
        RewardModel.Reward same = ad.reward(ch, GameAction.macro(1, "x"), more);
        check(same.value == 0 && same.source.equals("no visible change"), "no change → 0 reward, says so");
        StateObservation lost = copyWith(ch, p, "hearts", ch.intValue("hearts", 3) - 1);
        RewardModel.Reward lr = ad.reward(ch, GameAction.macro(1, "x"), lost);
        check(lr.value == NonoComAdapter.R_HEART && lr.source.contains("heart"), "lost heart → penalty with source");
        StateObservation done = new StateObservation(p.id, 1, 0);
        done.screen = StateObservation.Screen.LEVEL_COMPLETE;
        RewardModel.Reward win = ad.reward(ch, GameAction.macro(1, "x"), done);
        check(win.episodeEnd && win.win && win.value == NonoComAdapter.R_COMPLETE, "level complete → episode end, win");
        // Record a real-looking session.
        ExperienceStore es = new ExperienceStore(expDir);
        EpisodeRecorder rec = new EpisodeRecorder(es, p, "SOLVER", ad);
        rec.begin(ch, "challenge");
        rec.step(GameAction.macro(5, "filled rows 1-5"), 1, 2, lost);
        rec.end("paused: test", null);
        check(es.recent(p.id, 10).size() == 3, "nonogram.com session recorded");
    }

    static StateObservation copyWith(StateObservation s, GameProfile p, String key, int value) {
        StateObservation o = new StateObservation(p.id, p.version, s.time + 1);
        o.screen = s.screen;
        o.confidence = s.confidence;
        o.add(key, value, 1, StateObservation.Source.OBSERVED);
        for (StateObservation.Fact f : s.facts()) if (!f.key.equals(key)) o.add(f.key, f.value, f.confidence, f.source);
        return o;
    }

    static void nonoSim() {
        GameProfile p = Adapters.builtInProfiles().get(0);
        Puzzle pz = new PuzzleGenerator().get(Puzzle.Split.EVAL, 5, 0);
        Board b = Board.of(pz);
        StateObservation s = new NonoSimAdapter().observe(b.observe(4), p);
        check(s.confidence == 1f && "25".equals(s.value("unknown", "")) && "false".equals(s.value("solved", "")), "built-in board observed exactly");
        check(!s.toJson().contains("solution"), "no solution in the observation");
    }
}
