package com.nickrocks.aibot;

import android.content.Context;
import android.content.SharedPreferences;

import com.nickrocks.aibot.core.AISettings;
import com.nickrocks.aibot.core.Json;
import com.nickrocks.aibot.core.RunHistoryRepository;
import com.nickrocks.aibot.core.ScreenLayout;

import java.io.File;

/** Stores the AI settings, the control layout and the chosen game app. */
public final class AISettingsManager {
    private AISettingsManager() { }

    private static final String PREFS = "nickrocks_ai";
    private static final String K_SETTINGS = "settings", K_LAYOUT = "layout", K_GAME = "game_package",
            K_PANEL_X = "panel_x", K_PANEL_Y = "panel_y";

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static AISettings settings(Context c) {
        return AISettings.fromMap(Json.obj(prefs(c).getString(K_SETTINGS, "{}")));
    }

    public static void save(Context c, AISettings s) { prefs(c).edit().putString(K_SETTINGS, Json.write(s.toMap())).apply(); }

    public static ScreenLayout layout(Context c) { return ScreenLayout.parse(prefs(c).getString(K_LAYOUT, "")); }

    public static void save(Context c, ScreenLayout l) { prefs(c).edit().putString(K_LAYOUT, l.serialize()).apply(); }

    public static void resetLayout(Context c) { prefs(c).edit().remove(K_LAYOUT).apply(); }

    public static String gamePackage(Context c) { return prefs(c).getString(K_GAME, ""); }

    public static void setGamePackage(Context c, String p) { prefs(c).edit().putString(K_GAME, p).apply(); }

    public static int[] panelPos(Context c, int defX, int defY) {
        SharedPreferences p = prefs(c);
        return new int[]{p.getInt(K_PANEL_X, defX), p.getInt(K_PANEL_Y, defY)};
    }

    public static void savePanelPos(Context c, int x, int y) { prefs(c).edit().putInt(K_PANEL_X, x).putInt(K_PANEL_Y, y).apply(); }

    /** Runs, logs and learned experience live in the app's private storage (kept until uninstall). */
    public static RunHistoryRepository repo(Context c) {
        return new RunHistoryRepository(new File(c.getFilesDir(), "ai"));
    }
}
