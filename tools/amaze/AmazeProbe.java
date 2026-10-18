import com.nickrocks.pixelflowbot.amaze.*;
import com.nickrocks.pixelflowbot.vision.Frame;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Desktop check: java AmazeProbe [-draw out.png] frame.png ... prints what the bot reads, optionally draws it. */
public class AmazeProbe {
    public static void main(String[] a) throws Exception {
        String draw = null;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("-draw")) { draw = a[++i]; continue; }
            BufferedImage im = ImageIO.read(new File(a[i]));
            int w = im.getWidth(), h = im.getHeight();
            Frame f = new Frame(w, h, im.getRGB(0, 0, w, h, null, 0, w));
            AmazeVision.DEBUG = System.getenv("ADEBUG") != null;
            AmazeVision.Board b = new AmazeVision().read(f);
            int free = b.error == null ? AmazeSolver.freeArrows(b).size() : 0;
            int stuck = b.error == null ? AmazeSolver.stuckAfterClearing(b) : -1;
            System.out.println(a[i] + ": " + b.summary() + (b.error != null ? "  ERROR " + b.error : "  free " + free + ", stuck after clearing " + stuck) + " (" + b.ms + " ms)");
            if (draw != null && b.owner != null) {
                Graphics2D g = im.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(Math.max(2, b.pitch / 8)));
                for (AmazeVision.Arrow ar : b.arrows) {
                    g.setColor(AmazeSolver.free(b, ar) ? new Color(0, 200, 120) : new Color(220, 40, 90));
                    for (int k = 1; k < ar.cells.length; k++)
                        g.drawLine((int) b.px(ar.cells[k - 1]), (int) b.py(ar.cells[k - 1]), (int) b.px(ar.cells[k]), (int) b.py(ar.cells[k]));
                    int r = (int) Math.max(4, b.pitch / 4);
                    g.fillOval((int) b.px(ar.head()) - r, (int) b.py(ar.head()) - r, 2 * r, 2 * r);
                }
                ImageIO.write(im, "png", new File(draw));
                draw = null;
            }
        }
    }
}
