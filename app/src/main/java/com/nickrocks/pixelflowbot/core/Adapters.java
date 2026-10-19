package com.nickrocks.pixelflowbot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The game players (adapters) that exist in this build, and the library profiles that ship with
 * the app. A game added by the user whose package matches a shipped profile gets that player;
 * any other game stays in calibration and the app says it can't play it.
 */
public final class Adapters {
    private Adapters() { }

    public static final class Info {
        public final String id, title, works, limits, packageName;
        public final GameProfile.Pace pace;

        Info(String id, String title, String packageName, GameProfile.Pace pace, String works, String limits) {
            this.id = id; this.title = title; this.packageName = packageName; this.pace = pace; this.works = works; this.limits = limits;
        }
    }

    public static final Info NONOGRAM_COM = new Info("nonogram-com", "Nonogram.com reader + solver", "com.easybrain.nonogram",
            GameProfile.Pace.TURN_BASED,
            "Reads grid, clues (incl. two-digit and frozen ❄), cells, red mistakes, hearts and the fill/cross switch from the screen; "
                    + "solves with proven logic, fills by swipes, checks the board, presses Next Level. Records every session as experience.",
            "Plays with the exact solver, not a learned policy. Cells under hidden clues are left until the clue shows.");

    public static final Info CANDY_CRUSH = new Info("candy-crush", "Candy Crush board reader + move ranker", "com.king.candycrushsaga",
            GameProfile.Pace.TURN_BASED,
            "Finds the board, reads the candies, ranks every legal swap (matches, specials, cascades) and plays the best one; waits for the board "
                    + "to settle; pauses on popups, boosters, ads and level ends; goes to the next level with a button you teach once.",
            "Ranks one move at a time by hand-written scoring — it doesn't read each level's goal (jelly, ingredients, orders) yet, "
                    + "so it plays for strong matches rather than the objective. No learned policy yet. Sessions are logged to "
                    + "Download/AIGameBot but not yet into the experience store.");

    public static final Info ARROWS = new Info("arrows-escape", "Arrows puzzle reader + solver", "com.oakever.arrows",
            GameProfile.Pace.TURN_BASED,
            "Reads every arrow on the board (its bends and which end is the head) from the screen, finds the arrows with a clear path "
                    + "to the edge, taps one, waits for it to leave and reads again until the board is empty; then the next-level button you teach once.",
            "Exact rule-based player, no AI model. Assumes the classic rules (an arrow leaves along its head's direction if nothing is in the way); "
                    + "pauses if a tapped arrow doesn't move. Read correctly on a level 7 screenshot (53 arrows); other layouts not seen yet.");

    public static final Info FLOW = new Info("flow-free", "Flow Free reader + exact solver", "com.bigduckgames.flow",
            GameProfile.Pace.TURN_BASED,
            "Reads the grid (any size up to 20×20) and the coloured dots from the screen, solves the whole puzzle exactly (every pipe "
                    + "connected, every cell filled), then draws each pipe in one drag; the next-level button you teach once.",
            "Exact rule-based player, no AI model. Not for the bridge, hexagon or warp variants yet. Checked on a 5×5 screenshot and "
                    + "on rendered boards up to 14×14.");

    public static final Info MINECRAFT = new Info("minecraft-start", "Minecraft first-minutes player", "com.mojang.minecraftpe",
            GameProfile.Pace.REAL_TIME,
            "Survival, touch controls: finds tree trunks on screen, walks to them, holds to mine the logs, counts them in the recipe book, "
                    + "then crafts planks, a crafting table and sticks, places the table and makes a wooden pickaxe (and an axe and sword when "
                    + "there's wood), and takes the pickaxe in hand. Stops if it takes damage or sees a screen it doesn't know.",
            "Only the first minutes of the game — not mining, the Nether or the Ender Dragon. Trees are found by bark colour (checked on "
                    + "jungle trees from your recording); birch is missed. Calibrated to your 2520×1080 screen at the default GUI scale. "
                    + "Not tested on the phone yet.");

