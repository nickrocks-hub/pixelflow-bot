package com.aigame.minesweeper;

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

import com.aigame.minesweeper.core.MineBrain;
import com.aigame.minesweeper.core.MineVision;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Foreground service that mirrors the screen (540 px wide), runs each frame through MineBrain and
 * hands its taps to the tap service. When the screen goes still, it re-checks the last frame, so
 * the bot never waits for a picture that won't change. Everything stays on the phone.
 */
public class CaptureService extends Service {

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_STOP = "com.aigame.minesweeper.STOP";
    private static final String CHANNEL = "aigame_minesweeper";
    private static final int NOTIF_ID = 9;
    private static final int CAPTURE_WIDTH = 540;
    private static final long QUIET_MS = 90;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler bg;

    private final MineBrain.Params params = new MineBrain.Params();
    private MineBrain brain;
    private int[] px;
    private byte[] row;
    private int fw, fh;
    private float scaleX = 1, scaleY = 1;
    private long lastFrameAt, lastStatusAt;
    private int frameSeq;
    private boolean wasRunning;
    private String lastFg = "";
    private SessionLog log;
    private MineVision.Geometry panelCheckedFor;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (p, key) -> BotState.applyTo(p, params);

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForegroundCompat();
        if (projection != null) {
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
        try { projection = mpm.getMediaProjection(code, data); } catch (RuntimeException e) { projection = null; }
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        SharedPreferences prefs = BotState.prefs(this);
        BotState.applyTo(prefs, params);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        brain = new MineBrain(params);
        if (prefs.getBoolean(BotState.K_LOGS, true)) {
            log = new SessionLog(this, (name, ok, err) -> main.post(() -> {
                String msg = ok ? "Log saved to Downloads/" + SessionLog.FOLDER + "/" + name : "Couldn't save log: " + err;
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
            }));
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                if (log != null) log.event(SystemClock.uptimeMillis(), "SCREEN SHARING ENDED by Android");
                TapService svc = TapService.instance;
                if (svc != null) svc.flash("Screen sharing ended — press START to share again");
                main.post(CaptureService.this::stopSelf);
            }
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
        int w = Math.min(CAPTURE_WIDTH, rw);
        int h = Math.round((float) w * rh / rw) & ~1;
        scaleX = rw / (float) w;
        scaleY = rh / (float) h;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        if (log != null) log.setInfo(info(w, h, rw, rh));
        thread = new HandlerThread("mines-vision", android.os.Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        bg = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImage, bg);
        display = projection.createVirtualDisplay("AiGameMinesweeper", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, bg);
    }

