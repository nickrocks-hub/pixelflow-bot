package com.nickrocks.pixelflowbot;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Settings for the on-device AI agent: which model file (copied into this app's private storage),
 * GPU or CPU, and session limits. Nothing here involves the internet.
 */
final class LocalSettings {
    private final SharedPreferences p;
    private final Context ctx;

    LocalSettings(Context c) {
        ctx = c.getApplicationContext();
        p = ctx.getSharedPreferences("local_ai", Context.MODE_PRIVATE);
    }

    /** Where imported models live (app-private; removed with the app). */
    File modelDir() {
        File d = new File(ctx.getFilesDir(), "models");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    String modelPath() { return p.getString("model_path", ""); }
    String modelName() { String n = p.getString("model_name", ""); return n.isEmpty() ? "on-device model" : n; }
    boolean preferGpu() { return p.getBoolean("gpu", true); }
    long settleMs() { return p.getLong("settle_ms", 1200); }
    int maxSteps() { return p.getInt("max_steps", 300); }

    /** A model file has been imported and is still there. */
    boolean ready() {
        String m = modelPath();
        return !m.isEmpty() && new File(m).isFile();
    }

    void setModel(String path, String name) { p.edit().putString("model_path", path).putString("model_name", name).apply(); }
    void clearModel() { p.edit().remove("model_path").remove("model_name").apply(); }
    void setGpu(boolean on) { p.edit().putBoolean("gpu", on).apply(); }
    void setSettleMs(long v) { p.edit().putLong("settle_ms", v).apply(); }
    void setMaxSteps(int v) { p.edit().putInt("max_steps", v).apply(); }
}
