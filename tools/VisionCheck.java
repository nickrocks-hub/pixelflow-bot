import com.nickrocks.aibot.core.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Regression check of the screen reader on real frames from a recording of the game (480x432).
 * Each frame is read on its own (no tracking history), so this checks the HUD, the minimap and
 * finding the cowboy. Positions were checked by eye on the annotated replay pictures.
 */
public class VisionCheck {
    // name, hud, paused, hp, hpMax, visited rooms on map, player x, y (-1 = not checked)
    static final Object[][] CASES = {
            {"f0101.png", true, false, 6, 6, 1, 224, 232},
            {"f0221.png", true, false, 4, 6, 1, 213, 243},
            {"f0331.png", true, false, 3, 6, 2, 363, 155},
            {"f0421.png", true, false, 3, 6, 2, 266, 301},
            {"f0581.png", true, false, 3, 6, 2, 330, 190},
            {"f0640.png", false, true, -1, -1, -1, -1, -1},
    };

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        int fail = 0;
        for (Object[] c : CASES) {
            BufferedImage img = ImageIO.read(new File(dir, (String) c[0]));
            int w = img.getWidth(), h = img.getHeight();
            int[] px = img.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
            GameStateObserver obs = new GameStateObserver(new ScreenLayout());
            Observation o = obs.observe(px, w, h, 0);
            StringBuilder err = new StringBuilder();
            if (o.hudVisible != (Boolean) c[1]) err.append(" hud=").append(o.hudVisible);
            if (o.paused != (Boolean) c[2]) err.append(" paused=").append(o.paused);
            if (o.hp != (Integer) c[3] || o.hpMax != (Integer) c[4]) err.append(" hp=").append(o.hp).append('/').append(o.hpMax);
            if ((Integer) c[5] >= 0 && (!o.map.ok || o.map.visited.size() != (Integer) c[5])) err.append(" visited=").append(o.map.visited.size());
            if ((Integer) c[6] >= 0) {
                if (o.player == null) err.append(" no player");
                else if (Math.hypot(o.player.x - (Integer) c[6], o.player.y - (Integer) c[7]) > 12) err.append(" player at ").append((int) o.player.x).append(',').append((int) o.player.y);
            }
            System.out.println((err.length() == 0 ? "PASS " : "FAIL ") + c[0] + err);
            if (err.length() > 0) fail++;
        }
        System.out.println(fail == 0 ? "VISION OK" : fail + " VISION CHECK(S) FAILED");
        System.exit(fail == 0 ? 0 : 1);
    }
}
