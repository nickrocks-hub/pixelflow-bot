import com.aigame.subwaysurfers.core.SurferBrain;
import com.aigame.subwaysurfers.core.TrackVision;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;

/**
 * Replays a raw rgb24 recording through SurferBrain (no touches happen, so the
 * brain's lane belief follows what the vision sees the player doing).
 * Make the input with:  ffmpeg -i rec.mp4 -vf fps=30,scale=360:-2 -f rawvideo -pix_fmt rgb24 rec.raw
 * Usage: java Replay rec.raw 360 840 30 [everyNth]
 */
public class Replay {
    public static void main(String[] a) throws Exception {
        int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]);
        double fps = Double.parseDouble(a[3]);
        int every = a.length > 4 ? Integer.parseInt(a[4]) : 1;
        SurferBrain brain = new SurferBrain(new SurferBrain.Params());
        byte[] buf = new byte[w * h * 3];
        int[] px = new int[w * h];
        long total = 0, slow = 0; int n = 0, ok = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(a[0]), 1 << 20))) {
            for (int f = 0; ; f++) {
                try { in.readFully(buf); } catch (java.io.EOFException e) { break; }
                for (int i = 0, j = 0; i < px.length; i++, j += 3)
                    px[i] = ((buf[j] & 0xFF) << 16) | ((buf[j + 1] & 0xFF) << 8) | (buf[j + 2] & 0xFF);
                long t0 = System.nanoTime();
                SurferBrain.Move m = brain.onFrame(px, w, h, (long) (f * 1000 / fps));
                long dt = System.nanoTime() - t0;
                if (f > 20) { total += dt; n++; slow = Math.max(slow, dt); }
                TrackVision.Result r = brain.last;
                if (r.ok) ok++;
                if (System.getenv("VPLOG") != null) System.out.printf("VP %d %.1f %.1f P=%.0f sharp=%.0f%n", f, r.vpX, r.vpY, brain.vision.gridP, r.vpSharpness);
                if (f % every == 0 || m != SurferBrain.Move.NONE) {
                    System.out.printf("%4d %6.2fs %-5s v=%.2f tti=%s/%s/%s %s  %s%n", f, f / fps, m == SurferBrain.Move.NONE ? "" : m,
                            brain.getSpeed(), fmt(brain.laneTti[0], brain.laneTall[0]), fmt(brain.laneTti[1], brain.laneTall[1]),
                            fmt(brain.laneTti[2], brain.laneTall[2]), r.ok ? "" : r.why,
                            String.format("c=%.0f/%.0f/%.0f %s", brain.laneReward[0], brain.laneReward[1], brain.laneReward[2], brain.status));
                }
            }
        }
        System.err.printf("tracks found in %d frames; avg %.1f ms, slowest %.1f ms%n", ok, total / 1e6 / Math.max(1, n), slow / 1e6);
    }

    static String fmt(double t, boolean tall) {
        if (Double.isInfinite(t)) return "  -  ";
        return String.format("%4.0f%s", Math.min(9999, t), tall ? "T" : "l");
    }
}
