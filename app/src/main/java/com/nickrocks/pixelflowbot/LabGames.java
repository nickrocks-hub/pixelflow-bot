package com.nickrocks.pixelflowbot;

import android.content.Context;

import com.nickrocks.pixelflowbot.game.blocks.BlockGame;
import com.nickrocks.pixelflowbot.game.dodge.DodgeGame;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** The simulated lab games in this build, by adapter id, with their runtimes and screens. */
final class LabGames {
    private LabGames() { }

    private static final Map<String, LabRuntime<?>> runtimes = new HashMap<>();

    static boolean has(String adapterId) {
        return BlockGame.ID.equals(adapterId) || DodgeGame.ID.equals(adapterId);
    }

    static synchronized LabRuntime<?> runtime(Context c, String adapterId) {
        LabRuntime<?> r = runtimes.get(adapterId);
        if (r != null) return r;
        File ai = new File(c.getApplicationContext().getFilesDir(), "ai");
        if (BlockGame.ID.equals(adapterId)) r = new LabRuntime<>(new BlockGame(), ai);
        else if (DodgeGame.ID.equals(adapterId)) r = new LabRuntime<>(new DodgeGame(), ai);
        else throw new IllegalArgumentException("no lab game " + adapterId);
        runtimes.put(adapterId, r);
        return r;
    }

    @SuppressWarnings("unchecked")
    static <S> LabScreen<S> screen(Context c, String adapterId) {
        if (BlockGame.ID.equals(adapterId)) return (LabScreen<S>) new BlockScreen(c);
        if (DodgeGame.ID.equals(adapterId)) return (LabScreen<S>) new DodgeScreen(c);
        throw new IllegalArgumentException("no screen for " + adapterId);
    }
}
