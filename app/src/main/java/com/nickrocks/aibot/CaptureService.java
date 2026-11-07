package com.nickrocks.aibot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
import android.widget.Toast;

import com.nickrocks.aibot.core.AISettings;
import com.nickrocks.aibot.core.Action;
import com.nickrocks.aibot.core.AutonomousAgent;
import com.nickrocks.aibot.core.Json;
import com.nickrocks.aibot.core.Observation;
import com.nickrocks.aibot.core.RunHistoryRepository;
import com.nickrocks.aibot.core.RunLogger;
import com.nickrocks.aibot.core.RunSessionManager;
import com.nickrocks.aibot.core.ScreenLayout;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;

/**
 * Foreground service: mirrors the screen at a small size (shorter side 432 px), and on its own
 * background thread runs each frame through the run session (screen reader → AI → controls).
 * Frames are processed at the chosen decision rate at most; extra frames are dropped, which keeps
 * battery use and heat down. Nothing here runs on the game's or the UI's thread.
 */
public class CaptureService extends Service {

    public static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    public static final String ACTION_START_RUN = "com.nickrocks.aibot.START_RUN";
    public static final String ACTION_STOP_CAPTURE = "com.nickrocks.aibot.STOP_CAPTURE";
    private static final String CHANNEL = "nickrocks_ai";
    private static final int NOTIF_ID = 7, NOTIF_DONE = 8;
    public static volatile CaptureService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler bg;
    private int[] px;
    private byte[] row;
    private float scaleX = 1, scaleY = 1;
    private long lastProcessed, lastStatus;
    private AISettings settings;
    private ScreenLayout layout;
    private RunHistoryRepository repo;
    private boolean notInFrontLogged;
    private long visionMicros;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        repo = AISettingsManager.repo(this);
        // if the app crashes mid-run, save what we have; the run is marked INTERRUPTED next time
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            RunSessionManager s = BotState.session;
            if (s != null) try { s.emergencyFlush(System.currentTimeMillis()); } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP_CAPTURE.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        startForegroundCompat();
        if (projection == null) {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(EXTRA_DATA, Intent.class) : intent.getParcelableExtra(EXTRA_DATA);
            if (data == null) { stopSelf(); return START_NOT_STICKY; }
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            try { projection = mpm.getMediaProjection(code, data); } catch (RuntimeException e) { projection = null; }
            if (projection == null) { stopSelf(); return START_NOT_STICKY; }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (bg != null) bg.post(() -> endRun(RunSessionManager.Outcome.ERROR, "Screen sharing was ended by Android or by you"));
                    main.post(CaptureService.this::stopSelf);
                }
            }, main);
            startCapture();
            BotState.captureActive = true;
        }
        if (ACTION_START_RUN.equals(intent.getAction())) bg.post(this::startRun);
        return START_NOT_STICKY;
    }

    private void startCapture() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int rw, rh;
        if (Build.VERSION.SDK_INT >= 30) { Rect b = wm.getMaximumWindowMetrics().getBounds(); rw = b.width(); rh = b.height(); }
        else { DisplayMetrics m = new DisplayMetrics(); wm.getDefaultDisplay().getRealMetrics(m); rw = m.widthPixels; rh = m.heightPixels; }
        // the screen reader expects the shorter side at ~432 px
        float k = 432f / Math.min(rw, rh);
        int w = Math.round(rw * k) & ~1, h = Math.round(rh * k) & ~1;
        scaleX = rw / (float) w; scaleY = rh / (float) h;
        int dpi = getResources().getDisplayMetrics().densityDpi;
        thread = new HandlerThread("ai-vision", android.os.Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        bg = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, bg);
        display = projection.createVirtualDisplay("NickRocksAI", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, bg);
    }

    // ============================================================================== run control

    private void startRun() {
        if (BotState.running()) return;
        settings = AISettingsManager.settings(this);
        layout = AISettingsManager.layout(this);
        BotState.paused = false;
        RunSessionManager s = new RunSessionManager(repo, settings, layout, new RunSessionManager.Listener() {
            @Override public void onState(RunSessionManager.State st, String note) {
                BotState.status = st + (note.isEmpty() ? "" : " — " + note);
                ControlService c = ControlService.instance;
                if (c != null) c.refreshPanel();
            }
            @Override public void onFinished(Map<String, Object> meta) { onRunFinished(meta); }
        });
        BotState.session = s;
        s.start(System.currentTimeMillis());
        notInFrontLogged = false;
        // bring the game up if we know which app it is
        String game = AISettingsManager.gamePackage(this);
        if (!game.isEmpty()) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(game);
            if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); main.post(() -> { try { startActivity(launch); } catch (RuntimeException ignored) { } }); }
        }
        ControlService c = ControlService.instance;
        if (c != null) c.refreshPanel();
        toast("AI run " + s.runId + " started — waiting for the game screen");
    }

    private void endRun(RunSessionManager.Outcome o, String reason) {
        RunSessionManager s = BotState.session;
        if (s != null && s.isLive()) s.finishNow(o, reason, System.currentTimeMillis());
    }

    private void onRunFinished(Map<String, Object> meta) {
        ControlService c = ControlService.instance;
        if (c != null) { c.releaseAll(); c.hideDebug(); c.refreshPanel(); }
        String id = Json.str(meta, "runId", "");
        BotState.lastFinishedRunId = id;
        BotState.paused = false;
        AISettings st = settings;
        if (st != null && st.autoExport) {
            String msg = Exporter.export(this, repo, id, st.autoExportFormat);
            toast(msg);
        }
        String text = Json.str(meta, "outcome", "") + " — " + PerformanceAnalyzerShort.line(meta);
        notifyDone(id, text);
        // show the summary
        main.post(() -> {
            Intent i = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_SHOW_RUN)
                    .putExtra(MainActivity.EXTRA_RUN, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (RuntimeException ignored) { }
        });
    }

    // ============================================================================== frames

    private void onImage(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); } catch (RuntimeException e) { return; }
        if (img == null) return;
        RunSessionManager s = BotState.session;
        long now = SystemClock.uptimeMillis();
        int hz = settings == null ? 15 : settings.decisionHz;
        if (s == null || !s.isLive() || now - lastProcessed < 1000 / hz - 4) { img.close(); return; }
        lastProcessed = now;
        int w, h;
        long v0 = System.nanoTime();
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rs = plane.getRowStride(), ps = plane.getPixelStride();
            w = img.getWidth(); h = img.getHeight();
            if (px == null || px.length != w * h) px = new int[w * h];
            if (row == null || row.length < rs) row = new byte[rs];
            for (int y = 0; y < h; y++) {
                buf.position(y * rs);
                buf.get(row, 0, Math.min(rs, buf.remaining()));
                int o = 0, i = y * w;
                for (int x = 0; x < w; x++, o += ps) px[i + x] = ((row[o] & 0xFF) << 16) | ((row[o + 1] & 0xFF) << 8) | (row[o + 2] & 0xFF);
            }
        } catch (RuntimeException e) {
            return;
        } finally {
            img.close();
        }
        ControlService c = ControlService.instance;
        if (c == null) {
            if (now - lastStatus > 3000) { lastStatus = now; s.log(RunLogger.BASIC, "ERROR", "The accessibility service is off: the bot can't touch the screen", null); }
            return;
        }
        // only play while the game is the app in front
        String fg = BotState.foregroundPackage;
        String game = AISettingsManager.gamePackage(this);
        boolean home = c.isHome(fg);
        if ((!game.isEmpty() && !fg.isEmpty() && !fg.equals(game)) || home) {
            c.releaseAll();
            if (!notInFrontLogged) { notInFrontLogged = true; s.log(RunLogger.BASIC, "STATE", "The game is not in front (" + fg + "); the AI waits and holds no controls", null); }
            status(c, s, "Waiting for the game to be in front");
            return;
        }
        if (notInFrontLogged) { notInFrontLogged = false; s.log(RunLogger.BASIC, "STATE", "Game back in front", null); }

        Action a = s.onFrame(px, w, h, now, System.currentTimeMillis());
        visionMicros = (System.nanoTime() - v0) / 1000;
        // the first time the game HUD is read, remember which app the game is
        if (game.isEmpty() && s.agent() != null && s.agent().lastObs != null && s.agent().lastObs.hudVisible
                && !fg.isEmpty() && !home && !fg.equals(getPackageName())) {
            AISettingsManager.setGamePackage(this, fg);
            s.log(RunLogger.BASIC, "AI", "Remembered the game app: " + fg, null);
        }
        if (s.isLive()) c.apply(a, layout, settings.decisionHz);
        else c.releaseAll();
        status(c, s, null);
        if (settings.debugOverlay && s.isLive()) debug(c, s);
    }

    private void status(ControlService c, RunSessionManager s, String override) {
        long now = SystemClock.uptimeMillis();
        if (now - lastStatus < 250) return;
        lastStatus = now;
        AutonomousAgent ag = s.agent();
        String l1 = String.format(Locale.US, "%s %s %s", s.runId, s.state(), PerformanceAnalyzerShort.clock(s.gameMs()));
        String l2 = override != null ? override : ag == null ? "" :
                String.format(Locale.US, "HP %s · %s · rooms %d · kills %d", hearts(ag.hp, ag.hpMax), ag.objective,
                        s.liveStats().roomsEntered, s.liveStats().enemiesDefeated);
        c.status(l1, l2);
    }

    private static String hearts(int hp, int max) { return hp < 0 ? "?" : (hp / 2f) + "/" + (max / 2f); }

    private void debug(ControlService c, RunSessionManager s) {
        AutonomousAgent ag = s.agent();
        if (ag == null || ag.lastObs == null) return;
        ControlService.DebugFrame f = new ControlService.DebugFrame();
        f.obs = ag.lastObs;
        f.sx = scaleX; f.sy = scaleY;
        f.path = new java.util.ArrayList<>(ag.nav.path);
        f.aimX = ag.aimDirX; f.aimY = ag.aimDirY;
        Observation o = ag.lastObs;
        f.lines = new String[]{
                "STATE " + s.state() + " / " + ag.objective,
                "TARGET " + ag.targetText,
                "ACTION " + ag.actionText,
                String.format(Locale.US, "THREAT %.2f  NEARBY %d  SHOTS %d", ag.threatLevel, ag.nearbyEnemies, o.shots.size()),
                "OBJECTIVE " + ag.objectiveText,
                "LAST " + ag.lastDecision,
                String.format(Locale.US, "TIME vision+AI %.1f ms (AI %.1f ms)", visionMicros / 1000f, ag.procMicros / 1000f)};
        c.showDebug(f);
    }

    // ============================================================================== misc

    private void toast(String s) { main.post(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Nick Rocks AI Bot", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction(ACTION_STOP_CAPTURE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Nick Rocks AI Bot is watching the screen")
                .setContentText("Start, pause or stop AI runs from the floating panel.")
                .setSmallIcon(R.drawable.ic_stat_bot)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Turn off screen capture", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTIF_ID, n);
    }

    private void notifyDone(String id, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent i = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_SHOW_RUN).putExtra(MainActivity.EXTRA_RUN, id);
        PendingIntent open = PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("AI run " + id + " finished")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_bot)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try { nm.notify(NOTIF_DONE, n); } catch (SecurityException ignored) { }
    }

    @Override
    public void onDestroy() {
        RunSessionManager s = BotState.session;
        if (s != null && s.isLive()) {
            if (bg != null) bg.post(() -> s.finishNow(RunSessionManager.Outcome.STOPPED, "Screen capture was turned off", System.currentTimeMillis()));
        }
        BotState.captureActive = false;
        ControlService c = ControlService.instance;
        if (c != null) { c.releaseAll(); c.hideDebug(); c.refreshPanel(); }
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) { try { projection.stop(); } catch (RuntimeException ignored) { } }
        if (thread != null) thread.quitSafely();
        display = null; reader = null; projection = null; thread = null;
        instance = null;
        super.onDestroy();
    }

    /** Tiny helpers for status text. */
    static final class PerformanceAnalyzerShort {
        static String clock(long ms) { return RunLogger.clock(ms).substring(0, 5); }
        static String line(Map<String, Object> m) {
            Map<String, Object> st = com.nickrocks.aibot.core.PerformanceAnalyzer.stats(m);
            return String.format(Locale.US, "%s, %d rooms, %d kills, score %.0f",
                    com.nickrocks.aibot.core.PerformanceAnalyzer.duration(Json.num(m, "durationMs", 0)),
                    (int) Json.num(st, "roomsEntered", 0), (int) Json.num(st, "enemiesDefeated", 0), Json.num(m, "fitness", 0));
        }
    }
}
