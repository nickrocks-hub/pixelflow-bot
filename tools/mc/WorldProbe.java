import com.nickrocks.pixelflowbot.core.mc.McVision;
import com.nickrocks.pixelflowbot.vision.Frame;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/** Screen kind, health and trunks for each frame; writes an annotated copy to out/. */
public class WorldProbe {
    public static void main(String[] a) throws Exception {
        File out = new File(a[0]);
        out.mkdirs();
        for (int i = 1; i < a.length; i++) {
            File file = new File(a[i]);
            BufferedImage im = ImageIO.read(file);
            Frame f = MakeIcons.load(file);
            McVision.Screen s = McVision.screen(f);
            StringBuilder line = new StringBuilder(file.getName() + ": " + s);
            BufferedImage o = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = o.createGraphics();
            g.drawImage(im, 0, 0, null);
            float k = im.getWidth() / 2520f;
            if (s == McVision.Screen.WORLD) {
                line.append(" health ").append(McVision.health(f));
                boolean[] grid = McVision.barkGrid(f);
                g.setColor(new Color(255, 0, 255, 90));
                int gw = 210;
                for (int c = 0; c < grid.length; c++) if (grid[c]) g.fillRect(Math.round((c % gw) * 12 * k), Math.round((c / gw) * 12 * k), Math.round(12 * k), Math.round(12 * k));
                List<McVision.Trunk> ts = McVision.trunks(f);
                g.setStroke(new BasicStroke(5));
                g.setFont(new Font("SansSerif", Font.BOLD, 34));
                for (int t = 0; t < ts.size(); t++) {
                    McVision.Trunk tr = ts.get(t);
                    g.setColor(t == 0 ? Color.YELLOW : Color.CYAN);
                    g.drawRect(Math.round(tr.x0 * k), Math.round(tr.y0 * k), Math.round((tr.x1 - tr.x0) * k), Math.round((tr.y1 - tr.y0) * k));
                    g.fillOval(Math.round(tr.holdX * k) - 12, Math.round(tr.holdY * k) - 12, 24, 24);
                    g.drawString("#" + t + " w" + Math.round(tr.width), Math.round(tr.x0 * k) + 6, Math.round(tr.y0 * k) + 40);
                    line.append("\n   ").append(tr);
                }
            } else if (s != McVision.Screen.OTHER) {
                line.append("\n   ").append(McVision.craft(f).summary());
            }
            System.out.println(line);
            ImageIO.write(o, "jpg", new File(out, file.getName()));
        }
    }
}
