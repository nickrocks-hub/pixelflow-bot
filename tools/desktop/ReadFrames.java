import com.nickrocks.pixelflowbot.vision.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/** Desktop check: runs the app's vision code on PNG frames. Usage: ReadFrames calib.png frame1.png ... */
public class ReadFrames {
    static Frame load(String p) throws Exception {
        BufferedImage im = ImageIO.read(new File(p));
        int w = im.getWidth(), h = im.getHeight();
        return new Frame(w, h, im.getRGB(0, 0, w, h, null, 0, w));
    }
    public static void main(String[] a) throws Exception {
        BoardReader.Calibration cal = BoardReader.calibrate(load(a[0]));
        System.out.println("CALIB " + cal + " box " + cal.x0 + "," + cal.x1 + "," + cal.y0 + "," + cal.y1);
        for (int i = 1; i < a.length; i++) {
            Frame f = load(a[i]);
            GameState s = GameState.read(f, cal);
            System.out.println("FRAME " + new File(a[i]).getName() + " | " + s.summary().replace("\n", " | "));
        }
    }
}