    private void onImage(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); } catch (RuntimeException e) { return; }
        if (img == null) return;
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rs = plane.getRowStride(), ps = plane.getPixelStride();
            int w = img.getWidth(), h = img.getHeight();
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
            fw = w; fh = h;
        } catch (RuntimeException e) {
            return;
        } finally {
            img.close();
        }
        lastFrameAt = SystemClock.uptimeMillis();
        final int seq = ++frameSeq;
        step(false);
        // if nothing new arrives, look at this same picture again once the screen is still
        bg.postDelayed(() -> { if (frameSeq == seq) quietCheck(seq); }, QUIET_MS);
    }

    private void quietCheck(int seq) {
        if (frameSeq != seq || px == null) return;
        if (brain == null || !brain.wantsRecheck()) return;
        step(true);
        // keep re-checking a still screen now and then (game over dialogs, waiting for a cascade)
        if (BotState.running) bg.postDelayed(() -> { if (frameSeq == seq) quietCheck(seq); }, 250);
    }

    /** Runs on the vision thread. */
    private void step(boolean quiet) {
        TapService svc = TapService.instance;
        long now = SystemClock.uptimeMillis();
        boolean run = BotState.running;
        if (run != wasRunning) {
            wasRunning = run;
            if (log != null) {
                log.event(now, run ? "START" : "STOP");
                if (!run) log.save("stop");
            }
            if (run) { brain.reset(); BotState.gamePackage = ""; panelCheckedFor = null; }
        }
        if (!run) return;
        if (BotState.resetRequested) { BotState.resetRequested = false; brain.reset(); BotState.gamePackage = ""; panelCheckedFor = null; }
        if (svc == null) return;

        String fg = BotState.foregroundPackage;
        if (!BotState.gameInFront(getPackageName()) || svc.isHome(fg)) {
            if (log != null && !fg.equals(lastFg)) { lastFg = fg; log.event(now, "not in game, in front: " + fg); }
            if (now - lastStatusAt > 500) { lastStatusAt = now; svc.postStatus("Waiting for the game (in front: " + fg + ")"); }
            return;
        }

        long t0 = SystemClock.uptimeMillis();
        MineBrain.Plan plan = brain.onFrame(px, fw, fh, now, quiet);
        long took = SystemClock.uptimeMillis() - t0;
        if (log != null) log.frame(px, fw, fh, now, took + "ms" + (quiet ? " q " : " ") + brain.logLine());

        MineVision.Geometry g = brain.geometry();
        if (g != null && g != panelCheckedFor) {
            panelCheckedFor = g;
            // remember which app the board is in, so taps never land anywhere else
            if (!fg.isEmpty() && !fg.equals(getPackageName()) && !svc.isHome(fg)) {
                BotState.gamePackage = fg;
                BotState.prefs(this).edit().putString(BotState.K_LAST_GAME, fg).apply();
            }
            svc.keepOffBoard(Math.round(g.left() * scaleX), Math.round((g.top() - g.pitchY * 2.2f) * scaleY),
                    Math.round(g.right() * scaleX), Math.round(g.bottom() * scaleY));
            if (log != null) log.event(now, "board " + g + " in " + fg);
        }

        handleEvent(svc, now);

        if (plan != null) {
            float[] xy = new float[plan.xy.length];
            for (int i = 0; i < xy.length; i += 2) { xy[i] = plan.xy[i] * scaleX; xy[i + 1] = plan.xy[i + 1] * scaleY; }
            svc.tapAll(xy, BotState.tapGap(this), (ok, note) -> bg.post(() -> {
                long t = SystemClock.uptimeMillis();
                brain.onTapsDone(t, ok, note);
                if (log != null && !ok) log.event(t, "taps failed: " + note);
                // the screen may not change (taps on already-open tiles): look again soon
                final int seq = frameSeq;
                bg.postDelayed(() -> { if (frameSeq == seq) quietCheck(seq); }, QUIET_MS);
            }));
        }
        if (now - lastStatusAt > 120 || plan != null) {
            lastStatusAt = now;
            svc.postStatus(brain.status);
        }
    }

    private MineBrain.Event lastEvent = MineBrain.Event.NONE;
    private int lastWins, lastLosses;

    private void handleEvent(TapService svc, long now) {
        if (brain.wins != lastWins || brain.losses != lastLosses) {
            SharedPreferences p = BotState.prefs(this);
            SharedPreferences.Editor e = p.edit();
            e.putInt(BotState.K_WINS, p.getInt(BotState.K_WINS, 0) + (brain.wins - lastWins));
            e.putInt(BotState.K_LOSSES, p.getInt(BotState.K_LOSSES, 0) + (brain.losses - lastLosses));
            long best = p.getLong(BotState.K_BEST, -1);
            if (brain.wins != lastWins && brain.lastWinMs >= 0 && (best < 0 || brain.lastWinMs < best)) e.putLong(BotState.K_BEST, brain.lastWinMs);
            e.apply();
            if (brain.losses != lastLosses && log != null) { log.event(now, "LOST: " + brain.eventText); log.save("lost"); }
            if (brain.wins != lastWins && log != null) { log.event(now, "WON in " + brain.lastWinMs + " ms"); log.save("won"); }
            lastWins = brain.wins;
            lastLosses = brain.losses;
            svc.showStats(svc.savedStats() + String.format(Locale.US, " · this run %d/%d", brain.wins, brain.wins + brain.losses));
        }
        MineBrain.Event ev = brain.event;
        if (ev != lastEvent) {
            lastEvent = ev;
            if (ev == MineBrain.Event.WON && !params.playAgainOnWin) {
                BotState.running = false;
                svc.flash(brain.eventText + " 🎉");
                svc.refreshToggle();
            } else if (ev == MineBrain.Event.STOP) {
                BotState.running = false;
                svc.flash(brain.eventText);
                svc.refreshToggle();
                if (log != null) { log.event(now, "STOPPED: " + brain.eventText); log.save("stuck"); }
            }
        }
        if (ev == MineBrain.Event.WON || ev == MineBrain.Event.LOST) { brain.event = MineBrain.Event.NONE; lastEvent = MineBrain.Event.NONE; }
    }

    private String info(int w, int h, int rw, int rh) {
        return "app: AI GAME MINESWEEPER " + BuildConfigCompat.version(this) + "\n"
                + "phone: " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE
                + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                + "screen: " + rw + "x" + rh + " capture: " + w + "x" + h + "\n"
                + "tap gap: " + BotState.tapGap(this) + " ms, first tap " + (params.firstTapCentre ? "centre" : "corner")
                + ", restart on loss " + params.restartOnLoss + ", guess " + params.allowGuess + "\n"
                + "log line: time analyse_ms [q=re-check of a still screen] board (# closed, F flag, . empty, 1-8, * mine, X hit, ? unreadable; rows split by /) m=counter => taps\n";
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "AI GAME MINESWEEPER", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("AI GAME MINESWEEPER is watching the screen")
                .setContentText("Use the floating button to start or stop.")
                .setSmallIcon(R.drawable.ic_stat_bot)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop capture", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIF_ID, n);
    }

    private void notifyToggle() {
        TapService svc = TapService.instance;
        if (svc != null) svc.refreshToggle();
    }

    @Override
    public void onDestroy() {
        BotState.running = false;
        BotState.captureActive = false;
        TapService svc = TapService.instance;
        if (svc != null) svc.refreshToggle();
        BotState.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener);
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) { try { projection.stop(); } catch (RuntimeException ignored) { } }
        if (thread != null) thread.quitSafely();
        if (log != null) log.close(null);
        log = null;
        display = null; reader = null; projection = null; thread = null;
        super.onDestroy();
    }
}
