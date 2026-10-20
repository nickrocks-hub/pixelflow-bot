import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Draws Minesweeper screens in the classic style of the user's app, for testing the reader.
 * The 9x9 layout reuses the real screenshot (header, frame, raised tiles) and paints opened
 * tiles on top; other sizes are drawn from scratch with the same proportions.
 */
final class Render {
    static BufferedImage base;   // the real new-game screenshot

    /** Variant knobs, so the reader isn't tuned to one exact drawing. */
    static final class Style {
        int line = 3;              // grid line width on opened tiles (px at 117-px tiles)
        float fontScale = 0.62f;
        String font = Font.SANS_SERIF;
        int colorJitter = 0;
        int jpegQuality = 92;      // 0 = no JPEG round trip
        boolean pressed = false;   // draw pressed (flat empty) instead of raised for 'P' cells

        static Style random(Random r) {
            Style s = new Style();
            s.line = 1 + r.nextInt(5);
            s.fontScale = 0.5f + r.nextFloat() * 0.25f;
            s.font = r.nextBoolean() ? Font.SANS_SERIF : (r.nextBoolean() ? Font.MONOSPACED : Font.SERIF);
            s.colorJitter = r.nextInt(30);
            s.jpegQuality = r.nextInt(3) == 0 ? 0 : 60 + r.nextInt(36);
            return s;
        }
    }

    static final Color[] NUM = {null, new Color(0, 0, 255), new Color(0, 128, 0), new Color(255, 0, 0),
            new Color(0, 0, 128), new Color(128, 0, 0), new Color(0, 128, 128), Color.BLACK, new Color(128, 128, 128)};

