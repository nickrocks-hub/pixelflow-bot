import com.nickrocks.pixelflowbot.vision.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Replays recorded frames through Tracker + Planner and prints what the bot would do. */
public class PlanReplay {
    public static void main(String[] a) throws Exception {
        Tracker t = new Tracker();
        for (String p : a) {
            BufferedImage im = ImageIO.read(new File(p));
            int w = im.getWidth(), h = im.getHeight();
            GameState s = t.update(new Frame(w, h, im.getRGB(0, 0, w, h, null, 0, w)));
            Planner.Decision d = Planner.decide(s);
            String state = s.screen != GameState.Screen.GAMEPLAY ? s.screen.name()
                    : "slots " + s.slotsUsed() + "/5 belt " + s.beltFree;
            System.out.println(new File(p).getName() + " | " + state + " | "
                    + (d.move != null ? "TAP " + d.move : d.stuck ? "PAUSE" : "wait") + " | " + d.reason);
        }
    }
}
