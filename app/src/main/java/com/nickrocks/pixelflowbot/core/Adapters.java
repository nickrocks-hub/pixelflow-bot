package com.nickrocks.pixelflowbot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The adapters that exist in this build and the built-in profiles that use them. A profile
 * whose adapterId isn't listed here is handled by the shared core alone (calibration state).
 */
public final class Adapters {
    private Adapters() { }

    public static final class Info {
        public final String id, title, works, limits;
        public final GameProfile.Pace pace;

        Info(String id, String title, GameProfile.Pace pace, String works, String limits) {
            this.id = id; this.title = title; this.pace = pace; this.works = works; this.limits = limits;
        }
    }

    public static final Info NONOGRAM_SIM = new Info("nonogram-sim", "Built-in Nonogram", GameProfile.Pace.TURN_BASED,
            "Exact board state; seeded unique puzzles with separate training/evaluation sets; constraint-solver baseline; "
                    + "learning agent (linear Q-learning) with training, evaluation and play modes.",
            "Simulator only — it is our own game, not an external app.");

    public static final Info NONOGRAM_COM = new Info("nonogram-com", "Nonogram.com reader + solver", GameProfile.Pace.TURN_BASED,
            "Reads grid, clues (incl. two-digit and frozen ❄), cells, red mistakes, hearts and the fill/cross switch from the screen; "
                    + "solves with proven logic, fills by swipes, checks the board, presses Next Level. Records every session as experience.",
            "Plays with the exact solver, not a learned policy. Cells under hidden clues are left until the clue shows.");

    public static List<Info> all() {
        List<Info> l = new ArrayList<>();
        l.add(NONOGRAM_SIM);
        l.add(NONOGRAM_COM);
        return l;
    }

    public static Info get(String id) {
        if (id == null) return null;
        for (Info i : all()) if (i.id.equals(id)) return i;
        return null;
    }

    /** Built-in profiles, created on first launch. */
    public static List<GameProfile> builtInProfiles() {
        List<GameProfile> l = new ArrayList<>();
        GameProfile a = new GameProfile();
        a.id = "nonogram-ingame";
        a.name = "Nonogram (built-in)";
        a.kind = GameProfile.Kind.BUILT_IN;
        a.adapterId = NONOGRAM_SIM.id;
        a.support = GameProfile.Support.SUPPORTED;
        a.pace = GameProfile.Pace.TURN_BASED;
        a.notes = "Test bed for the learning agent.";
        l.add(a);
        GameProfile b = new GameProfile();
        b.id = "nonogram-com";
        b.name = "Nonogram.com";
        b.kind = GameProfile.Kind.EXTERNAL_APP;
        b.packageName = "com.easybrain.nonogram";
        b.adapterId = NONOGRAM_COM.id;
        b.support = GameProfile.Support.SUPPORTED;
        b.pace = GameProfile.Pace.TURN_BASED;
        b.notes = "Checked on real screens: normal levels, frozen clues, Challenge mode.";
        l.add(b);
        return l;
    }
}
