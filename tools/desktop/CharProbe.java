import com.nickrocks.pixelflowbot.vision.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Prints slots, queue (with tap x) and belt counter for each image. */
public class CharProbe {
    public static void main(String[] a) throws Exception {
        for (String p : a) {
            BufferedImage im = ImageIO.read(new File(p));
            int w = im.getWidth(), h = im.getHeight();
            com.nickrocks.pixelflowbot.vision.Frame f = new com.nickrocks.pixelflowbot.vision.Frame(w, h, im.getRGB(0, 0, w, h, null, 0, w));
            StringBuilder sb = new StringBuilder(new File(p).getName()).append(" | screen ").append(GameState.detectScreen(f)).append(" | slots ");
            for (CharReader.Character c : CharReader.readSlots(f)) sb.append(c == null ? "-" : c.code()).append(' ');
            sb.append("| queue ");
            for (CharReader.Character c : CharReader.readQueue(f)) sb.append(c.code()).append("@").append(Math.round(c.x)).append(' ');
            sb.append("| belt ").append(CharReader.readBeltFree(f));
            System.out.println(sb);
        }
    }
}
