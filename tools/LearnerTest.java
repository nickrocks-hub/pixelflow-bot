import com.aigame.subwaysurfers.core.Learner;
import com.aigame.subwaysurfers.core.SurferBrain;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the crash learner: blame rules, save/load, revert-to-best, and (optionally)
 * a real crash replayed from a log zip.  Usage: java LearnerTest [log.zip crashMs]
 */
public class LearnerTest {
    static SurferBrain.Record rec(long t, SurferBrain.Event e, double tti) throws Exception {
        Constructor<SurferBrain.Record> c = SurferBrain.Record.class.getDeclaredConstructor(long.class, SurferBrain.Event.class, double.class);
        c.setAccessible(true);
        return c.newInstance(t, e, tti);
    }
    static int fails = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "ok   " : "FAIL ") + name); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        long T = 100_000;
        check("lane change", Learner.blame(List.of(rec(T - 600, SurferBrain.Event.DODGE, 500)), T) == Learner.Cause.LANE_CHANGE);
        check("jump early", Learner.blame(List.of(rec(T - 900, SurferBrain.Event.JUMP_BARRIER, 400)), T) == Learner.Cause.JUMP_EARLY);
        check("jump late", Learner.blame(List.of(rec(T - 200, SurferBrain.Event.JUMP_BARRIER, 150)), T) == Learner.Cause.JUMP_LATE);
        check("misread", Learner.blame(List.of(rec(T - 400, SurferBrain.Event.JUMP_BARRIER, 380)), T) == Learner.Cause.MISREAD);
        check("boxed", Learner.blame(List.of(rec(T - 300, SurferBrain.Event.JUMP_BOXED, 300)), T) == Learner.Cause.BOXED_IN);
        check("hesitated", Learner.blame(List.of(rec(T - 300, SurferBrain.Event.WAIT_TRAIN, 300)), T) == Learner.Cause.HESITATED);
        check("unseen", Learner.blame(List.of(rec(T - 5000, SurferBrain.Event.DODGE, 300)), T) == Learner.Cause.UNSEEN);

        SurferBrain.Params base = new SurferBrain.Params();
        Learner l = new Learner(base);
        int c0 = l.targetClearMs;
        System.out.println("  " + l.learn(List.of(rec(T - 600, SurferBrain.Event.DODGE, 500)), T, 40));
        check("lane change raises targetClear", l.targetClearMs > c0);
        Learner l2 = Learner.load(l.serialize(), base);
        check("save/load round trip", l2.serialize().equals(l.serialize()));
        // drift then revert: best run 40 s with original settings
        for (int i = 0; i < 3; i++) System.out.println("  " + l.learn(List.of(), T, 5));
        check("reverted to best after 3 bad runs", l.reactMs == base.reactMs && l.targetClearMs > c0 - 1);
        System.out.println(l.summary());
        check("garbage state -> fresh", Learner.load("nonsense", base).runs == 0);

        if (a.length >= 2) {
            long crash = Long.parseLong(a[1]);
            SurferBrain brain = new SurferBrain(new SurferBrain.Params());
            try (ZipFile z = new ZipFile(a[0])) {
                List<ZipEntry> fr = new ArrayList<>();
                z.stream().filter(e -> e.getName().startsWith("frames/")).forEach(fr::add);
                fr.sort((x, y) -> x.getName().compareTo(y.getName()));
                for (ZipEntry e : fr) {
                    long t = Long.parseLong(e.getName().replaceAll("\\D", ""));
                    if (t > crash) break;
                    BufferedImage im = ImageIO.read(new ByteArrayInputStream(z.getInputStream(e).readAllBytes()));
                    int w = im.getWidth(), h = im.getHeight();
                    int[] px = im.getRGB(0, 0, w, h, null, 0, w);
                    for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
                    brain.onFrame(px, w, h, t);
                }
            }
            for (SurferBrain.Record r : brain.history()) System.out.println("  hist " + r.t + " " + r.e + " tti=" + (int) r.ownTti);
            Learner real = new Learner(new SurferBrain.Params());
            System.out.println("real crash: " + Learner.blame(brain.history(), crash) + " -> " + real.learn(brain.history(), crash, 35));
        }
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
    }
}
