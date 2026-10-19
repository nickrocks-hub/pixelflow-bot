import com.nickrocks.pixelflowbot.core.mc.McIcons;
import com.nickrocks.pixelflowbot.core.mc.McLayout;
import com.nickrocks.pixelflowbot.vision.Frame;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Prints what the crafting-screen reader sees on each frame, using templates from the OTHER frames only. */
public class InvProbe {
    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        for (int k = 1; k < a.length; k++) {
            String fr = a[k];
            List<McIcons.Template> ts = new ArrayList<>();
            for (String[] l : MakeIcons.LABELS) {
                if (l[0].equals(fr)) continue;
                Frame f = MakeIcons.load(new File(dir, "rec_" + l[0] + ".jpg"));
                float[] p = MakeIcons.where(l[1]);
                ts.add(new McIcons.Template(l[2], McIcons.describe(f, p[0], p[1])));
            }
            McIcons.useTemplates(ts);
            Frame f = MakeIcons.load(new File(dir, "rec_" + fr + ".jpg"));
            System.out.println("== " + fr);
            for (int r = 0; r < McLayout.RECIPE_ROWS; r++) {
                StringBuilder sb = new StringBuilder("  ");
                for (int c = 0; c < McLayout.RECIPE_COLS; c++) {
                    McIcons.Read rd = McIcons.readRecipe(f, McLayout.recipeX(c), McLayout.recipeY(r));
                    sb.append(String.format("%-26s", rd + String.format("[%.2f]", rd.dist)));
                }
                System.out.println(sb);
            }
            StringBuilder hb = new StringBuilder("  hotbar: ");
            for (int i = 0; i < 9; i++) hb.append(McIcons.read(f, McLayout.invSlotX(i), McLayout.INV_SLOT_Y)).append("  ");
            System.out.println(hb);
            System.out.println("  out2: " + McIcons.read(f, McLayout.OUT2_X, McLayout.OUT2_Y) + "   out3: " + McIcons.read(f, McLayout.OUT3_X, McLayout.OUT3_Y));
        }
    }
}
