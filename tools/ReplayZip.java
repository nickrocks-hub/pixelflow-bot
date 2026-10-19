import com.aigame.subwaysurfers.core.SurferBrain;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Replays the frames inside a log zip saved by the app (Download/AI GAME SUBWAY SURFERS/*.zip)
 * through SurferBrain and prints what it would do on each frame.
 * Usage: java ReplayZip subway_....zip
 */
public class ReplayZip {
    public static void main(String[] a) throws Exception {
        try (ZipFile z = new ZipFile(a[0])) {
            List<ZipEntry> frames = new ArrayList<>();
            z.stream().filter(e -> e.getName().startsWith("frames/") && e.getName().endsWith(".jpg")).forEach(frames::add);
            frames.sort((x, y) -> x.getName().compareTo(y.getName()));
            SurferBrain brain = new SurferBrain(new SurferBrain.Params());
            for (ZipEntry e : frames) {
                long t = Long.parseLong(e.getName().replaceAll("\\D", ""));
                BufferedImage im = ImageIO.read(new ByteArrayInputStream(z.getInputStream(e).readAllBytes()));
                int w = im.getWidth(), h = im.getHeight();
                int[] px = im.getRGB(0, 0, w, h, null, 0, w);
                for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
                SurferBrain.Move m = brain.onFrame(px, w, h, t);
                System.out.println(t + " " + brain.logLine(m));
            }
        }
    }
}
