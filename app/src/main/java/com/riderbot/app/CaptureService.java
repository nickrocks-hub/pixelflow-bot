package com.riderbot.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.riderbot.app.core.RiderBrain;

import java.nio.ByteBuffer;

/**
 * Foreground service that mirrors the screen at low resolution (360 px wide),
 * runs every frame through RiderBrain and tells the accessibility service
 * whether to hold the screen.
 */
public class CaptureService extends Service {

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_STOP = "com.riderbot.app.STOP";
    private static final String CHANNEL = "riderbot";
    private static final int NOTIF_ID = 7;
    private static final int CAPTURE_WIDTH = 360;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;

    private final RiderBrain.Params params = new RiderBrain.Params();
    private RiderBrain brain;
    private int[] px;
    private byte[] row;
    private long lastStatusMs;
    private boolean lastHold;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> BotState.applyTo(p, params);

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (projection != null) {            // already capturing: just (re)arm the bot
            BotState.resetRequested = true;
            BotState.running = true;
            notifyToggle();
            return START_NOT_STICKY;
        }
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (RuntimeException e) {
            projection = null;
        }
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        SharedPreferences prefs = BotState.prefs(this);
        BotState.applyTo(prefs, params);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        brain = new RiderBrain(params);

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(CaptureService.this::stopSelf); }
        }, main);
        startCapture();

        BotState.captureActive = true;
        BotState.resetRequested = true;
        BotState.running = true;
        notifyToggle();
        return START_NOT_STICKY;
    }

    private void startCapture() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int rw, rh;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            rw = b.width(); rh = b.height();
        } else {
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
            rw = m.widthPixels; rh = m.heightPixels;
        }
        int w = CAPTURE_WIDTH;
        int h = Math.round((float) CAPTURE_WIDTH * rh / rw) & ~1;
        int dpi = getResources().getDisplayMetrics().densityDpi;

        thread = new HandlerThread("rider-vision");
        thread.start();
        Handler bg = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, bg);
        display = projection.createVirtualDisplay("RiderBot", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, bg);
    }

    private void onImage(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); } catch (RuntimeException e) { return; }
        if (img == null) return;
        int w, h;
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rs = plane.getRowStride(), ps = plane.getPixelStride();
            w = img.getWidth(); h = img.getHeight();
            if (px == null || px.length != w * h) px = new int[w * h];
            if (row == null || row.length < rs) row = new byte[rs];
            for (int y = 0; y < h; y++) {
                buf.position(y * rs);
                int len = Math.min(rs, buf.remaining());
                buf.get(row, 0, len);
                int o = 0, i = y * w;
                for (int x = 0; x < w; x++, o += ps) {
                    px[i + x] = ((row[o] & 0xFF) << 16) | ((row[o + 1] & 0xFF) << 8) | (row[o + 2] & 0xFF);
                }
            }
        } catch (RuntimeException e) {
            return;
        } finally {
            img.close();
        }

        BotAccessibilityService svc = BotAccessibilityService.instance;
        if (!BotState.running) {
            if (lastHold && svc != null) svc.setHolding(false);
            lastHold = false;
            return;
        }
        if (BotState.resetRequested) {
            BotState.resetRequested = false;
            brain.reset();
        }
        long now = SystemClock.uptimeMillis();
        boolean hold = brain.onFrame(px, w, h, now);
        if (svc != null) {
            svc.setHolding(hold);
            if (now - lastStatusMs > 150) {
                lastStatusMs = now;
                svc.postStatus(brain.status);
            }
        }
        lastHold = hold;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Rider Bot", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Rider Bot is watching the screen")
                .setContentText("Use the floating button to start or stop.")
                .setSmallIcon(R.drawable.ic_stat_bot)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop capture", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void notifyToggle() {
        BotAccessibilityService svc = BotAccessibilityService.instance;
        if (svc != null) svc.refreshToggle();
    }

    @Override
    public void onDestroy() {
        BotState.running = false;
        BotState.captureActive = false;
        BotAccessibilityService svc = BotAccessibilityService.instance;
        if (svc != null) { svc.setHolding(false); svc.refreshToggle(); }
        BotState.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener);
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) {
            try { projection.stop(); } catch (RuntimeException ignored) { }
        }
        if (thread != null) thread.quitSafely();
        display = null; reader = null; projection = null; thread = null;
        super.onDestroy();
    }
}
