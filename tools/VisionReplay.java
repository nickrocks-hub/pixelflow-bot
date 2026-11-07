import com.nickrocks.aibot.core.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * Runs the screen reader over a folder of frames (PNG/JPG, in name order) from a screen recording
 * of the game, at the capture size the phone uses, and writes annotated pictures plus one text line
 * per frame. Usage: java VisionReplay <framesDir> <fps> <outDir> [everyNth] [width]
 */
public class VisionReplay {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        float fps = Float.parseFloat(a[1]);
        File out = new File(a[2]); out.mkdirs();
        int every = a.length > 3 ? Integer.parseInt(a[3]) : 10;
        int W = a.length > 4 ? Integer.parseInt(a[4]) : 480;
        File[] fs = dir.listFiles((d, n) -> n.matches("f\\d+\\.(png|jpg)"));
        Arrays.sort(fs);
        GameStateObserver obs = new GameStateObserver(new ScreenLayout());
        int i = 0;
        long totalNs = 0;
        int[] counts = new int[6];
        for (File f : fs) {
            BufferedImage src = ImageIO.read(f);
            int H = Math.round((float) W * src.getHeight() / src.getWidth());
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, W, H, null);
            g.dispose();
            int[] px = img.getRGB(0, 0, W, H, null, 0, W);
            for (int k = 0; k < px.length; k++) px[k] &= 0xFFFFFF;
            long t = Math.round(i * 1000 / fps);
            long t0 = System.nanoTime();
            Observation o = obs.observe(px, W, H, t);
            totalNs += System.nanoTime() - t0;
            if (o.player != null) counts[0]++;
            counts[1] += o.enemies.size(); counts[2] += o.shots.size(); counts[3] += o.ownShots.size();
            System.out.println(line(f.getName(), o));
            if (i % every == 0) draw(img, o, obs, new File(out, f.getName().replace(".jpg", ".png")));
            i++;
        }
        System.out.printf(Locale.US, "frames %d, player found %d, avg enemies %.2f, enemy shots %.2f, own shots %.2f, avg %.2f ms/frame%n",
                i, counts[0], counts[1] / (float) i, counts[2] / (float) i, counts[3] / (float) i, totalNs / 1e6 / i);
    }

    static String line(String name, Observation o) {
        StringBuilder b = new StringBuilder(name);
        b.append(String.format(Locale.US, " hud=%s paused=%s hurt=%s cut=%s cam=%.0f,%.0f hp=%d/%d ult=%.2f%s",
                o.hudVisible ? 1 : 0, o.paused ? 1 : 0, o.hurtFlash ? 1 : 0, o.sceneCut ? 1 : 0, o.camDx, o.camDy, o.hp, o.hpMax, o.ultFill, o.ultReady ? "!" : ""));
        if (o.map.ok) b.append(String.format(Locale.US, " map=(%d,%d) v%d u%d", o.map.curX, o.map.curY, o.map.visited.size(), o.map.unvisited.size()));
        if (o.player != null) b.append(String.format(Locale.US, " P(%.0f,%.0f v%.0f,%.0f)", o.player.x, o.player.y, o.player.vx, o.player.vy));
        b.append(" E[");
        for (Observation.Thing t : o.enemies) b.append(String.format(Locale.US, "%s#%d(%.0f,%.0f %s) ", t.kind == Observation.Kind.BOSS ? "B" : "", t.id, t.x, t.y, t.sig));
        b.append("] S").append(o.shots.size()).append(" O").append(o.ownShots.size()).append(" I").append(o.pickups.size()).append(" P").append(o.props.size());
        return b.toString();
    }

    static void draw(BufferedImage img, Observation o, GameStateObserver obs, File file) throws Exception {
        int W = img.getWidth(), H = img.getHeight();
        BufferedImage big = new BufferedImage(W * 2, H * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.drawImage(img, 0, 0, W * 2, H * 2, null);
        // walk grid
        if (o.walk != null) {
            g.setColor(new Color(0, 255, 0, 40));
            for (int y = 0; y < o.gh; y++) for (int x = 0; x < o.gw; x++)
                if (o.walk[y * o.gw + x]) g.fillRect(x * o.cell * 2, y * o.cell * 2, o.cell * 2, o.cell * 2);
        }
        if (obs.debugBlocks != null) {
            g.setColor(new Color(255, 255, 0, 90));
            for (int y = 0; y < obs.debugBh; y++) for (int x = 0; x < obs.debugBw; x++)
                if (obs.debugBlocks[y * obs.debugBw + x] == 2) g.fillRect(x * 4, y * 4, 4, 4);
        }
        g.setStroke(new BasicStroke(2));
        box(g, o.player, Color.GREEN, "P");
        for (Observation.Thing t : o.enemies) box(g, t, t.kind == Observation.Kind.BOSS ? Color.ORANGE : Color.RED, (t.kind == Observation.Kind.BOSS ? "BOSS " : "") + t.sig);
        for (Observation.Thing t : o.shots) box(g, t, Color.MAGENTA, "!");
        for (Observation.Thing t : o.ownShots) box(g, t, Color.CYAN, "o");
        for (Observation.Thing t : o.pickups) box(g, t, Color.YELLOW, "$");
        for (Observation.Thing t : o.props) box(g, t, Color.GRAY, "");
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString(line(file.getName(), o).substring(0, Math.min(150, line(file.getName(), o).length())), 10, H * 2 - 10);
        g.dispose();
        ImageIO.write(big, "png", file);
    }

    static void box(Graphics2D g, Observation.Thing t, Color c, String s) {
        if (t == null) return;
        g.setColor(c);
        int x = Math.round((t.x - t.w / 2) * 2), y = Math.round((t.y - t.h / 2) * 2);
        g.drawRect(x, y, Math.round(t.w * 2), Math.round(t.h * 2));
        g.drawLine(Math.round(t.x * 2), Math.round(t.y * 2), Math.round((t.x + t.vx * 0.25f) * 2), Math.round((t.y + t.vy * 0.25f) * 2));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString(s, x, y - 2);
    }
}