    /**
     * cells: -1 raised, -2 flag, 0..8 opened, 9 mine, 10 hit mine.
     */
    static BufferedImage draw(int cols, int rows, int[] cells, int counter, Style st, Random rnd) throws Exception {
        int W = 1080, H = 2284;
        int x0 = 11, y0 = 142, p = 117;
        if (cols != 9 || rows != 9) {
            p = Math.min((W - 22) / cols, (H - 300) / rows);
            x0 = (W - p * cols) / 2;
        }
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        if (cols == 9 && rows == 9 && counter == 12 && base != null) {
            g.drawImage(base, 0, 0, null);
        } else {
            g.setColor(new Color(192, 192, 192));
            g.fillRect(0, 0, W, H);
            drawHeader(g, W, counter);
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) raised(g, x0 + c * p, y0 + r * p, p);
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int v = cells[r * cols + c];
                int x = x0 + c * p, y = y0 + r * p;
                if (v == -1) continue;
                if (v == -2) { raised(g, x, y, p); flag(g, x, y, p); continue; }
                opened(g, x, y, p, st, v == 10 ? new Color(255, 0, 0) : new Color(192, 192, 192));
                if (v >= 1 && v <= 8) number(g, x, y, p, v, st, rnd);
                if (v == 9 || v == 10) mine(g, x, y, p);
            }
        }
        g.dispose();
        if (st.jpegQuality > 0) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            javax.imageio.ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
            javax.imageio.ImageWriteParam prm = wr.getDefaultWriteParam();
            prm.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            prm.setCompressionQuality(st.jpegQuality / 100f);
            wr.setOutput(ImageIO.createImageOutputStream(bo));
            wr.write(null, new javax.imageio.IIOImage(im, null, null), prm);
            wr.dispose();
            im = ImageIO.read(new ByteArrayInputStream(bo.toByteArray()));
        }
        return im;
    }

    static void raised(Graphics2D g, int x, int y, int p) {
        int b = Math.max(2, Math.round(p * 0.12f));
        g.setColor(new Color(192, 192, 192)); g.fillRect(x, y, p, p);
        g.setColor(Color.WHITE);
        g.fillPolygon(new Polygon(new int[]{x, x + p, x + p - b, x + b, x + b, x}, new int[]{y, y, y + b, y + b, y + p - b, y + p}, 6));
        g.setColor(new Color(128, 128, 128));
        g.fillPolygon(new Polygon(new int[]{x + p, x + p, x, x + b, x + p - b, x + p - b}, new int[]{y, y + p, y + p, y + p - b, y + p - b, y + b}, 6));
    }

    static void opened(Graphics2D g, int x, int y, int p, Style st, Color bg) {
        g.setColor(bg); g.fillRect(x, y, p, p);
        g.setColor(new Color(128, 128, 128));
        g.fillRect(x, y, p, st.line);
        g.fillRect(x, y, st.line, p);
    }

    static void number(Graphics2D g, int x, int y, int p, int v, Style st, Random rnd) {
        Color c = NUM[v];
        if (st.colorJitter > 0) {
            int j = st.colorJitter;
            c = new Color(clamp(c.getRed() + rnd.nextInt(2 * j + 1) - j), clamp(c.getGreen() + rnd.nextInt(2 * j + 1) - j),
                    clamp(c.getBlue() + rnd.nextInt(2 * j + 1) - j));
        }
        g.setColor(c);
        g.setFont(new Font(st.font, Font.BOLD, Math.round(p * st.fontScale)));
        FontMetrics fm = g.getFontMetrics();
        String s = String.valueOf(v);
        g.drawString(s, x + (p - fm.stringWidth(s)) / 2, y + (p + fm.getAscent() - fm.getDescent()) / 2);
    }

    static void mine(Graphics2D g, int x, int y, int p) {
        int cx = x + p / 2, cy = y + p / 2, r = Math.round(p * 0.24f);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(Math.max(2, p / 18f)));
        g.drawLine(cx - r - r / 2, cy, cx + r + r / 2, cy);
        g.drawLine(cx, cy - r - r / 2, cx, cy + r + r / 2);
        g.drawLine(cx - r, cy - r, cx + r, cy + r);
        g.drawLine(cx - r, cy + r, cx + r, cy - r);
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setColor(Color.WHITE);
        g.fillOval(cx - r / 2, cy - r / 2, r / 2, r / 2);
    }

    static void flag(Graphics2D g, int x, int y, int p) {
        g.setColor(Color.BLACK);
        g.fillRect(x + p * 45 / 100, y + p * 25 / 100, Math.max(2, p / 20), p * 45 / 100);
        g.fillRect(x + p * 30 / 100, y + p * 68 / 100, p * 40 / 100, Math.max(2, p / 14));
        g.setColor(new Color(255, 0, 0));
        g.fillPolygon(new int[]{x + p * 47 / 100, x + p * 47 / 100, x + p * 22 / 100}, new int[]{y + p * 22 / 100, y + p * 50 / 100, y + p * 36 / 100}, 3);
    }

    static void drawHeader(Graphics2D g, int W, int counter) {
        g.setColor(Color.BLACK);
        g.fillRect(17, 17, 166, 96);
        g.fillRect(W - 262, 17, 245, 96);
        g.setColor(new Color(255, 230, 0));
        g.fillOval(W / 2 - 45, 20, 90, 90);
        String s = String.format("%03d", Math.max(0, counter));
        for (int i = 0; i < 3; i++) seg(g, 27 + i * 52, 26, 44, 78, s.charAt(i) - '0');
    }

    static final int[] SEG = {0b1111110, 0b0110000, 0b1101101, 0b1111001, 0b0110011, 0b1011011, 0b1011111, 0b1110000, 0b1111111, 0b1111011};

    static void seg(Graphics2D g, int x, int y, int w, int h, int d) {
        int t = 8;
        int[][] r = { // a b c d e f g
                {x + t, y, w - 2 * t, t}, {x + w - t, y + t, t, h / 2 - t}, {x + w - t, y + h / 2, t, h / 2 - t},
                {x + t, y + h - t, w - 2 * t, t}, {x, y + h / 2, t, h / 2 - t}, {x, y + t, t, h / 2 - t}, {x + t, y + h / 2 - t / 2, w - 2 * t, t}};
        for (int s = 0; s < 7; s++) {
            boolean on = ((SEG[d] >> (6 - s)) & 1) == 1;
            g.setColor(on ? new Color(255, 0, 0) : new Color(70, 0, 0));
            g.fillRect(r[s][0], r[s][1], r[s][2], r[s][3]);
        }
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
