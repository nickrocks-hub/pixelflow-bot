import com.aigame.subwaysurfers.core.TrackVision;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws what TrackVision sees on still frames.
 * Usage: java DebugFrames out.png in1.png in2.png ...  (frames side by side)
 */
public class DebugFrames {
    public static void main(String[] a) throws Exception {
        int n = a.length - 1;
        BufferedImage first = ImageIO.read(new File(a[1]));
        int w = first.getWidth(), h = first.getHeight();
        BufferedImage out = new BufferedImage(w * n, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        TrackVision v = new TrackVision();
        for (int k = 0; k < n; k++) {
            BufferedImage im = ImageIO.read(new File(a[k + 1]));
            int[] px = im.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
            long t0 = System.nanoTime();
            TrackVision.Result r = v.analyse(px, w, h);
            long dt = System.nanoTime() - t0;
            int ox = k * w;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int dim = ((c >> 16 & 0xFF) / 3) << 16 | ((c >> 8 & 0xFF) / 3) << 8 | (c & 0xFF) / 3;
                out.setRGB(ox + x, y, v.isRidge(x, y) ? 0x887700 : dim);
            }
            double yn = h * v.rowNear, yf = h * v.rowFar;
            g.setStroke(new BasicStroke(1f));
            for (TrackVision.Line l : r.lines) {
                g.setColor(Color.CYAN);
                g.drawLine(ox + (int) l.xAt(h * v.bandBottom, yn, yf), (int) (h * v.bandBottom),
                        ox + (int) l.xAt(r.vpY, yn, yf), (int) r.vpY);
            }
            int ti = 0;
            for (TrackVision.Track t : r.tracks) {
                g.setColor(new Color[]{Color.GREEN, Color.MAGENTA, Color.ORANGE, Color.WHITE}[ti++ % 4]);
                g.setStroke(new BasicStroke(3f));
                int fx = (int) t.centerAtFeet;
                g.drawLine(ox + fx - 8, (int) (h * v.runnerFeet), ox + fx + 8, (int) (h * v.runnerFeet));
                if (t.obstacleY >= 0) {
                    int xl = (int) t.left.xAt(t.obstacleY, yn, yf), xr = (int) t.right.xAt(t.obstacleY, yn, yf);
                    g.drawLine(ox + xl, t.obstacleY, ox + xr, t.obstacleY);
                    int ye = t.obstacleY - t.obstacleSpan;
                    g.setStroke(new BasicStroke(1f));
                    g.drawLine(ox + (xl + xr) / 2, t.obstacleY, ox + (xl + xr) / 2, ye);
                    g.drawString((t.railsResume ? "low " : "tall ") + t.obstacleSpan + (t.blockedAtStart ? " AT" : ""),
                            ox + xl, t.obstacleY + 14);
                }
            }
            g.setColor(Color.RED);
            g.fillOval(ox + (int) r.vpX - 4, (int) r.vpY - 4, 8, 8);
            g.setColor(Color.WHITE);
            g.drawLine(ox, (int) (h * v.scanStart), ox + w, (int) (h * v.scanStart));
            g.drawString(String.format("P%.0f sc %.0f/%.0f/%.0f/%.0f/%.0f ", v.gridP, v.trackScore[0], v.trackScore[1], v.trackScore[2], v.trackScore[3], v.trackScore[4]), ox + 4, h - 20);
            g.drawString(new File(a[k + 1]).getName() + " " + (r.ok ? "ok" : r.why) + " " + dt / 1000000 + "ms", ox + 4, h - 6);
            g.setColor(Color.DARK_GRAY);
            g.drawLine(ox + w - 1, 0, ox + w - 1, h);
        }
        ImageIO.write(out, "png", new File(a[0]));
    }
}
