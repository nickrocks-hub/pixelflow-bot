package com.aigame.minesweeper;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Records what the bot saw and did, and saves it to the phone's Downloads
 * folder ("Download/AI GAME MINESWEEPER/") as a .zip:
 *   log.txt   one line per frame (time, board reading, taps)
 *   info.txt  phone, screen and settings
 *   frames/   the last few seconds of screen frames as JPEGs (named by time),
 *             so the run can be replayed through the same vision code.
 * A zip is written when a run ends (crash / game over) and when the bot stops.
 */
public final class SessionLog {

    public static final String FOLDER = "AI GAME MINESWEEPER";
    private static final int KEEP_FRAMES_MS = 6000;
    private static final int FRAME_EVERY_MS = 80;
    private static final int MAX_TEXT = 6_000_000;

    private final Context ctx;
    private final HandlerThread thread = new HandlerThread("mines-log");
    private final Handler bg;
    private final long t0;
    private final String sessionName;
    private final StringBuilder text = new StringBuilder(1 << 16);
    private final ArrayDeque<Object[]> frames = new ArrayDeque<>();   // {Long t, byte[] jpeg}
    private final ArrayBlockingQueue<int[]> pool = new ArrayBlockingQueue<>(3);
    private Bitmap bmp;
    private long lastFrameMs = -FRAME_EVERY_MS;
    private int allocated = 0;
    private int saves = 0;
    private String info = "";

    public interface Listener { void onSaved(String name, boolean ok, String error); }
    private final Listener listener;

    public SessionLog(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
        thread.start();
        bg = new Handler(thread.getLooper());
        t0 = android.os.SystemClock.uptimeMillis();
        sessionName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    public long elapsed(long uptimeMs) { return uptimeMs - t0; }

    public void setInfo(String s) { bg.post(() -> info = s); }

    /** Called from the vision thread for every analysed frame. */
    public void frame(int[] px, int w, int h, long uptimeMs, String line) {
        final long t = uptimeMs - t0;
        final String l = t + " " + line;
        int[] copy = null;
        if (t - lastFrameMs >= FRAME_EVERY_MS) {
            copy = pool.poll();
            if (copy == null && allocated < 3) { copy = new int[w * h]; allocated++; }   // vision thread only
            if (copy != null && copy.length != w * h) copy = new int[w * h];
            if (copy != null) {
                for (int i = 0; i < copy.length; i++) copy[i] = px[i] | 0xFF000000;
                lastFrameMs = t;
            }
        }
        final int[] c = copy;
        bg.post(() -> {
            appendLine(l);
            if (c != null) {
                encode(c, w, h, t);
                pool.offer(c);
            }
        });
    }

    /** Free-form event line. */
    public void event(long uptimeMs, String s) {
        final String l = (uptimeMs - t0) + " ## " + s;
        bg.post(() -> appendLine(l));
    }

    /** Write a zip now (on the log thread). */
    public void save(String reason) {
        bg.post(() -> writeZip(reason));
    }

    /** Save (if asked) and stop the log thread after pending work. */
    public void close(String reason) {
        if (reason != null) save(reason);
        thread.quitSafely();
    }

    // ------------------------------------------------------------- internals

    private void appendLine(String l) {
        text.append(l).append('\n');
        if (text.length() > MAX_TEXT) {
            int cut = text.indexOf("\n", text.length() - MAX_TEXT / 2);
            text.delete(0, cut + 1);
            text.insert(0, "... (older lines dropped)\n");
        }
    }

    private void encode(int[] argb, int w, int h, long t) {
        try {
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            bmp.setPixels(argb, 0, w, 0, 0, w, h);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(40_000);
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bo);
            frames.addLast(new Object[]{t, bo.toByteArray()});
            while (!frames.isEmpty() && t - (Long) frames.peekFirst()[0] > KEEP_FRAMES_MS) frames.removeFirst();
        } catch (RuntimeException ignored) { }
    }

    private void writeZip(String reason) {
        saves++;
        String name = "minesweeper_" + sessionName + "_" + String.format(Locale.US, "%02d", saves) + "_" + reason + ".zip";
        OutputStream os = null;
        Uri uri = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = ctx.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IOException("Downloads folder not available");
                os = cr.openOutputStream(uri);
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
                os = new FileOutputStream(new File(dir, name));
            }
            if (os == null) throw new IOException("can't open file");
            try (ZipOutputStream zip = new ZipOutputStream(os)) {
                put(zip, "info.txt", ("reason: " + reason + "\n" + info).getBytes(StandardCharsets.UTF_8));
                put(zip, "log.txt", text.toString().getBytes(StandardCharsets.UTF_8));
                for (Object[] f : frames) {
                    put(zip, String.format(Locale.US, "frames/t%08d.jpg", (Long) f[0]), (byte[]) f[1]);
                }
            }
            os = null;
            if (uri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, v, null, null);
            }
            if (listener != null) listener.onSaved(name, true, null);
        } catch (IOException | RuntimeException e) {
            if (os != null) try { os.close(); } catch (IOException ignored) { }
            if (uri != null) try { ctx.getContentResolver().delete(uri, null, null); } catch (RuntimeException ignored) { }
            if (listener != null) listener.onSaved(name, false, e.getMessage());
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }
}
