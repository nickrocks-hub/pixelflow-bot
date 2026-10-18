import com.nickrocks.pixelflowbot.vision.*;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Draws the screenshot's board region next to what the reader saw. Usage: RenderBoard frame.png out.png */
public class RenderBoard {
    public static void main(String[] a) throws Exception {
        BufferedImage im = ImageIO.read(new File(a[0]));
        int w = im.getWidth(), h = im.getHeight();
        Frame f = new Frame(w, h, im.getRGB(0, 0, w, h, null, 0, w));
        BoardReader.Calibration c = BoardReader.calibrate(f);
        GameColor[][] g = BoardReader.read(f, c);
        int S = 12, W = c.cols * S, H = c.rows * S;
        BufferedImage out = new BufferedImage(W * 2 + 10, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D gr = out.createGraphics();
        gr.drawImage(im.getSubimage((int) c.x0, (int) c.y0, (int) (c.cols * c.cellW), (int) (c.rows * c.cellH)), 0, 0, W, H, null);
        java.util.Map<GameColor, Color> pal = new java.util.EnumMap<>(GameColor.class);
        pal.put(GameColor.WHITE, new Color(235, 240, 248)); pal.put(GameColor.BLACK, new Color(20, 20, 20));
        pal.put(GameColor.RED, new Color(220, 20, 20)); pal.put(GameColor.ORANGE, new Color(255, 130, 20));
        pal.put(GameColor.YELLOW, new Color(255, 215, 40)); pal.put(GameColor.GREEN, new Color(60, 200, 40));
        pal.put(GameColor.BLUE, new Color(30, 80, 230)); pal.put(GameColor.PURPLE, new Color(140, 50, 240));
        gr.setColor(new Color(57, 59, 99)); gr.fillRect(W + 10, 0, W, H);
        for (int r = 0; r < c.rows; r++) for (int col = 0; col < c.cols; col++) {
            if (g[r][col] == GameColor.EMPTY) continue;
            gr.setColor(pal.get(g[r][col]));
            gr.fillRect(W + 10 + col * S + 1, r * S + 1, S - 2, S - 2);
        }
        ImageIO.write(out, "png", new File(a[1]));
        System.out.println(c + " grid " + c.cols + "x" + c.rows);
    }
}
