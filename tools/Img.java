import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import javax.imageio.ImageIO;

/** Loads pictures as 0xRRGGBB arrays, optionally scaled like the phone's 540-px capture. */
final class Img {
    final int[] px; final int w, h;
    Img(int[] px, int w, int h) { this.px = px; this.w = w; this.h = h; }

    static Img load(String path, int width) throws Exception {
        BufferedImage im = ImageIO.read(new File(path));
        return of(im, width);
    }

    static Img of(BufferedImage im, int width) {
        if (width > 0 && width != im.getWidth()) {
            int hh = Math.round((float) width * im.getHeight() / im.getWidth());
            BufferedImage s = new BufferedImage(width, hh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = s.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(im, 0, 0, width, hh, null);
            g.dispose();
            im = s;
        }
        int w = im.getWidth(), h = im.getHeight();
        int[] px = im.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) px[i] &= 0xFFFFFF;
        return new Img(px, w, h);
    }
}
