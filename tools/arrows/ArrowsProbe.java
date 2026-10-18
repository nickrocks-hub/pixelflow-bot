import com.nickrocks.pixelflowbot.core.arrows.ArrowsBoard;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Reads arrows-puzzle screenshots and checks the reading: every arrow found, one head each, and the
 * whole level clears when free arrows are removed one by one. Writes an overlay PNG next to each input.
 *   javac -d /tmp/ap $S/core/arrows/*.java tools/arrows/ArrowsProbe.java && java -cp /tmp/ap ArrowsProbe [-debug] tools/arrows/*.jpg
 */
public class ArrowsProbe {
    public static void main(String[] args) throws Exception {
        int bad = 0;
        for (String f : args) {
            if (f.equals("-debug")) { ArrowsBoard.DEBUG = true; continue; }
            BufferedImage im = ImageIO.read(new File(f));
            int w = im.getWidth(), h = im.getHeight();
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            long t0 = System.currentTimeMillis();
            ArrowsBoard b = ArrowsBoard.read(px, w, h);
            long ms = System.currentTimeMillis() - t0;
            List<ArrowsBoard.Arrow> plan = b.plan();
            System.out.printf("%s: %s · plan clears %d of %d · %d ms%n", f, b.summary(), plan.size(), b.arrows.size(), ms);
            if (b.problem != null || b.unreadable > 0 || plan.size() != b.arrows.size()) bad++;
            // Play it out on the image: erase arrows in plan order, re-read with the remembered lattice each time.
            BufferedImage sim = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            sim.getGraphics().drawImage(im, 0, 0, null);
            Graphics2D e = sim.createGraphics();
            e.setColor(new Color(sim.getRGB(Math.round(b.x0 - b.s * 0.5f), Math.round(b.y0 - b.s * 0.5f))));
            e.setStroke(new BasicStroke(b.s * 0.65f));
            int simBad = 0;
            for (int k = 0; k < plan.size(); k++) {
                ArrowsBoard.Arrow a = plan.get(k);
                for (int j = 1; j < a.nodes.size(); j++) {
                    int[] p = a.nodes.get(j - 1), q = a.nodes.get(j);
                    e.drawLine(Math.round(b.x0 + p[0] * b.s), Math.round(b.y0 + p[1] * b.s), Math.round(b.x0 + q[0] * b.s), Math.round(b.y0 + q[1] * b.s));
                }
                ArrowsBoard r = ArrowsBoard.read(sim.getRGB(0, 0, w, h, null, 0, w), w, h, b);
                boolean ok = k == plan.size() - 1 ? "no arrows found".equals(r.problem) : r.consistent() && r.arrows.size() == plan.size() - k - 1;
                if (!ok) { simBad++; System.out.println("  after " + (k + 1) + " taps: " + r.summary()); }
            }
            System.out.println("  played out: " + (simBad == 0 ? "board read correctly after every tap, empty at the end" : simBad + " bad readings"));
            if (simBad > 0) bad++;
            Graphics2D g = im.createGraphics();
            g.setStroke(new BasicStroke(5));
            g.setFont(new Font("SansSerif", Font.BOLD, 22));
            for (int i = 0; i < b.arrows.size(); i++) {
                ArrowsBoard.Arrow a = b.arrows.get(i);
                g.setColor(a.free ? Color.GREEN : Color.RED);
                for (int k = 1; k < a.nodes.size(); k++) {
                    int[] p = a.nodes.get(k - 1), q = a.nodes.get(k);
                    g.drawLine(Math.round(b.x0 + p[0] * b.s), Math.round(b.y0 + p[1] * b.s), Math.round(b.x0 + q[0] * b.s), Math.round(b.y0 + q[1] * b.s));
                }
                int[] hd = a.head();
                g.fillOval(Math.round(b.x0 + hd[0] * b.s) - 8, Math.round(b.y0 + hd[1] * b.s) - 8, 16, 16);
                g.setColor(Color.BLUE);
                g.drawString(String.valueOf(plan.indexOf(a) + 1), a.tapX - 8, a.tapY - 6);
            }
            g.dispose();
            ImageIO.write(im, "png", new File(f.replaceAll("\\.\\w+$", "") + "_read.png"));
        }
        System.out.println(bad == 0 ? "all readings consistent" : bad + " screenshot(s) with problems");
        if (bad > 0) System.exit(1);
    }
}
