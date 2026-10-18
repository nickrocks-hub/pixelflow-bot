package com.nickrocks.pixelflowbot;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import com.nickrocks.pixelflowbot.learn.Agent;
import com.nickrocks.pixelflowbot.learn.ExperienceLog;
import com.nickrocks.pixelflowbot.learn.ModelStore;
import com.nickrocks.pixelflowbot.learn.SimRunner;
import com.nickrocks.pixelflowbot.learn.Transition;
import com.nickrocks.pixelflowbot.learn.nono.NonoAdapter;
import com.nickrocks.pixelflowbot.learn.nono.NonoSim;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Process-wide home of the learning agent, shared by the AI Lab screen (simulator training) and
 * the floating panel (real game). One agent per adapter; only Nonogram exists so far.
 *
 * Files (private to the app): files/ai/nonogram/model_vN.json, model_latest.json, experiences.jsonl
 */
final class AiHub {
    private static final String TAG = "PFBot.AI";
    private static AiHub instance;

    final Context ctx;
    final NonoAdapter adapter = new NonoAdapter();
    final NonoSim sim = new NonoSim();
    final Agent agent;
    final ModelStore store;
    final ExperienceLog experiences;
    final Object lock = new Object();

    // Simulator training state (read by the UI).
    volatile boolean simRunning, simPaused, simStop;
    volatile String simLine = "";
    volatile float stepsPerSecond;
    final List<float[]> curve = new ArrayList<>();   // {episode, recent win rate} for the chart
    private Thread simThread;
    private long nextEpisodeId = 1;
    String loadNote = "";

    static synchronized AiHub get(Context c) {
        if (instance == null) instance = new AiHub(c.getApplicationContext());
        return instance;
    }

    private AiHub(Context ctx) {
        this.ctx = ctx;
        File dir = new File(ctx.getFilesDir(), "ai/" + adapter.id());
        store = new ModelStore(dir, adapter.id());
        experiences = new ExperienceLog(new File(dir, "experiences.jsonl"));
        agent = new Agent(adapter.id(), adapter.featureNames(), System.nanoTime());
        try {
            if (store.loadLatest(agent)) {
                loadNote = "Loaded the last saved model.";
                // Refill replay memory from recent real experience so training continues smoothly.
                for (Transition t : experiences.recent(4000)) {
                    if (t.features != null && t.features.length == adapter.featureNames().length) agent.replay.add(t);
                }
            } else loadNote = "New, untrained model.";
        } catch (Exception e) {
            loadNote = "Couldn't load the saved model (" + e.getMessage() + "); starting fresh.";
            Log.w(TAG, "load failed", e);
        }
        nextEpisodeId = agent.stats.get(Agent.Mode.TRAINING).episodes + agent.stats.get(Agent.Mode.EVALUATION).episodes + 1;
    }

    long newEpisodeId() { return nextEpisodeId++; }

    // ------------------------------------------------------------------ simulator

    /** Runs simulated puzzles in the background in the agent's current mode until stopped. */
    void startSim(int[] sizes) {
        if (simRunning) { simPaused = false; return; }
        simRunning = true;
        simPaused = false;
        simStop = false;
        sim.sizes = sizes;
        simThread = new Thread(() -> {
            long t0 = System.currentTimeMillis(), steps0;
            synchronized (lock) { steps0 = agent.stats().steps; }
            int sinceSave = 0;
            // Simulator transitions aren't written to the experience file (thousands per second);
            // they still go through the same agent.record() learning path.
            while (!simStop) {
                if (simPaused) { sleep(150); continue; }
                SimRunner.Result r;
                synchronized (lock) {
                    ExperienceLog keep = agent.log;
                    agent.log = null;
                    r = SimRunner.episode(agent, adapter, sim, newEpisodeId(), 10_000, () -> simStop);
                    agent.log = keep;
                    float secs = (System.currentTimeMillis() - t0) / 1000f;
                    stepsPerSecond = secs > 0 ? (agent.stats().steps - steps0) / secs : 0;
                    simLine = agent.mode + " · " + agent.stats().line();
                    curve.add(new float[]{agent.stats().episodes, agent.stats().recentWinRate()});
                    if (curve.size() > 600) curve.remove(0);
                }
                if (agent.learning() && ++sinceSave >= 200) { sinceSave = 0; autosave(); }
            }
            if (agent.learning()) autosave();
            simRunning = false;
        }, "ai-sim");
        simThread.setPriority(Thread.MIN_PRIORITY);
        simThread.start();
    }

    void stopSim() {
        simStop = true;
        simPaused = false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    // ------------------------------------------------------------------ models

    void autosave() {
        synchronized (lock) {
            try { store.autosave(agent); } catch (IOException e) { Log.w(TAG, "autosave failed", e); }
        }
    }

    ModelStore.Info saveVersion(String note) throws IOException {
        synchronized (lock) { return store.saveVersion(agent, note); }
    }

    void load(File f) throws IOException {
        synchronized (lock) {
            store.load(agent, f);
            agent.replay.clear();
            store.autosave(agent);
        }
    }

    /** Wipes the model, statistics, saved versions and experiences. */
    void resetAll() {
        stopSim();
        synchronized (lock) {
            agent.resetLearning();
            store.deleteAll();
            experiences.delete();
            curve.clear();
        }
    }

    /** Imports a model file chosen by the user (saved as a new version, then loaded). */
    ModelStore.Info importModel(InputStream in) throws IOException {
        byte[] data = readAll(in);
        File tmp = new File(store.dir, "import.tmp");
        try (OutputStream os = new java.io.FileOutputStream(tmp)) { os.write(data); }
        synchronized (lock) {
            store.load(agent, tmp);   // validates adapter + feature count
            tmp.delete();
            store.autosave(agent);
            return store.saveVersion(agent, "imported");
        }
    }

    /** Copies a file to Download/PixelFlowBot so it can be shared or inspected. */
    String exportToDownloads(File src, String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/PixelFlowBot");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("couldn't create " + name);
            try (InputStream in = new FileInputStream(src); OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return "Download/PixelFlowBot/" + name;
        }
        File out = new File(ctx.getExternalFilesDir("export"), name);
        try (InputStream in = new FileInputStream(src); OutputStream os = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out.getPath();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
