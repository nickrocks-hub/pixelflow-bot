import com.aigame.subwaysurfers.core.SurferBrain;
import com.aigame.subwaysurfers.core.SurferBrain.Move;
import com.aigame.subwaysurfers.core.TrackVision;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-made scenes fed straight into SurferBrain.step(), one per case from the spec.
 * Frames are 360x840 at 60 fps; the world scrolls at 2.4 depth units / s.
 * Usage: java ScenarioTest
 */
public class ScenarioTest {
    static final int W = 360, H = 840;
    static final double VP_Y = 188;
    static int fails = 0;

    /** One obstacle in an absolute lane (-1..1). */
    static final class Ob {
        int lane; double d0; double speed; boolean train; long appear;
        Ob(int lane, double d0, double speed, boolean train) { this.lane = lane; this.d0 = d0; this.speed = speed; this.train = train; }
        double d(long t) { return d0 - speed * (t - appear) / 1000.0; }
    }

    static final class World {
        int lane = 0;                  // where the runner really is
        final List<Ob> obs = new ArrayList<>();
        final boolean[] alongside = new boolean[3];
        boolean frozen, ignoreSwipes;
        long laneMovesAt = -1; int laneTo;
    }

    static double rowFor(double d, TrackVision v) {
        double feet = H * v.runnerFeet;
        return VP_Y + (feet - VP_Y) / (1 + d);
    }

    /** Build the perception result the brain would get for this world at time t. */
    static TrackVision.Result scene(SurferBrain b, World wd, long t) {
        TrackVision v = b.vision;
        TrackVision.Result r = new TrackVision.Result();
        r.ok = true; r.vpX = 168; r.vpY = VP_Y;
        v.gridP = 303;                                   // ground camera
        v.motion = wd.frozen ? 0.5 : 20;
        v.bestCam = v.freeFit ? 99 : TrackVision.CAM[wd.lane + 1];
        for (int k = -2; k <= 2; k++) {
            TrackVision.Track tr = new TrackVision.Track();
            int a = wd.lane + k;
            tr.obstacleY = -1;
            if (a >= -1 && a <= 1) {
                Ob near = null;
                for (Ob o : wd.obs) if (o.lane == a && o.d(t) > 0.45 && (near == null || o.d(t) < near.d(t))) near = o;
                if (near != null && near.d(t) < 4) {
                    tr.obstacleY = (int) Math.round(rowFor(near.d(t), v));
                    double dTop = near.train ? near.d(t) + 2.5 : near.d(t) + 0.15;
                    tr.obstacleSpan = (int) Math.round(rowFor(near.d(t), v) - rowFor(dTop, v));
                    tr.railsResume = !near.train;
                }
                tr.sideBlocked = k != 0 && wd.alongside[a + 1];
            } else {
                tr.sideBlocked = true; tr.blockedAtStart = true;   // no track there: looks blocked
            }
            r.all.add(tr);
        }
        r.tracks.addAll(r.all.subList(1, 4));
        return r;
    }

    /** Run the world for ms milliseconds; returns the moves with their times. */
    static List<String> run(SurferBrain b, World wd, long from, long ms, long frameAge) {
        List<String> out = new ArrayList<>();
        for (long t = from; t < from + ms; t += 17) {
            // the game: apply delivered lane swipes after a short animation
            if (wd.laneMovesAt >= 0 && t >= wd.laneMovesAt) { wd.lane = wd.laneTo; wd.laneMovesAt = -1; }
            b.vision.freeFit = b.wantsFreeFit(t - frameAge);
            Move m = b.step(scene(b, wd, t - frameAge), H, t - frameAge, t, System.nanoTime());
            if (m != Move.NONE) {
                out.add(m + "@" + (t - from));
                if ((m == Move.LEFT || m == Move.RIGHT) && !wd.ignoreSwipes) {
                    wd.laneTo = Math.max(-1, Math.min(1, wd.lane + (m == Move.LEFT ? -1 : 1)));
                    wd.laneMovesAt = t + 60 + 200;
                }
            }
        }
        return out;
    }

