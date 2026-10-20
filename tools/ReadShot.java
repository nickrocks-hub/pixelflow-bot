import com.aigame.minesweeper.core.MineVision;

/** java ReadShot picture.jpg [captureWidth] : prints what the bot reads from a screenshot. */
public class ReadShot {
    public static void main(String[] a) throws Exception {
        int width = a.length > 1 ? Integer.parseInt(a[1]) : 540;
        Img im = Img.load(a[0], width);
        long t0 = System.nanoTime();
        MineVision.Geometry g = MineVision.findGrid(im.px, im.w, im.h);
        long t1 = System.nanoTime();
        if (g == null) { System.out.println("no board found"); return; }
        MineVision.Reading r = MineVision.read(im.px, im.w, im.h, g);
        long t2 = System.nanoTime();
        System.out.printf("%dx%d  grid %s  mines %d  smiley (%.0f,%.0f)  find %.1f ms  read %.1f ms%n",
                im.w, im.h, g, r.mines, r.smileX, r.smileY, (t1 - t0) / 1e6, (t2 - t1) / 1e6);
        System.out.print(r.grid());
    }
}
