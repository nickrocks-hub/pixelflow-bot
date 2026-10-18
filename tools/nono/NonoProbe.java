import com.nickrocks.pixelflowbot.nono.*;
import com.nickrocks.pixelflowbot.vision.Frame;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Desktop check: java NonoProbe frame.png ...  prints what the bot reads from each screenshot. */
public class NonoProbe {
    public static Frame load(String path) throws Exception {
        BufferedImage im = ImageIO.read(new File(path));
        int w = im.getWidth(), h = im.getHeight();
        int[] px = im.getRGB(0, 0, w, h, null, 0, w);
        return new Frame(w, h, px);
    }
    public static void main(String[] a) throws Exception {
        boolean solve = false;
        for (String p : a) {
            if (p.equals("-solve")) { solve = true; continue; }
            NonoVision v = new NonoVision(); NonoVision.DEBUG = System.getenv("NDEBUG") != null;
            NonoVision.Reading r = v.read(load(p));
            System.out.println("== " + p + " (" + r.ms + " ms)");
            if (r.grid == null) { System.out.println("  " + r.error); continue; }
            System.out.println("  grid " + r.grid + "  toggle=" + r.toggle + " @" + r.toggleX + "," + r.toggleY);
            System.out.println("  " + r.cluesText().replace("\n", "\n  "));
            System.out.println("  worst digit " + r.worstDigit + " at " + r.worstWhere + (r.error != null ? "  ERROR: " + r.error : ""));
            System.out.print(r.boardText());
            if (solve && r.error == null) {
                NonoSolver.Result s = new NonoSolver().solve(r.rowClues, r.colClues, null);
                System.out.println("  solver: " + s.status + " " + s.note);
                if (s.grid != null) System.out.print(NonoSolver.text(s.grid, r.grid.rows, r.grid.cols));
            }
        }
    }
}
