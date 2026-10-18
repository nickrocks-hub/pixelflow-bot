import com.nickrocks.pixelflowbot.candy.Board;
import com.nickrocks.pixelflowbot.candy.BoardShape;
import com.nickrocks.pixelflowbot.candy.CandyVision;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

/**
 * Synthetic Candy Crush screens with differently shaped boards, run through the board detector
 * (original + BoardShape). Checks the grid and every cell (candy vs hole) against the truth.
 * Synthetic images approximate the game's look; real screenshots are the final test.
 *
 *   javac -cp app/libs/candy-vision.jar -d /tmp/cs app/src/main/java/com/nickrocks/pixelflowbot/candy/*.java tools/candy/CandyShapes.java
 *   java -cp /tmp/cs:app/libs/candy-vision.jar CandyShapes [outDir]
 */
public class CandyShapes {
    static final int W = 1080, H = 2400;
    static final Color[] CANDY = {new Color(230, 30, 45), new Color(250, 140, 20), new Color(250, 215, 30),
            new Color(40, 200, 70), new Color(30, 120, 240), new Color(170, 60, 225)};

    static final String[][] SHAPES = {
            {"full 9x9", "#########", "#########", "#########", "#########", "#########", "#########", "#########", "#########", "#########"},
            {"small 5x5", "#####", "#####", "#####", "#####", "#####"},
            {"wide 9x5", "#########", "#########", "#########", "#########", "#########"},
            {"tall 6x9", "######", "######", "######", "######", "######", "######", "######", "######", "######"},
            {"plus", "...###...", "...###...", "...###...", "#########", "#########", "#########", "...###...", "...###...", "...###..."},
            {"diamond", "....#....", "...###...", "..#####..", ".#######.", "#########", ".#######.", "..#####..", "...###...", "....#...."},
            {"two islands", "###...###", "###...###", "###...###", "###...###", "###...###", "###...###", "###...###"},
            {"ring", "#########", "#########", "##.....##", "##.....##", "##.....##", "##.....##", "#########", "#########"},
            {"narrow 3x8", "###", "###", "###", "###", "###", "###", "###", "###"},
            {"stairs", "###......", "####.....", ".####....", "..####...", "...####..", "....####.", ".....####", "......###"},
    };

