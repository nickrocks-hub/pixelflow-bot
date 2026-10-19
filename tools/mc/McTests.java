import com.nickrocks.pixelflowbot.core.mc.McBrain;
import com.nickrocks.pixelflowbot.core.mc.McIcons;
import com.nickrocks.pixelflowbot.core.mc.McLayout;
import com.nickrocks.pixelflowbot.core.mc.McVision;
import com.nickrocks.pixelflowbot.vision.Frame;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft player checks on frames from the user's own screen recording (tools/mc/samples):
 * screen kinds, health, trunks, the crafting screen reader, camera-shift measurement, and the
 * planner walked through the recorded sequence (logs → planks → table → sticks → pickaxe).
 *   java -cp /tmp/mc McTests tools/mc/samples
 */
public class McTests {
    static int pass, fail;
    static final Map<String, Frame> frames = new HashMap<>();

    static void check(boolean ok, String what) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
    }

    static Frame f(String n) { return frames.get(n); }

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        for (File file : dir.listFiles()) {
            String n = file.getName();
            if (n.startsWith("rec_") && n.endsWith(".jpg")) frames.put(n.substring(4, 7), MakeIcons.load(file));
        }

        System.out.println("Screens");
        String[] world = {"001", "014", "026", "030", "037", "058", "076", "120", "123"};
        String[] inv = {"080", "084", "090", "097", "101", "104", "108", "112"};
        String[] table = {"125", "128", "131"};
        for (String n : world) check(McVision.screen(f(n)) == McVision.Screen.WORLD && McVision.health(f(n)) == 20, n + " world, full health");
        for (String n : inv) check(McVision.screen(f(n)) == McVision.Screen.CRAFT2, n + " inventory crafting (2×2)");
        for (String n : table) check(McVision.screen(f(n)) == McVision.Screen.CRAFT3, n + " crafting table (3×3)");
        int[] blank = new int[2520 * 1080];
        check(McVision.screen(new Frame(2520, 1080, blank)) == McVision.Screen.OTHER, "black screen is OTHER");

        System.out.println("Touch controls");
        Frame dp = MakeIcons.load(new File(dir, "user_dpad.jpg"));
        check(McVision.screen(dp) == McVision.Screen.WORLD, "your D-pad screenshot is the world screen");
        check(McVision.layout(dp) == McVision.Layout.DPAD, "your D-pad screenshot → D-pad");
        for (String n : world) check(McVision.layout(f(n)) == McVision.Layout.JOYSTICK, n + " (recording) → joystick");
        McBrain auto = new McBrain();
        auto.setTurn(-0.8f);
        auto.next(dp);
        check(auto.controls == McBrain.Controls.DPAD, "planner switches itself to the D-pad");

        System.out.println("Crafting screen reader");
        McVision.Craft c = McVision.craft(f("080"));
        check(c.canMake("planks") == 16 && c.canMake("crafting_table") == 0 && "log".equals(c.hotbar[0].item), "080: 4 logs → planks ×16, table red, logs in slot 1");
        c = McVision.craft(f("084"));
        check(c.selected("planks") && "planks".equals(c.output.item), "084: planks recipe picked, planks in the output");
        c = McVision.craft(f("097"));
        check(c.canMake("planks") == 0 && c.canMake("button") == 16 && c.canMake("stick") == 32 && c.canMake("crafting_table") == 4, "097: 16 planks (button ×16), sticks ×32, table ×4");
        c = McVision.craft(f("112"));
        check(c.slot("crafting_table") == 2 && c.slot("stick") == 0 && c.slot("planks") == 1 && "crafting_table".equals(c.output.item), "112: hotbar stick, planks, table; table in output");
        c = McVision.craft(f("125"));
        check(c.table && c.canMake("wooden_pickaxe") == 2 && c.canMake("wooden_axe") == 2 && c.canMake("wooden_sword") == 4 && c.canMake("button") == 10,
                "125: table recipes pickaxe ×2, axe ×2, sword ×4, 10 planks");
        c = McVision.craft(f("131"));
        check(c.slot("wooden_pickaxe") == 0 && "wooden_pickaxe".equals(c.output.item), "131: pickaxe crafted into slot 1");
        int bad = 0;
        for (String n : new String[]{"080", "097", "112", "125", "131"}) {
            McVision.Craft cc = McVision.craft(f(n));
            for (int r = 0; r < cc.recipes.length; r++) for (int col = 0; col < cc.recipes[r].length; col++) {
                if (cc.recipes[r][col].item.equals("?")) { bad++; System.out.println("     unrecognised: " + n + " r" + r + " c" + col + " d=" + cc.recipes[r][col].dist + " bg " + cc.recipes[r][col].bg); }
            }
        }
        check(bad == 0, "no unrecognised recipe on 5 screens without pop-ups (" + bad + ")");

        System.out.println("Trunks");
        for (String n : new String[]{"030", "037", "058"}) {
            List<McVision.Trunk> ts = McVision.trunks(f(n));
            check(!ts.isEmpty() && ts.get(0).width >= 230, n + ": trunk right in front, close enough to mine (" + (ts.isEmpty() ? "none" : ts.get(0)) + ")");
        }
        for (String n : new String[]{"001", "014"}) {
            List<McVision.Trunk> ts = McVision.trunks(f(n));
            check(!ts.isEmpty(), n + ": a trunk in view (" + (ts.isEmpty() ? "none" : ts.get(0)) + ")");
        }
        boolean tableTrunk = false;
        for (McVision.Trunk t : McVision.trunks(f("123"))) if (t.cx > 1100 && t.cx < 1550) tableTrunk = true;
        check(!tableTrunk, "123: the placed crafting table is not taken for a tree");
        check(McVision.trunks(f("120")).isEmpty(), "120: no trunk in view");

        Frame bf = MakeIcons.load(new File(dir, "user_birch.jpg"));
        List<McVision.Trunk> bt = McVision.trunks(bf);
        int birches = 0;
        for (McVision.Trunk t : bt) if (t.birch) birches++;
        check(birches >= 3 && bt.get(0).birch, "your birch-forest screenshot: " + birches + " birch trunks, nearest " + (bt.isEmpty() ? "none" : bt.get(0)));
        boolean falseBirch = false;
        for (String n : world) for (McVision.Trunk t : McVision.trunks(f(n))) if (t.birch) falseBirch = true;
        for (McVision.Trunk t : McVision.trunks(MakeIcons.load(new File(dir, "user_dpad.jpg")))) if (t.birch) falseBirch = true;
        check(!falseBirch, "no birch seen in the jungle frames (sky, stone, the crafting table)");

        System.out.println("Camera turn measurement");
        Frame base = f("123");
        for (int sh : new int[]{160, -240}) {
            int[] px = new int[base.px.length];
            for (int y = 0; y < base.h; y++) for (int x = 0; x < base.w; x++) {
                int sx = Math.min(base.w - 1, Math.max(0, x - sh));
                px[y * base.w + x] = base.px[y * base.w + sx];
            }
            float got = McVision.shift(McVision.columnProfile(base), McVision.columnProfile(new Frame(base.w, base.h, px)));
            check(Math.abs(got - sh) <= 8, "picture moved " + sh + " px → measured " + got);
        }

        System.out.println("Planner, along the recording");
        McBrain b = new McBrain();
        b.setTurn(-0.8f);
        McBrain.Step st = b.next(f("037"));
        check(st.acts.size() == 1 && st.acts.get(0).kind == McBrain.Act.Kind.HOLD, "037: holds on the trunk in front: " + st);
        st = b.next(f("120"));
        check(b.logsBroken == 1 && hasWalk(st), "then the trunk is gone → counts a log and walks in: " + st);
        st = b.next(f("037"));
        st = b.next(f("120"));
        check(b.logsBroken == 2, "second log");
        st = b.next(f("120"));
        check(b.phase == McBrain.Phase.CHECK && tapAt(st, McLayout.INV_BTN_X, McLayout.INV_BTN_Y), "after two logs → opens the inventory to count: " + st);
        st = b.next(f("080"));
        check(b.phase == McBrain.Phase.CRAFT_INV && b.logsSeen == 4, "080: 4 logs = enough for table, sticks and three tools → crafting: " + st);
        st = b.next(f("080"));
        check(tapAt(st, McLayout.recipeX(0), McLayout.recipeY(0)), "080: picks the planks recipe: " + st);
        st = b.next(f("084"));
        check(tapAt(st, McLayout.OUT2_X, McLayout.OUT2_Y), "084: takes the planks from the output: " + st);
        st = b.next(f("097"));
        check(tapAt(st, McLayout.recipeX(3), McLayout.recipeY(0)), "097: logs used up → picks the crafting table recipe: " + st);
        st = b.next(f("112"));
        check(tapAt(st, McLayout.recipeX(2), McLayout.recipeY(0)), "112: table made → picks the stick recipe: " + st);
        st = b.next(f("104"));
        check(tapAt(st, McLayout.OUT2_X, McLayout.OUT2_Y), "104: takes the sticks: " + st);
        st = b.next(f("112"));
        check(tapAt(st, McLayout.recipeX(2), McLayout.recipeY(0)), "112: 10 planks left, one stick craft so far → a second one (axe + sword need 5 sticks): " + st);
        st = b.next(f("104"));
        check(tapAt(st, McLayout.OUT2_X, McLayout.OUT2_Y), "104: takes the second batch of sticks: " + st);
        st = b.next(f("112"));
        check(b.phase == McBrain.Phase.PLACE && tapAt(st, McLayout.CLOSE_X, McLayout.CLOSE_Y), "112: done in the inventory → closes it to place the table: " + st);
        st = b.next(f("120"));
        check(tapAt(st, McLayout.hudSlotX(2), McLayout.HUD_SLOT_Y) && st.acts.size() == 3, "120: selects the table (slot 3), places it, taps it: " + st);
        st = b.next(f("125"));
        check(b.phase == McBrain.Phase.CRAFT_TABLE && tapAt(st, McLayout.recipeX(5), McLayout.recipeY(1)), "125: at the table → picks the wooden pickaxe: " + st);
        st = b.next(f("131"));
        check(tapAt(st, McLayout.recipeX(4), McLayout.recipeY(1)), "131: pickaxe in the hotbar → next the axe: " + st);
        b.fullSet = false;
        st = b.next(f("131"));
        check(b.phase == McBrain.Phase.EQUIP && tapAt(st, McLayout.CLOSE_X, McLayout.CLOSE_Y), "pickaxe-only plan: closes the table: " + st);
        st = b.next(f("120"));
        check(tapAt(st, McLayout.hudSlotX(0), McLayout.HUD_SLOT_Y), "takes the pickaxe in hand (slot 1): " + st);
        st = b.next(f("120"));
        check(st.stop != null && st.success, "finished: " + st.stop);

        System.out.println("Safety");
        McBrain s2 = new McBrain();
        s2.setTurn(-0.8f);
        for (int i = 0; i < 3; i++) s2.next(new Frame(2520, 1080, blank));
        check(s2.next(new Frame(2520, 1080, blank)).stop != null, "stops on a screen it doesn't know (4 in a row)");

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static boolean tapAt(McBrain.Step st, float x, float y) {
        for (McBrain.Act a : st.acts) if (a.kind == McBrain.Act.Kind.TAP && Math.abs(a.x - x) < 2 && Math.abs(a.y - y) < 2) return true;
        return false;
    }

    static boolean hasWalk(McBrain.Step st) {
        for (McBrain.Act a : st.acts) if (a.kind == McBrain.Act.Kind.WALK) return true;
        return false;
    }
}
