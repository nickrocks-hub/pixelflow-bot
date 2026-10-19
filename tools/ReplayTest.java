import com.riderbot.app.core.RiderBrain;
import com.riderbot.app.core.RiderVision;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;

/**
 * Replays a raw rgb24 video (ffmpeg -f rawvideo -pix_fmt rgb24) through RiderBrain.
 * Usage: java ReplayTest frames.raw width height fps
 * Prints one line per frame: time, car found, grounded, angle, predicted landing time, decision.
 */
public class ReplayTest {
    public static void main(String[] a) throws Exception {
        String file = a[0];
        int w = Integer.parseInt(a[1]), h = Integer.parseInt(a[2]);
        double fps = Double.parseDouble(a[3]);
        RiderBrain brain = new RiderBrain(new RiderBrain.Params());
        byte[] buf = new byte[w * h * 3];
        int[] px = new int[w * h];
        long total = 0, found = 0, slowest = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 20))) {
            for (int f = 0; ; f++) {
                try { in.readFully(buf); } catch (java.io.EOFException e) { break; }
                for (int i = 0, j = 0; i < px.length; i++, j += 3)
                    px[i] = ((buf[j] & 0xFF) << 16) | ((buf[j + 1] & 0xFF) << 8) | (buf[j + 2] & 0xFF);
                long t0 = System.nanoTime();
                boolean hold = brain.onFrame(px, w, h, (long) (f * 1000 / fps));
                long dt = System.nanoTime() - t0;
                if (f > 30) slowest = Math.max(slowest, dt);
                total += dt;
                RiderVision.Car c = brain.lastCar;
                if (c != null) found++;
                System.out.printf("%4d %6.2f %s %s th=%7.1f T=%5s hgt=%5s u=%2d hold=%d  %s%n", f, f / fps,
                        c == null ? "----" : "car ", brain.lastGrounded ? "G" : "a", brain.getTheta(),
                        Double.isNaN(brain.lastT) ? "" : String.format("%.2f", brain.lastT),
                        Double.isNaN(brain.lastHeightOut) ? "" : String.format("%.0f", brain.lastHeightOut),
                        brain.lastUnder, hold ? 1 : 0, brain.status);
            }
        }
        System.err.printf("car found %d frames; avg %.2f ms/frame, slowest %.2f ms%n",
                found, total / 1e6 / Math.max(1, found), slowest / 1e6);
    }
}