    public static void main(String[] a) throws Exception {
        File out = a.length > 0 ? new File(a[0]) : null;
        if (out != null) out.mkdirs();
        int okOrig = 0, okNew = 0;
        badMoves = 0;
        moves = 0;
        System.out.printf("%-12s %-34s %-34s %s%n", "shape", "original detector", "with BoardShape", "cells right");
        for (String[] sh : SHAPES) {
            String name = sh[0];
            int rows = sh.length - 1, cols = sh[1].length();
            boolean[] cellOn = new boolean[rows * cols];
            for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) cellOn[r * cols + c] = sh[r + 1].charAt(c) == '#';
            float cell = 112;   // Candy Crush keeps the cell size; small boards are centred
            float bx = (W - cols * cell) / 2f + cell / 2f, by = H * 0.55f - rows * cell / 2f + cell / 2f;
            BufferedImage im = render(cellOn, rows, cols, bx, by, cell, name.hashCode());
            if (out != null) ImageIO.write(im, "png", new File(out, name.replace(' ', '_') + ".png"));
            int[] px = im.getRGB(0, 0, W, H, null, 0, W);
            CandyVision v = new CandyVision();
            CandyVision.Grid g0 = v.detect(px, W, H);
            String orig = g0 == null ? "none (" + v.lastNote + ")" : g0.cols + "x" + g0.rows + (matches(g0, rows, cols, bx, by, cell) ? " ✓" : " ✗");
            if (g0 != null && matches(g0, rows, cols, bx, by, cell)) okOrig++;
            CandyVision.Grid g1 = g0 == null ? BoardShape.find(px, W, H) : BoardShape.expand(px, W, H, g0);
            String now = g1 == null ? "none (" + BoardShape.lastNote + ")" : g1.cols + "x" + g1.rows + " " + g1.source;
            String cells = "—";
            if (g1 != null && matches(g1, rows, cols, bx, by, cell)) {
                Board b = v.read(px, W, H, g1, null);
                int right = 0;
                for (int i = 0; i < rows * cols; i++) {
                    boolean hole = b.kind[i] == Board.HOLE;
                    if (hole != cellOn[i]) right++;
                }
                cells = right + "/" + rows * cols;
                // The move ranker must only ever swap two neighbouring candies, never touch a hole.
                for (com.nickrocks.pixelflowbot.candy.CandySolver.Move mv : new com.nickrocks.pixelflowbot.candy.CandySolver().rank(b, new java.util.HashSet<>())) {
                    boolean ok = b.in(mv.r1, mv.c1) && b.in(mv.r2, mv.c2) && cellOn[mv.r1 * cols + mv.c1] && cellOn[mv.r2 * cols + mv.c2]
                            && Math.abs(mv.r1 - mv.r2) + Math.abs(mv.c1 - mv.c2) == 1;
                    if (!ok) { badMoves++; System.out.println("  BAD MOVE " + mv + " on " + name); }
                    moves++;
                }
                if (right == rows * cols) okNew++;
                now += " ✓";
            } else now += " ✗";
            System.out.printf("%-12s %-34s %-34s %s%n", name, orig, now, cells);
        }
        System.out.println("\noriginal: " + okOrig + "/" + SHAPES.length + " grids right · with BoardShape: " + okNew + "/" + SHAPES.length + " grids and all cells right");
        System.out.println("move ranker: " + moves + " moves proposed, " + badMoves + " touching a hole or not adjacent");
        if (okNew < SHAPES.length || badMoves > 0) System.exit(1);
    }

    static int badMoves, moves;

    static boolean matches(CandyVision.Grid g, int rows, int cols, float bx, float by, float cell) {
        return g.rows == rows && g.cols == cols && Math.abs(g.x0 - bx) < cell * 0.25f && Math.abs(g.y0 - by) < cell * 0.25f
                && Math.abs(g.cell - cell) < cell * 0.06f;
    }

    static BufferedImage render(boolean[] on, int rows, int cols, float bx, float by, float cell, long seed) {
        Random rnd = new Random(seed);
        BufferedImage im = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Scenery: soft gradient + some colourful but small decorations.
        g.setPaint(new GradientPaint(0, 0, new Color(140, 175, 205), 0, H, new Color(110, 150, 120)));
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < 40; i++) {
            g.setColor(new Color(150 + rnd.nextInt(80), 130 + rnd.nextInt(60), 150 + rnd.nextInt(60)));
            int s = 20 + rnd.nextInt(60);
            g.fillOval(rnd.nextInt(W), (int) (H * 0.15f) + rnd.nextInt((int) (H * 0.75f)), s, s);
        }
        // HUD: goal bar with coloured icons, booster bar.
        g.setColor(new Color(60, 40, 110));
        g.fillRoundRect(40, 120, W - 80, 230, 40, 40);
        for (int i = 0; i < 3; i++) { g.setColor(CANDY[rnd.nextInt(6)]); g.fillOval(380 + i * 120, 180, 90, 90); }
        g.setColor(new Color(60, 40, 110));
        g.fillRoundRect(40, (int) (H * 0.9f), W - 80, 170, 40, 40);
        for (int i = 0; i < 5; i++) { g.setColor(CANDY[i]); g.fillRoundRect(90 + i * 190, (int) (H * 0.9f) + 30, 110, 110, 30, 30); }
        // Tiles and candies.
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            if (!on[r * cols + c]) continue;
            float x = bx + c * cell - cell / 2f, y = by + r * cell - cell / 2f;
            g.setColor(new Color(45, 65, 135));
            g.fillRect(Math.round(x), Math.round(y), Math.round(cell), Math.round(cell));
            g.setColor(new Color(55, 78, 150));
            g.fillRect(Math.round(x + 3), Math.round(y + 3), Math.round(cell - 6), Math.round(cell - 6));
        }
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            if (!on[r * cols + c]) continue;
            int k = rnd.nextInt(6);
            float cx = bx + c * cell, cy = by + r * cell, s = cell * 0.74f;
            Color col = CANDY[k];
            g.setColor(col.darker());
            shape(g, k, cx, cy, s + 6);
            g.setColor(col);
            shape(g, k, cx, cy, s);
            g.setColor(new Color(255, 255, 255, 200));
            g.fillOval(Math.round(cx - s * 0.25f), Math.round(cy - s * 0.3f), Math.round(s * 0.18f), Math.round(s * 0.12f));
        }
        g.dispose();
        return im;
    }

    static void shape(Graphics2D g, int k, float cx, float cy, float s) {
        int x = Math.round(cx - s / 2), y = Math.round(cy - s / 2), d = Math.round(s);
        switch (k) {
            case 0: g.fillOval(x, Math.round(cy - s * 0.38f), d, Math.round(s * 0.76f)); break;            // jelly bean
            case 1: g.fillRoundRect(x + d / 10, y, d * 8 / 10, d, d / 2, d / 2); break;                     // lozenge
            case 2: g.fillPolygon(new int[]{Math.round(cx), x + d, Math.round(cx), x}, new int[]{y, Math.round(cy), y + d, Math.round(cy)}, 4); break;
            case 3: g.fillRoundRect(x + d / 12, y + d / 12, d * 10 / 12, d * 10 / 12, d / 5, d / 5); break; // square
            case 4: g.fillOval(x, y, d, d); break;                                                          // ball
            default: g.fillPolygon(new int[]{Math.round(cx), x + d, x + d * 4 / 5, x + d / 5, x},
                    new int[]{y, y + d * 2 / 5, y + d, y + d, y + d * 2 / 5}, 5);                          // cluster
        }
    }
}
