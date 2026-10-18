import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Measures cube pitch and grid phase over the whole board region, frame by frame. */
public class PitchProbe {
    public static void main(String[] a) throws Exception {
        for (String p : a) {
            BufferedImage im = ImageIO.read(new File(p));
            int x0 = 100, x1 = 980, y0 = 420, y1 = 1320;
            float[] gx = new float[x1 - x0], gy = new float[y1 - y0];
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
                float l = lum(im, x, y);
                gx[x - x0] += Math.abs(lum(im, x + 1, y) - l);
                gy[y - y0] += Math.abs(lum(im, x, y + 1) - l);
            }
            double[] px = peak(gx, 15, 45), py = peak(gy, 15, 45);
            double[] rx = refine(gx, px[0], px[1]), ry = refine(gy, py[0], py[1]);
            System.out.printf("%s px=%.2f phx=%.1f -> %.3f %.1f | py=%.2f phy=%.1f -> %.3f %.1f%n", new File(p).getName(),
                px[0], x0 + px[1], rx[0], x0 + rx[1], py[0], y0 + py[1], ry[0], y0 + ry[1]);
        }
    }
    /** Comb search: pitch within +-3% and phase, maximizing mean gradient at predicted boundaries. */
    static double[] refine(float[] g, double P0, double ph0) {
        double best = -1, bp = P0, bo = ph0;
        for (double P = P0 * 0.97; P <= P0 * 1.03; P += 0.005) {
            for (double o = 0; o < P; o += 0.25) {
                double s = 0; int n = 0;
                for (double x = o; x < g.length - 1; x += P) {
                    int i = (int) x; double f = x - i;
                    s += g[i] * (1 - f) + g[i + 1] * f; n++;
                }
                s /= n;
                if (s > best) { best = s; bp = P; bo = o; }
            }
        }
        return new double[]{bp, bo};
    }
    static float lum(BufferedImage im, int x, int y) { int c = im.getRGB(x, y); return (((c >> 16) & 255) + ((c >> 8) & 255) + (c & 255)) / 3f; }
    static double[] peak(float[] p, double lo, double hi) {
        double mean = 0; for (float v : p) mean += v; mean /= p.length;
        double best = -1, bp = 0, bph = 0;
        for (double P = lo; P < hi; P += 0.02) {
            double re = 0, im = 0, w = 2 * Math.PI / P;
            for (int k = 0; k < p.length; k++) { double v = p[k] - mean; re += v * Math.cos(w * k); im += v * Math.sin(w * k); }
            double s = re * re + im * im;
            if (s > best) { best = s; bp = P; bph = Math.atan2(im, re); }
        }
        double off = bph / (2 * Math.PI) * bp; if (off < 0) off += bp;   // position of gradient peaks (cube boundaries)
        return new double[]{bp, off};
    }
}
