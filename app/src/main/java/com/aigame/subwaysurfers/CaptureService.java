package com.aigame.subwaysurfers;

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

import com.aigame.subwaysurfers.core.SurferBrain;

import java.nio.ByteBuffer;

/**
 * Foreground service that mirrors the screen at low resolution (360 px wide),
 * runs every frame through SurferBrain and hands any move (left, right, jump,
 * roll) to the swipe service. Everything stays on the phone.
 */
public class CaptureService extends Service {

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_STOP = "com.aigame.subwaysurfers.STOP";
    private static final String CHANNEL = "aigame_subway";
    private static final int NOTIF_ID = 7;
    private static final int CAPTURE_WIDTH = 360;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;

    private final SurferBrain.Params params = new SurferBrain.Params();
    private SurferBrain brain;
    private int[] px;
    private byte[] row;
    private long lastStatusMs;

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
        brain = new SurferBrain(params);
        if (prefs.getBoolean(BotState.K_LOGS, true)) {
            log = new SessionLog(this, (name, ok, err) -> main.post(() -> {
                String msg = ok ? "Log saved to Downloads/" + SessionLog.FOLDER + "/" + name
                        : "Couldn't save log: " + err;
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
                SwipeService s2 = SwipeService.instance;
                if (s2 != null) s2.flash(ok ? "Log saved ✓" : "Log not saved");
            }));
        }

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

        if (log != null) log.setInfo(infoText(w, h));
        // high priority so the analysis runs on a fast core, ahead of the logger
        thread = new HandlerThread("subway-vision", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        thread.start();
        Handler bg = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, bg);
        display = projection.createVirtualDisplay("AiGameSubway", w, h, dpi,
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

        SwipeService svc = SwipeService.instance;
        long now = SystemClock.uptimeMillis();
        boolean run = BotState.running;
        if (run != wasRunning) {
            wasRunning = run;
            if (log != null) {
                log.event(now, run ? "START" : "STOP");
                if (!run && loggedSinceSave) { log.save("stop"); loggedSinceSave = false; }
            }
        }
        if (!run) return;
        if (BotState.resetRequested) {
            BotState.resetRequested = false;
            brain.reset();
        }
        if (!BotState.gameInFront()) {
            if (log != null && !BotState.foregroundPackage.equals(lastFg)) {
                lastFg = BotState.foregroundPackage;
                log.event(now, "not in game, in front: " + lastFg);
            }
            if (svc != null && now - lastStatusMs > 500) { lastStatusMs = now; svc.postStatus("Waiting for Subway Surfers (in front: " + BotState.foregroundPackage + ")"); }
            brain.reset();
            return;
        }
        SurferBrain.Move m = brain.onFrame(px, w, h, now);
        long took = SystemClock.uptimeMillis() - now;
        boolean frozen = updateFrozen(px, w, h);
        if (log != null) {
            log.frame(px, w, h, now, took + "ms" + (frozen ? " FZ " : " ") + brain.logLine(m));
            loggedSinceSave = true;
            detectRunEnd(now, brain.last != null && brain.last.ok, frozen);
        }
        if (svc != null) {
            if (m != SurferBrain.Move.NONE) svc.perform(m);
            if (now - lastStatusMs > 150 || m != SurferBrain.Move.NONE) {
                lastStatusMs = now;
                svc.postStatus(brain.status);
            }
        }
    }

    // ------------------------------------------------------------ logging

    private SessionLog log;
    private boolean wasRunning, loggedSinceSave, endSaved;
    private long lastFrameMs, activeMs, frozenSince = -1, lostSince = -1;
    private int[] prevLuma;
    private String lastFg = "";

    /** True when the picture hardly changed since the last frame (crashed, paused, menu). */
    private boolean updateFrozen(int[] p, int w, int h) {
        int step = 8, n = 0;
        int cols = w / step, rows = h / step;
        if (prevLuma == null || prevLuma.length != cols * rows) { prevLuma = new int[cols * rows]; return false; }
        long diff = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int c = p[(y * step) * w + x * step];
                int l = ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
                diff += Math.abs(l - prevLuma[n]);
                prevLuma[n++] = l;
            }
        }
        return diff / (double) n / 3.0 < 1.5;
    }

    /** A run has ended when, after playing a while, the screen freezes or the tracks vanish. */
    private void detectRunEnd(long now, boolean tracked, boolean frozen) {
        long dt = lastFrameMs == 0 ? 0 : Math.min(500, now - lastFrameMs);
        lastFrameMs = now;
        if (tracked && !frozen) {
            activeMs += dt;
            if (activeMs > 3000) endSaved = false;
        }
        frozenSince = frozen ? (frozenSince < 0 ? now : frozenSince) : -1;
        lostSince = !tracked ? (lostSince < 0 ? now : lostSince) : -1;
        boolean ended = (frozenSince >= 0 && now - frozenSince > 1200) || (lostSince >= 0 && now - lostSince > 2500);
        if (ended && !endSaved && activeMs > 3000) {
            endSaved = true;
            activeMs = 0;
            log.event(now, "RUN END (" + (frozenSince >= 0 ? "screen froze" : "tracks lost") + ")");
            log.save("crash");
            loggedSinceSave = false;
            brain.reset();
        }
    }

    private String infoText(int w, int h) {
        StringBuilder sb = new StringBuilder();
        sb.append("app: AI GAME SUBWAY SURFERS ").append(BuildConfigCompat.version(this)).append('\n');
        sb.append("phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("capture: ").append(w).append('x').append(h).append('\n');
        SurferBrain.Params q = params;
        sb.append("settings: react=").append(q.reactMs).append(" latency=").append(q.latencyMs)
                .append(" safeExtra=").append(q.safeExtraMs).append(" laneCooldown=").append(q.laneCooldownMs)
                .append(" jumpLow=").append(q.jumpLowObstacles).append(" preferMiddle=").append(q.preferMiddle)
                .append(" seekCoins=").append(q.seekCoins).append(" runnerOffset=").append(q.runnerOffsetPct).append('\n');
        sb.append("log line: time_ms analyse_ms [FZ=frozen] vp P lane(-1..1) speed | per lane k: y(obstacle row) s(span) AT C(confirmed) T(tall) tti(ms) c(coins) => move | status\n");
        return sb.toString();
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "AI GAME SUBWAY SURFERS", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("AI GAME SUBWAY SURFERS is watching the screen")
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
        SwipeService svc = SwipeService.instance;
        if (svc != null) svc.refreshToggle();
    }

    @Override
    public void onDestroy() {
        BotState.running = false;
        BotState.captureActive = false;
        SwipeService svc = SwipeService.instance;
        if (svc != null) svc.refreshToggle();
        BotState.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener);
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) {
            try { projection.stop(); } catch (RuntimeException ignored) { }
        }
        if (thread != null) thread.quitSafely();
        if (log != null) log.close(loggedSinceSave ? "capture-ended" : null);
        log = null;
        display = null; reader = null; projection = null; thread = null;
        super.onDestroy();
    }
}
