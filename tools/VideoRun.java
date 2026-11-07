import com.nickrocks.aibot.core.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;

/**
 * Feeds a recording of the game (frames f0001.png… at a known fps) through the whole pipeline —
 * screen reader, AI, session, logger — as if it were live, and prints the run's log. The AI's
 * stick choices can't change a recording, so this checks reading, tracking, logging and stability.
 * Usage: java VideoRun <framesDir> <fps> <workDir>
 */
public class VideoRun {
    public static void main(String[] a) throws Exception {
        File[] fs = new File(a[0]).listFiles((d, n) -> n.matches("f\\d+\\.png"));
        Arrays.sort(fs);
        float fps = Float.parseFloat(a[1]);
        File work = new File(a[2]);
        SimTests.deleteTree(work); work.mkdirs();
        RunHistoryRepository repo = new RunHistoryRepository(work);
        AISettings s = new AISettings(); s.logLevel = AISettings.LogLevel.DETAILED; s.skill = AISettings.Skill.ADVANCED;
        RunSessionManager sess = new RunSessionManager(repo, s, new ScreenLayout(), null);
        sess.start(0);
        long t0 = System.nanoTime();
        int n = 0;
        for (File f : fs) {
            BufferedImage src = ImageIO.read(f);
            int w = src.getWidth(), h = src.getHeight();
            int[] px = src.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
            long t = Math.round(n * 1000 / fps);
            sess.onFrame(px, w, h, t, t);
            n++;
            if (!sess.isLive()) break;
        }
        double ms = (System.nanoTime() - t0) / 1e6 / n;
        if (sess.isLive()) { sess.requestStop("end of the recording"); sess.onFrame(new int[480 * 432], 480, 432, n * 1000L / (long) fps + 50, n * 1000L / (long) fps + 50); }
        System.out.printf(Locale.US, "%d frames, %.2f ms per frame (reader + AI)%n", n, ms);
        System.out.println(repo.exportTxt(sess.runId));
    }
}
