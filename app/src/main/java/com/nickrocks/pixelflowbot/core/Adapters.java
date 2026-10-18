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

    public static List<Info> all() {
        List<Info> l = new ArrayList<>();
        l.add(NONOGRAM_COM);
        l.add(CANDY_CRUSH);
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

    /** Which floating-panel player runs this adapter: "nonogram", "candy", or "none". */
    public static String panelKey(String adapterId) {
        if (NONOGRAM_COM.id.equals(adapterId)) return "nonogram";
        if (CANDY_CRUSH.id.equals(adapterId)) return "candy";
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
        return l;
    }
}
