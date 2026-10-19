package com.nickrocks.pixelflowbot.core.mc;

import com.nickrocks.pixelflowbot.vision.Frame;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides the bot's next move in Minecraft's first minutes: punch trees for logs, then craft planks,
 * a crafting table, sticks, place the table and make wooden tools (pickaxe first; axe and sword if
 * there's wood for them), and finally hold the pickaxe.
 *
 * One call = one look at the screen → a few touch actions. Every decision is re-checked on the next
 * screen: logs counted by the game's own recipe book, crafts confirmed by the hotbar. Plain Java with
 * no Android types, so its logic runs in desktop tests.
 */
public final class McBrain {

    public enum Controls { JOYSTICK, DPAD }

    public enum Phase { CALIBRATE, GATHER, CHECK, CRAFT_INV, PLACE, CRAFT_TABLE, EQUIP, DONE }

    /** A touch action in reference coordinates (2520×1080). */
    public static final class Act {
        public enum Kind { TAP, HOLD, SWIPE, WALK }
        public final Kind kind;
        public final float x, y, x2, y2;
        public final long ms;
        Act(Kind k, float x, float y, float x2, float y2, long ms) { kind = k; this.x = x; this.y = y; this.x2 = x2; this.y2 = y2; this.ms = ms; }
        @Override public String toString() {
            switch (kind) {
                case TAP: return String.format(Locale.US, "tap %.0f,%.0f", x, y);
                case HOLD: return String.format(Locale.US, "hold %.0f,%.0f %dms", x, y, ms);
                case SWIPE: return String.format(Locale.US, "swipe %.0f,%.0f→%.0f,%.0f", x, y, x2, y2);
                default: return "walk " + ms + "ms";
            }
        }
    }

    public static final class Step {
        public final List<Act> acts = new ArrayList<>();
        public long settleMs = 500;      // wait after the actions before looking again
        public String note = "";
        public String stop;              // non-null: stop the session with this message
        public boolean success;          // the stop is the goal reached
        Step tap(float x, float y) { acts.add(new Act(Act.Kind.TAP, x, y, x, y, 60)); return this; }
        Step hold(float x, float y, long ms) { acts.add(new Act(Act.Kind.HOLD, x, y, x, y, ms)); return this; }
        Step swipe(float x0, float y0, float x1, float y1, long ms) { acts.add(new Act(Act.Kind.SWIPE, x0, y0, x1, y1, ms)); return this; }
        Step walk(long ms) { acts.add(new Act(Act.Kind.WALK, 0, 0, 0, 0, ms)); return this; }
        Step settle(long ms) { settleMs = ms; return this; }
        Step say(String s) { note = s; return this; }
        @Override public String toString() { return (stop != null ? "STOP " + stop : note) + " " + acts; }
    }

    // ---------------------------------------------------------------- settings

    public Controls controls = Controls.JOYSTICK;
    /** Detect the movement control from the screen (true) or keep {@link #controls} as set. */
    public boolean autoControls = true;
    /** What the last screen showed, for the log. */
    public String seen = "";
    /** Also make an axe and a sword when there's wood for them. */
    public boolean fullSet = true;
    /** Hand-mining a log takes 3 s; hold a little longer. */
    public long holdMs = 3600;
    /** A trunk this wide (reference px) is close enough to mine. */
    public float reachWidth = 230;

    // ---------------------------------------------------------------- state

    public Phase phase = Phase.CALIBRATE;
    /** Picture shift per pixel of finger movement when swiping sideways (negative: drag right → view turns right). */
    public float turnK = Float.NaN;
    private float[] calibProfile;
    private int calibTries;

    private int bestHealth = -1, otherScreens;
    private int steps;

    // gathering
    private float[] holdAt;              // last hold point
    private int[] holdBefore;            // patch before the hold
    private int holdFails, searchTurns, searchTotal, walkStuck;
    private int[] viewBefore;            // whole-view thumbnail before a walk
    private float[] searchProfile;       // view before a search turn
    private int deadTurns;
    public int logsBroken;
    private int logsBrokenAtCheck, checks, lastLogsSeen = -1, checksWithoutGain;
    public int logsSeen;                 // logs in the inventory at the last check

    // crafting
    private int tableSlot = -1, pickaxeSlot = -1;
    private boolean tableMade, tablePlaced;
    private int stickCrafts, stickCraftsNeeded = 1;
    private String lastCraft;
    private int sameCraftTaps;
    private final Set<String> made = new HashSet<>();
    private final Set<String> skipped = new HashSet<>();
    private int placeTries;
    private int craftScreenSteps;

    public int steps() { return steps; }

    // ---------------------------------------------------------------- main

    public Step next(Frame f) {
        steps++;
        McVision.Screen s = McVision.screen(f);
        if (s == McVision.Screen.OTHER) {
            if (++otherScreens >= 4) return stop("This isn't the game world or the crafting screen (pause menu, death screen, chat?). Go back to the game and press ▶ again.");
            return new Step().settle(900).say("Waiting for the game screen…");
        }
        otherScreens = 0;
        seen = s.name().toLowerCase(Locale.US);
        if (s == McVision.Screen.WORLD) {
            if (autoControls) {
                McVision.Layout l = McVision.layout(f);
                if (l == McVision.Layout.DPAD) controls = Controls.DPAD;
                else if (l == McVision.Layout.JOYSTICK) controls = Controls.JOYSTICK;
            }
            int h = McVision.health(f);
            seen += " health " + h + " " + controls.name().toLowerCase(Locale.US);
            if (h > bestHealth) bestHealth = h;
            if (bestHealth > 0 && h <= bestHealth - 3) {
                return stop(String.format(Locale.US, "Took damage (%d/20 health, was %d) — a mob or a fall? Stopped so you can take over.", h, bestHealth));
            }
        }
        if (steps > 900) return stop("Stopped after 900 steps without finishing.");
        switch (phase) {
            case CALIBRATE: return calibrate(f, s);
            case GATHER: return gather(f, s);
            case CHECK: return check(f, s);
            case CRAFT_INV: return craftInventory(f, s);
            case PLACE: return place(f, s);
            case CRAFT_TABLE: return craftTable(f, s);
            case EQUIP: return equip(f, s);
            default: return done();
        }
    }

    private Step stop(String why) { Step st = new Step(); st.stop = why; return st; }

    private Step done() {
        Step st = new Step();
        st.stop = "Done: " + (made.isEmpty() ? "nothing crafted" : String.join(", ", made).replace('_', ' ')) + ". Holding the wooden pickaxe.";
        st.success = true;
        phase = Phase.DONE;
        return st;
    }

    private Step closeScreen(String why) { return new Step().tap(McLayout.CLOSE_X, McLayout.CLOSE_Y).settle(700).say(why); }

    private Step openInventory(String why) { return new Step().tap(McLayout.INV_BTN_X, McLayout.INV_BTN_Y).settle(1000).say(why); }

    // ---------------------------------------------------------------- camera

    /** Swipe so the picture moves by {@code shift} reference px (+ = picture moves right). */
    private Step turnBy(Step st, float shift) {
        float k = Float.isNaN(turnK) ? -1f : turnK;
        float finger = shift / k;
        while (Math.abs(finger) > 1) {
            float part = Math.max(-McLayout.LOOK_MAX, Math.min(McLayout.LOOK_MAX, finger));
            st.swipe(McLayout.LOOK_X - part / 2, McLayout.LOOK_Y, McLayout.LOOK_X + part / 2, McLayout.LOOK_Y, 380);
            finger -= part;
            if (st.acts.size() > 6) break;
        }
        return st;
    }

    /** Swipe so screen column x comes to the centre. */
    private Step turnTo(Step st, float x) { return turnBy(st, McLayout.CX - x); }

    private Step calibrate(Frame f, McVision.Screen s) {
        if (s != McVision.Screen.WORLD) return closeScreen("Closing this screen to start in the world…");
        if (calibProfile == null) {
            calibProfile = McVision.columnProfile(f);
            return new Step().swipe(McLayout.LOOK_X - 200, McLayout.LOOK_Y, McLayout.LOOK_X + 200, McLayout.LOOK_Y, 400).settle(450)
                    .say("Measuring how far a swipe turns the camera…");
        }
        float sh = McVision.shift(calibProfile, McVision.columnProfile(f));
        calibProfile = null;
        if (Float.isNaN(sh) || Math.abs(sh) < 20) {
            if (++calibTries >= 3) {
                return stop("Swiping on the screen didn't turn the camera (3 tries). Check Minecraft's touch settings: classic touch "
                        + "controls (not split controls), and that nothing covers the right half of the screen. Then press ▶ again.");
            }
            return new Step().settle(200).say("Camera turn not measured — trying again…");
        }
        turnK = sh / 400f;
        phase = Phase.GATHER;
        return new Step().settle(200).say(String.format(Locale.US, "Camera: %.2f px of view per px of swipe. Looking for trees…", turnK));
    }

    // ---------------------------------------------------------------- gathering logs

    private Step gather(Frame f, McVision.Screen s) {
        if (s != McVision.Screen.WORLD) return closeScreen("Back to the world to gather wood…");

        // Result of the last hold: did the block under the finger change?
        if (holdAt != null) {
            int[] after = McVision.patch(f, holdAt[0], holdAt[1], 60);
            float diff = McVision.patchDiff(holdBefore, after);
            float barkNow = McVision.barkAround(f, holdAt[0], holdAt[1], 40);
            float hx = holdAt[0];
            holdAt = null;
            if (diff > 18 && (barkNow < 0.5f || diff > 30)) {
                logsBroken++;
                holdFails = 0;
                Step st = turnTo(new Step(), hx);
                return st.walk(900).settle(400).say("Broke a log (" + logsBroken + " so far). Walking in to pick it up.");
            }
            holdFails++;
            if (holdFails >= 4) {
                holdFails = 0;
                return turnBy(new Step(), 700).walk(800).settle(400).say("This trunk won't break — trying another one.");
            }
            return turnTo(new Step(), hx).walk(700).settle(400).say("Nothing broke — probably out of reach. Moving closer.");
        }

        // Count what was actually picked up, every couple of logs.
        int need = Math.max(1, logsNeeded() - logsSeen);
        if (logsBroken - logsBrokenAtCheck >= Math.min(2, need)) {
            phase = Phase.CHECK;
            return openInventory("Checking the inventory for logs…");
        }

        // Stuck after a walk? (view didn't change)
        if (viewBefore != null) {
            float d = McVision.patchDiff(viewBefore, McVision.patch(f, McLayout.CX, McLayout.CY, 500));
            viewBefore = null;
            if (d < 3 && ++walkStuck >= 2) {
                walkStuck = 0;
                Step st = turnBy(new Step(), 900);
                viewBefore = McVision.patch(f, McLayout.CX, McLayout.CY, 500);
                return st.walk(1200).settle(400).say("Seems stuck — turning and walking around it.");
            }
        }

        // Did the last search turn actually turn the camera?
        if (searchProfile != null) {
            float sh = McVision.shift(searchProfile, McVision.columnProfile(f));
            searchProfile = null;
            if (!Float.isNaN(sh) && Math.abs(sh) < 25) {
                if (++deadTurns >= 2) return stop("My swipes stopped turning the camera — is a menu or the chat open, or is the game paused? Press ▶ again when it's back.");
            } else deadTurns = 0;
        }

        List<McVision.Trunk> ts = McVision.trunks(f);
        seen += ts.isEmpty() ? " · no trunk" : " · " + ts.size() + " trunk(s), best " + ts.get(0);
        if (ts.isEmpty()) {
            searchTurns++;
            if (++searchTotal > 48) return stop("No trees found after looking around for a long time. Walk to a forest and press ▶ again.");
            Step st = new Step();
            if (searchTurns % 7 == 0) {
                viewBefore = McVision.patch(f, McLayout.CX, McLayout.CY, 500);
                return st.walk(2500).settle(400).say("No trees around here — walking on.");
            }
            searchProfile = McVision.columnProfile(f);
            return turnBy(st, -630).settle(450).say("Looking around for a tree…");
        }
        searchTurns = 0;
        McVision.Trunk t = ts.get(0);
        boolean close = t.width >= reachWidth || (t.x0 < McLayout.CX && t.x1 > McLayout.CX && t.width > reachWidth * 0.8f);
        if (close) {
            holdAt = new float[]{t.holdX, t.holdY};
            holdBefore = McVision.patch(f, t.holdX, t.holdY, 60);
            return new Step().hold(t.holdX, t.holdY, holdMs).settle(350).say("Mining the trunk…");
        }
        // walk towards it
        Step st = new Step();
        if (Math.abs(t.cx - McLayout.CX) > 110) turnTo(st, t.cx);
        float blocks = 935f / Math.max(24, t.width);
        long ms = (long) Math.max(350, Math.min(1600, (blocks - 2.2f) / 4.3f * 1000));
        viewBefore = McVision.patch(f, McLayout.CX, McLayout.CY, 500);
        return st.walk(ms).settle(350).say(String.format(Locale.US, "Tree ahead (about %.0f blocks) — walking to it.", blocks));
    }

    /** Planks the whole plan needs, as logs. */
    private int logsNeeded() {
        int planks = (tableMade ? 0 : 4) + (fullSet ? 4 + 3 + 3 + 2 : 2 + 3);
        return (planks + 3) / 4;
    }

    // ---------------------------------------------------------------- inventory check

    private Step check(Frame f, McVision.Screen s) {
        if (s == McVision.Screen.WORLD) return openInventory("Opening the inventory…");
        McVision.Craft c = McVision.craft(f);
        logsBrokenAtCheck = logsBroken;
        int logs = c.canMake("planks") / 4;
        int planks = c.canMake("button");
        if (c.slot("crafting_table") >= 0) tableMade = true;
        logsSeen = logs + planks / 4;
        checks++;
        if (lastLogsSeen >= 0 && logsSeen <= lastLogsSeen) checksWithoutGain++; else checksWithoutGain = 0;
        lastLogsSeen = logsSeen;
        int planksNow = planks + 4 * logs;
        int full = (tableMade ? 0 : 4) + 4 + 3 + 3 + 2, min = (tableMade ? 0 : 4) + 2 + 3;
        String have = String.format(Locale.US, "%d log%s + %d planks", logs, logs == 1 ? "" : "s", planks);
        if (planksNow >= (fullSet ? full : min) || (planksNow >= min && checksWithoutGain >= 2)) {
            phase = Phase.CRAFT_INV;
            craftScreenSteps = 0;
            return new Step().settle(150).say("Have " + have + " — crafting.");
        }
        if (checksWithoutGain >= 4) {
            return stop("I'm breaking logs but they don't reach the inventory (" + have + "). Pick them up yourself and press ▶ again.");
        }
        phase = Phase.GATHER;
        return closeScreen("Have " + have + "; need about " + logsNeeded() + " logs. More wood…");
    }

    // ---------------------------------------------------------------- crafting

    /** Select the recipe, then take the result from the output slot (one craft). */
    private Step craftOnce(McVision.Craft c, String item) {
        if (item.equals(lastCraft)) sameCraftTaps++; else { lastCraft = item; sameCraftTaps = 0; }
        if (sameCraftTaps > 10) return stop("Crafting " + item.replace('_', ' ') + " isn't working — stopped.");
        if (item.equals(c.output.item) && c.output.bg != McIcons.Bg.MISSING) {
            return new Step().tap(c.outX(), c.outY()).settle(650).say("Crafting " + item.replace('_', ' ') + "…");
        }
        int[] rc = c.find(item);
        if (rc == null) return stop("Can't find the " + item.replace('_', ' ') + " recipe in the recipe book (is \"All recipes\" selected?).");
        return new Step().tap(McLayout.recipeX(rc[1]), McLayout.recipeY(rc[0])).settle(600).say("Picking the " + item.replace('_', ' ') + " recipe…");
    }

    private Step craftInventory(Frame f, McVision.Screen s) {
        if (s == McVision.Screen.WORLD) return openInventory("Opening the inventory to craft…");
        if (s == McVision.Screen.CRAFT3) { phase = Phase.CRAFT_TABLE; return craftTable(f, s); }
        if (++craftScreenSteps > 60) return stop("Crafting took too many steps — stopped.");
        McVision.Craft c = McVision.craft(f);
        if (c.slot("crafting_table") >= 0) { tableMade = true; tableSlot = c.slot("crafting_table"); }
        if (c.canMake("planks") > 0) return craftOnce(c, "planks");
        int planks = c.canMake("button");
        if (!tableMade) {
            if (c.canMake("crafting_table") > 0) return craftOnce(c, "crafting_table");
            return stop("Not enough planks for a crafting table (" + planks + ").");
        }
        // planks left after the table: sticks for the tools
        if (stickCrafts == 0) stickCraftsNeeded = (fullSet && planks >= 4 + 3 + 3 + 2) ? 2 : 1;
        if (stickCrafts < stickCraftsNeeded && c.canMake("stick") > 0 && planks >= 5) {
            if (item(c.output).equals("stick") && c.output.bg != McIcons.Bg.MISSING) stickCrafts++;
            return craftOnce(c, "stick");
        }
        if (c.slot("stick") < 0 && stickCrafts == 0) return stop("No sticks and not enough planks for them (" + planks + ").");
        tableSlot = c.slot("crafting_table");
        if (tableSlot < 0) return stop("The crafting table isn't in the hotbar — put it there and press ▶ again.");
        phase = Phase.PLACE;
        placeTries = 0;
        return closeScreen("Crafted planks, table and sticks. Placing the crafting table…");
    }

    private static String item(McIcons.Read r) { return r == null ? "" : r.item; }

    private static final float[][] PLACE_SPOTS = {{1260, 780}, {1260, 690}, {1260, 860}, {1050, 780}, {1470, 780}};

    private Step place(Frame f, McVision.Screen s) {
        if (s == McVision.Screen.CRAFT3) { phase = Phase.CRAFT_TABLE; craftScreenSteps = 0; return craftTable(f, s); }
        if (s == McVision.Screen.CRAFT2) return closeScreen("Closing the inventory…");
        if (placeTries >= PLACE_SPOTS.length) {
            return stop("Couldn't place and open the crafting table. Place it, tap it to open it, and press ▶ — I'll carry on from there.");
        }
        float[] p = PLACE_SPOTS[placeTries++];
        Step st = new Step();
        if (!tablePlaced) st.tap(McLayout.hudSlotX(tableSlot), McLayout.HUD_SLOT_Y);
        st.tap(p[0], p[1]);               // place it (with the table in hand)
        st.tap(p[0], p[1]);               // ...and tap it to open it
        tablePlaced = true;
        return st.settle(1100).say("Placing the crafting table and opening it…");
    }

    private Step craftTable(Frame f, McVision.Screen s) {
        if (s == McVision.Screen.WORLD) {
            if (phase == Phase.CRAFT_TABLE && made.contains("wooden_pickaxe")) { phase = Phase.EQUIP; return equip(f, s); }
            phase = Phase.PLACE;
            return place(f, s);
        }
        if (s == McVision.Screen.CRAFT2) return closeScreen("That's the inventory, not the table — closing it.");
        if (++craftScreenSteps > 60) return stop("Crafting tools took too many steps — stopped.");
        McVision.Craft c = McVision.craft(f);
        List<String> want = new ArrayList<>();
        want.add("wooden_pickaxe");
        if (fullSet) { want.add("wooden_axe"); want.add("wooden_sword"); }
        for (String t : want) if (c.slot(t) >= 0) made.add(t);
        for (String t : want) {
            if (made.contains(t) || skipped.contains(t)) continue;
            if (c.canMake(t) > 0) return craftOnce(c, t);
            // Missing sticks? Make more while there are planks for the tool too.
            int planks = c.canMake("button");
            int toolPlanks = t.equals("wooden_sword") ? 2 : 3;
            if (c.canMake("stick") > 0 && planks >= toolPlanks + 2 && stickCrafts < 4) {
                if (item(c.output).equals("stick") && c.output.bg != McIcons.Bg.MISSING) stickCrafts++;
                return craftOnce(c, "stick");
            }
            if (t.equals("wooden_pickaxe")) return stop("Not enough wood for a pickaxe (" + planks + " planks). Get more logs and press ▶ again.");
            skipped.add(t);
        }
        pickaxeSlot = c.slot("wooden_pickaxe");
        phase = Phase.EQUIP;
        return closeScreen("Made: " + String.join(", ", made).replace('_', ' ') + ". Closing the table…");
    }

    private Step equip(Frame f, McVision.Screen s) {
        if (s != McVision.Screen.WORLD) return closeScreen("Closing…");
        if (pickaxeSlot < 0) return done();
        int slot = pickaxeSlot;
        pickaxeSlot = -1;
        return new Step().tap(McLayout.hudSlotX(slot), McLayout.HUD_SLOT_Y).settle(400).say("Taking the pickaxe in hand.");
    }

    // ---------------------------------------------------------------- for the panel / tests

    public String status() {
        return String.format(Locale.US, "%s · logs broken %d, in inventory %d · made %s", phase.name().toLowerCase(Locale.US).replace('_', ' '),
                logsBroken, logsSeen, made.isEmpty() ? "—" : String.join(", ", made).replace('_', ' '));
    }

    /** Skip calibration (tests, or when the user already measured it). */
    public void setTurn(float k) { turnK = k; if (phase == Phase.CALIBRATE) phase = Phase.GATHER; }
}
