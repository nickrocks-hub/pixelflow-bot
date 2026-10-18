import com.nickrocks.pixelflowbot.vision.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/** Runs the app's Tracker over a sequence of frames and prints one line per frame. */
public class Timeline {
    public static void main(String[] a) throws Exception {
        Tracker t = new Tracker();
        for (String p : a) {
            BufferedImage im = ImageIO.read(new File(p));
            int w = im.getWidth(), h = im.getHeight();
            long t0 = System.nanoTime();
            GameState s = t.update(new Frame(w, h, im.getRGB(0, 0, w, h, null, 0, w)));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println(new File(p).getName() + " [" + ms + "ms] " + s.summary().replace("\n", " | "));
        }
        System.out.println("calibrations: " + t.calibrations);
    }
}
