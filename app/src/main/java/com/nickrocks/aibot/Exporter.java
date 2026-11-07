package com.nickrocks.aibot;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.nickrocks.aibot.core.RunHistoryRepository;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Saves run reports to Downloads/NickRocksAI on the phone (visible in the Files app). */
public final class Exporter {
    private Exporter() { }

    public static final String FOLDER = "NickRocksAI";

    /** Returns a message for the user. Safe to call off the main thread. */
    public static String export(Context c, RunHistoryRepository repo, String runId, String format) {
        String f = format.toUpperCase(Locale.ROOT);
        String body, mime, ext;
        switch (f) {
            case "JSON": body = repo.exportJson(runId); mime = "application/json"; ext = "json"; break;
            case "CSV": body = repo.exportCsv(runId); mime = "text/csv"; ext = "csv"; break;
            default: body = repo.exportTxt(runId); mime = "text/plain"; ext = "txt";
        }
        String name = runId + "." + ext;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = c.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "Couldn't create the file in Downloads";
                try (OutputStream out = cr.openOutputStream(uri)) {
                    if (out == null) return "Couldn't open the file in Downloads";
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
                dir.mkdirs();
                try (OutputStream out = new FileOutputStream(new File(dir, name))) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            return "Saved to Downloads/" + FOLDER + "/" + name;
        } catch (Exception e) {
            return "Couldn't save: " + e.getMessage();
        }
    }
}