    public static List<Info> all() {
        List<Info> l = new ArrayList<>();
        l.add(NONOGRAM_COM);
        l.add(CANDY_CRUSH);
        l.add(ARROWS);
        l.add(FLOW);
        l.add(MINECRAFT);
        return l;
    }

    public static Info get(String id) {
        if (id == null) return null;
        for (Info i : all()) if (i.id.equals(id)) return i;
        return null;
    }

    public static Info forPackage(String pkg) {
        if (pkg == null) return null;
        for (Info i : all()) if (pkg.equals(i.packageName)) return i;
        return null;
    }

    /** Which floating-panel player runs this adapter: "nonogram", "candy", "arrows", "flow", "minecraft", or "none". */
    public static String panelKey(String adapterId) {
        if (NONOGRAM_COM.id.equals(adapterId)) return "nonogram";
        if (CANDY_CRUSH.id.equals(adapterId)) return "candy";
        if (ARROWS.id.equals(adapterId)) return "arrows";
        if (FLOW.id.equals(adapterId)) return "flow";
        if (MINECRAFT.id.equals(adapterId)) return "minecraft";
        return "none";
    }

    /** Ids of profiles from earlier versions that no longer exist (built-in practice games). */
    public static final String[] RETIRED = {"nonogram-ingame", "block-puzzle", "dodge"};

    /** Profiles that ship with the app, one per adapter. */
    public static List<GameProfile> builtInProfiles() {
        List<GameProfile> l = new ArrayList<>();
        GameProfile b = new GameProfile();
        b.id = "nonogram-com";
        b.name = "Nonogram.com";
        b.kind = GameProfile.Kind.EXTERNAL_APP;
        b.packageName = NONOGRAM_COM.packageName;
        b.adapterId = NONOGRAM_COM.id;
        b.support = GameProfile.Support.SUPPORTED;
        b.pace = GameProfile.Pace.TURN_BASED;
        b.notes = "Checked on real screens: normal levels, frozen clues, Challenge mode.";
        l.add(b);
        GameProfile c = new GameProfile();
        c.id = "candy-crush";
        c.name = "Candy Crush Saga";
        c.kind = GameProfile.Kind.EXTERNAL_APP;
        c.packageName = CANDY_CRUSH.packageName;
        c.adapterId = CANDY_CRUSH.id;
        c.support = GameProfile.Support.SUPPORTED;
        c.pace = GameProfile.Pace.TURN_BASED;
        c.notes = "Player restored from build 17 (field-tested then).";
        l.add(c);
        GameProfile ar = new GameProfile();
        ar.id = "arrows-escape";
        ar.name = "Amaze Go!";
        ar.kind = GameProfile.Kind.EXTERNAL_APP;
        ar.packageName = ARROWS.packageName;
        ar.adapterId = ARROWS.id;
        ar.support = GameProfile.Support.SUPPORTED;
        ar.pace = GameProfile.Pace.TURN_BASED;
        ar.notes = "Built from a level 7 screenshot; reads all 53 arrows and clears the level in simulation.";
        l.add(ar);
        GameProfile fl = new GameProfile();
        fl.id = "flow-free";
        fl.name = "Flow Free";
        fl.kind = GameProfile.Kind.EXTERNAL_APP;
        fl.packageName = FLOW.packageName;
        fl.adapterId = FLOW.id;
        fl.support = GameProfile.Support.SUPPORTED;
        fl.pace = GameProfile.Pace.TURN_BASED;
        fl.notes = "Built from a 5×5 screenshot; solver checked on rendered boards up to 14×14.";
        l.add(fl);
        GameProfile mc = new GameProfile();
        mc.id = "minecraft-start";
        mc.name = "Minecraft";
        mc.kind = GameProfile.Kind.EXTERNAL_APP;
        mc.packageName = MINECRAFT.packageName;
        mc.adapterId = MINECRAFT.id;
        mc.support = GameProfile.Support.SUPPORTED;
        mc.pace = GameProfile.Pace.REAL_TIME;
        mc.notes = "Built from your screen recording: logs → planks → crafting table → wooden pickaxe.";
        l.add(mc);
        return l;
    }
}
