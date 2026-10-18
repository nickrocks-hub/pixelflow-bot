package com.nickrocks.pixelflowbot;

import android.content.Context;
import android.util.Log;

import com.nickrocks.pixelflowbot.core.Adapters;
import com.nickrocks.pixelflowbot.core.ExperienceStore;
import com.nickrocks.pixelflowbot.core.GameProfile;
import com.nickrocks.pixelflowbot.core.ProfileStore;

import java.io.File;

/**
 * App-wide access to the shared agent core: game profiles and stored experience.
 * Everything lives in the app's private storage (files/profiles, files/experience) and never
 * leaves the phone unless the user exports it.
 */
final class AgentPlatform {
    private static final String TAG = "AIGameBot.Platform";
    private static AgentPlatform instance;

    final ProfileStore profiles;
    final ExperienceStore experience;
    final File root;

    static synchronized AgentPlatform get(Context c) {
        if (instance == null) instance = new AgentPlatform(c.getApplicationContext());
        return instance;
    }

    private AgentPlatform(Context ctx) {
        root = ctx.getFilesDir();
        profiles = new ProfileStore(new File(root, "profiles"));
        experience = new ExperienceStore(new File(root, "experience"));
        // Retired built-in practice games from v1.1–v1.4.
        for (String id : Adapters.RETIRED) { profiles.delete(id); experience.clear(id); }
        for (String d : new String[]{"ai/nonogram-game", "ai/nonogram-game-assisted", "ai/blocks-sim", "ai/dodge-sim"}) deleteTree(new File(root, d));
        try {
            profiles.adoptBuiltIns(Adapters.builtInProfiles());
        } catch (Exception e) { Log.w(TAG, "couldn't set up the shipped profiles", e); }
    }

    /** The current version of a profile; falls back to the shipped default if storage fails. */
    GameProfile profile(String id) {
        GameProfile p = profiles.get(id);
        if (p != null) return p;
        for (GameProfile b : Adapters.builtInProfiles()) if (b.id.equals(id)) return b;
        return null;
    }

    /** The library profile for an installed app, or null if it isn't in the library. */
    GameProfile profileForPackage(String pkg) { return profiles.forPackage(pkg); }

    private static void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File x : fs) deleteTree(x);
        f.delete();
    }
}
