package com.nickrocks.pixelflowbot;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;


import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One log file per auto-play session, kept on the phone only:
 * Download/AIGameBot/pfbot_YYYYMMDD_HHMMSS.txt, one JSON object per line.
 */
final class Recorder {
    private static final String TAG = "PFBot.Rec";
    private final Context ctx;
    private final String name;
    private Uri uri;
    private File file;
    private boolean failed = false;

    Recorder(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.name = "aiminecraftbot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
    }

    String where() { return "Download/AIGameBot/" + name; }

    synchronized void line(String json) {
        if (failed) return;
        try {
            byte[] bytes = (json + "\n").getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (uri == null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/AIGameBot");
                    uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) throw new IllegalStateException("could not create log file");
                }
                try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wa")) {
                    os.write(bytes);
                }
            } else {
                if (file == null) file = new File(ctx.getExternalFilesDir("logs"), name);
                try (FileOutputStream os = new FileOutputStream(file, true)) { os.write(bytes); }
            }
        } catch (Exception e) {
            Log.e(TAG, "log write failed", e);
            failed = true;
        }
    }

    // ---------- JSON helpers (no library needed) ----------

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }
}
