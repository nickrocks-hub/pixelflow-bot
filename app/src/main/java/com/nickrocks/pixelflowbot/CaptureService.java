package com.nickrocks.pixelflowbot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Foreground service that holds the MediaProjection and keeps the latest screen frame.
 * Later versions will hand frames to the board reader; for now it counts frames and
 * can save one to Pictures/PixelFlowBot so you can confirm capture works.
 */
public class CaptureService extends Service {
    private static final String TAG = "PFBot.Capture";
    private static final String CHANNEL_ID = "capture";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";

    private static volatile CaptureService instance;
    private static volatile long frameCount = 0;
    private static volatile boolean saveNextFrame = false;
    private static volatile java.util.function.Consumer<com.nickrocks.pixelflowbot.vision.Frame> frameRequest;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler mainHandler;
    private int width, height, density;

    public static boolean isRunning() { return instance != null; }
    public static long getFrameCount() { return frameCount; }
    public static void requestSaveFrame() { saveNextFrame = true; }

    /** Saves an already-captured frame (e.g. one the bot misread) to Pictures/PixelFlowBot, off the main thread. */
    public static void saveFrame(com.nickrocks.pixelflowbot.vision.Frame f) {
        CaptureService s = instance;
        if (s == null || f == null) return;
        new Thread(() -> {
            try {
                Bitmap bmp = Bitmap.createBitmap(f.px, f.w, f.h, Bitmap.Config.ARGB_8888);
                s.saveBitmap(bmp);
            } catch (Throwable t) {
                Log.e(TAG, "saveFrame failed", t);
            }
        }, "save-frame").start();
    }

    /** Delivers the next captured frame (on the capture thread). Frames only arrive when the screen changes. */
    public static void requestFrame(java.util.function.Consumer<com.nickrocks.pixelflowbot.vision.Frame> cb) {
        frameRequest = cb;
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, CaptureService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Android 14 requires the foreground service to be running before getMediaProjection().
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_DATA);
        }
        if (data == null) {
            toast("Screen capture permission missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            startCapture(resultCode, data);
            instance = this;
            toast("Screen capture started");
        } catch (Exception e) {
            Log.e(TAG, "Capture failed to start", e);
            toast("Capture failed: " + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startCapture(int resultCode, Intent data) {
        readScreenSize();
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) throw new IllegalStateException("MediaProjection was null");

        captureThread = new HandlerThread("capture");
        captureThread.start();
        Handler bg = new Handler(captureThread.getLooper());

        // Must be registered before createVirtualDisplay on Android 14+.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.i(TAG, "Projection stopped by system/user");
                mainHandler.post(CaptureService.this::stopSelf);
            }
        }, mainHandler);

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                frameCount++;
                java.util.function.Consumer<com.nickrocks.pixelflowbot.vision.Frame> req = frameRequest;
                if (req != null) {
                    frameRequest = null;
                    Bitmap bmp = imageToBitmap(img);
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    int[] px = new int[w * h];
                    bmp.getPixels(px, 0, w, 0, 0, w, h);
                    bmp.recycle();
                    req.accept(new com.nickrocks.pixelflowbot.vision.Frame(w, h, px));
                }
                if (saveNextFrame) {
                    saveNextFrame = false;
                    saveBitmap(imageToBitmap(img));
                }
            } catch (Exception e) {
                Log.e(TAG, "Frame error", e);
            } finally {
                if (img != null) img.close();
            }
        }, bg);

        virtualDisplay = projection.createVirtualDisplay("PixelFlowBot",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, bg);
    }

    private void readScreenSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.densityDpi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            width = b.width();
            height = b.height();
        } else {
            DisplayMetrics real = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(real);
            width = real.widthPixels;
            height = real.heightPixels;
        }
    }

    /** Converts an RGBA_8888 Image to a Bitmap, trimming row padding. */
    private Bitmap imageToBitmap(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * img.getWidth();
        Bitmap padded = Bitmap.createBitmap(img.getWidth() + rowPadding / pixelStride,
                img.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buf);
        if (rowPadding == 0) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, img.getWidth(), img.getHeight());
        padded.recycle();
        return cropped;
    }

    private void saveBitmap(Bitmap bmp) {
        String name = "pixelflow_" + System.currentTimeMillis() + ".png";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PixelFlowBot");
                ContentResolver cr = getContentResolver();
                Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("MediaStore insert failed");
                try (OutputStream os = cr.openOutputStream(uri)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                toast("Saved to Pictures/PixelFlowBot");
            } else {
                File dir = getExternalFilesDir("frames");
                File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                toast("Saved to " + f.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
            toast("Save failed: " + e.getMessage());
        } finally {
            bmp.recycle();
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Screen capture",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stopPi = PendingIntent.getService(this, 0,
                new Intent(this, CaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PixelFlow Bot is watching the screen")
                .setContentText("Tap Stop to end capture")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }

    private void toast(String msg) {
        mainHandler.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (projection != null) { projection.stop(); projection = null; }
        if (captureThread != null) { captureThread.quitSafely(); captureThread = null; }
        frameCount = 0;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