    static SurferBrain brain() {
        SurferBrain.Params p = new SurferBrain.Params();
        SurferBrain b = new SurferBrain(p);
        b.simulateAckMs = 60;
        return b;
    }

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) fails++;
    }

    static long warm(SurferBrain b, World wd) { run(b, wd, 0, 600, 0); return 600; }

    public static void main(String[] a) {
        // 1. Clear path -> no input
        { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
          List<String> mv = run(b, wd, t, 3000, 0);
          check("clear path: no input", mv.isEmpty(), mv); }

        // 2. Train ahead in our lane, neighbours clear -> one lane change, in time
        { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
          Ob o = new Ob(0, 3.5, 2.4, true); o.appear = t; wd.obs.add(o);
          List<String> mv = run(b, wd, t, 2000, 0);
          boolean one = mv.size() == 1 && (mv.get(0).startsWith("LEFT") || mv.get(0).startsWith("RIGHT"));
          long at = one ? Long.parseLong(mv.get(0).split("@")[1]) : -1;
          double ttiAtMove = (3.5 - 2.4 * at / 1000.0) / 2.4 * 1000;
          check("train ahead: one lane change before it arrives", one && ttiAtMove > 400 && wd.lane != 0,
                  mv + String.format(" (train %.0f ms away at the swipe, runner now in %d)", ttiAtMove, wd.lane)); }

        // 3. Low barrier -> jump once, not too early
        { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
          Ob o = new Ob(0, 3.0, 2.4, false); o.appear = t; wd.obs.add(o);
          List<String> mv = run(b, wd, t, 2000, 0);
          long at = mv.size() == 1 && mv.get(0).startsWith("JUMP") ? Long.parseLong(mv.get(0).split("@")[1]) : -1;
          double tti = (3.0 - 2.4 * at / 1000.0) / 2.4 * 1000;
          check("low barrier: one jump, timed (lands 0.2-0.6 s before reaching it)", at >= 0 && tti > 250 && tti < 650,
                  mv + String.format(" (barrier %.0f ms away at the swipe)", tti)); }

        // 4. Overhead barrier -> roll (barrier setting = roll)
        { SurferBrain b = brain(); b.p.jumpLowObstacles = false; World wd = new World(); long t = warm(b, wd);
          Ob o = new Ob(0, 3.0, 2.4, false); o.appear = t; wd.obs.add(o);
          List<String> mv = run(b, wd, t, 2000, 0);
          check("overhead barrier (roll setting): one roll", mv.size() == 1 && mv.get(0).startsWith("ROLL"), mv); }

        // 5. Blocked adjacent lane -> never swipe into it
        { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
          wd.alongside[0] = true;                                    // train alongside on the left
          Ob o = new Ob(0, 3.5, 2.4, true); o.appear = t; wd.obs.add(o);
          List<String> mv = run(b, wd, t, 2000, 0);
          check("blocked left lane: dodges right, never left", !mv.isEmpty() && mv.get(0).startsWith("RIGHT") && mv.stream().noneMatch(s -> s.startsWith("LEFT")), mv); }

        // 5b. Both neighbours blocked -> no swipe into them; last-resort jump
        { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
          wd.alongside[0] = true; wd.alongside[2] = true;
          Ob o = new Ob(0, 3.5, 2.4, true); o.appear = t; wd.obs.add(o);
          List<String> mv = run(b, wd, t, 2000, 0);
          check("boxed in: no unsafe swipe, one jump", mv.size() == 1 && mv.get(0).startsWith("JUMP"), mv); }

        // 6. Two-lane movement -> two separate, registered swipes
        { SurferBrain b = brain(); World wd = new World(); wd.lane = -1;
          long t = warm(b, wd);
          Ob own = new Ob(-1, 3.5, 2.4, true); own.appear = t; wd.obs.add(own);
          Ob mid = new Ob(0, 4.6, 2.4, true); mid.appear = t; wd.obs.add(mid);   // middle: passable, not a place to stay
          List<String> mv = run(b, wd, t, 2500, 0);
          long n = mv.stream().filter(s -> s.startsWith("RIGHT")).count();
          boolean spaced = n >= 2 && Long.parseLong(mv.get(1).split("@")[1]) - Long.parseLong(mv.get(0).split("@")[1]) >= b.p.laneCooldownMs;
          check("two-lane move: two distinct RIGHT swipes, spaced", n == 2 && spaced && wd.lane == 1, mv + " runner in " + wd.lane); }

        // 7. Ignored gesture -> detected, state corrected, replanned
        { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
          wd.ignoreSwipes = true;
          Ob o = new Ob(0, 4.0, 2.4, true); o.appear = t; wd.obs.add(o);
          List<String> mv = run(b, wd, t, 1300, 0);
          String notes = b.drainNotes();
          boolean failSeen = b.history().stream().anyMatch(r -> r.e == SurferBrain.Event.LANE_FAILED);
          check("ignored swipe: failure detected, lane corrected, swipe re-planned", failSeen && b.lane == 0 && mv.size() >= 2, mv + " lane=" + b.lane); }

        // 8. Stale frame -> no decision acted on
        { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
          Ob o = new Ob(0, 2.5, 2.4, true); o.appear = t; wd.obs.add(o);
          List<String> mv = run(b, wd, t, 800, 400);                // every frame 400 ms old
          check("stale frames: no swipes from old pictures", mv.isEmpty(), mv + " reason: " + b.lastReason); }

        // 9. Faster approach (game sped up) -> jumps a barrier from further away
        { double dSlow, dFast;
          { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
            Ob o = new Ob(0, 3.5, 2.4, false); o.appear = t; wd.obs.add(o);
            List<String> mv = run(b, wd, t, 2000, 0);
            dSlow = mv.isEmpty() ? -1 : o.d(t + Long.parseLong(mv.get(0).split("@")[1])); }
          { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
            Ob o = new Ob(0, 3.5, 4.0, false); o.appear = t; wd.obs.add(o);
            List<String> mv = run(b, wd, t, 2000, 0);
            dFast = mv.isEmpty() ? -1 : o.d(t + Long.parseLong(mv.get(0).split("@")[1])); }
          check("faster approach: jumps from further out (same time to impact)", dSlow > 0 && dFast > dSlow * 1.3,
                  String.format("slow at d=%.2f, fast at d=%.2f", dSlow, dFast)); }

        // 10. Game over -> no input
        { SurferBrain b = brain(); World wd = new World(); long t = warm(b, wd);
          run(b, wd, t, 2500, 0); t += 2500;
          wd.frozen = true;
          Ob o = new Ob(0, 2.0, 2.4, true); o.appear = t; wd.obs.add(o);
          List<String> mv = run(b, wd, t, 1500, 0);
          check("game over (frozen picture): no input, crash time recorded", mv.isEmpty() && b.crashAt > 0 && b.game == SurferBrain.Game.STOPPED,
                  mv + " crashAt=" + b.crashAt + " game=" + b.game); }

        System.out.println(fails == 0 ? "ALL SCENARIOS PASSED" : fails + " SCENARIO(S) FAILED");
        if (fails > 0) System.exit(1);
    }
}
