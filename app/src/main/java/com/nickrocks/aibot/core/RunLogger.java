package com.nickrocks.aibot.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes one run's log as it happens: events.jsonl (one JSON object per line, appended about once a
 * second) and meta.json (status, counters, settings; rewritten every few seconds). If the app is
 * killed, everything up to the last flush is on disk and the run is later marked INTERRUPTED.
 */
public final class RunLogger {

    public static final int BASIC = 0, DETAILED = 1, DEBUG = 2;

    private final File dir, events, meta;
    private final int level;
    private final List<String> pending = new ArrayList<>();
    private int count;
    private long lastFlushWall;
    public final String runId;

    public RunLogger(File runDir, String runId, AISettings.LogLevel level) {
        this.dir = runDir;
        this.runId = runId;
        runDir.mkdirs();
        this.events = new File(runDir, "events.jsonl");
        this.meta = new File(runDir, "meta.json");
        this.level = level == AISettings.LogLevel.BASIC ? BASIC : level == AISettings.LogLevel.DETAILED ? DETAILED : DEBUG;
    }

    public boolean wants(int lvl) { return lvl <= level; }

    /** Adds an event if the logging level includes it. {@code t} is in-game time in ms. */
    public synchronized void event(int lvl, long t, String type, String msg, Map<String, Object> data) {
        if (lvl > level) return;
        Map<String, Object> m = Json.map("n", ++count, "t", t, "clock", clock(t), "type", type, "msg", msg);
        if (data != null && !data.isEmpty()) m.put("data", data);
        pending.add(Json.write(m));
    }

    public synchronized int eventCount() { return count; }

    public static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
    }

    /** Appends pending events. Cheap to call often; writes at most once per {@code minGapMs}. */
    public synchronized void flush(long wallNow, long minGapMs) {
        if (pending.isEmpty() || wallNow - lastFlushWall < minGapMs) return;
        lastFlushWall = wallNow;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(events, true), StandardCharsets.UTF_8)) {
            for (String s : pending) { w.write(s); w.write('\n'); }
            pending.clear();
        } catch (IOException e) {
            // keep them for the next try; the meta file still records the run
        }
    }

    public synchronized void writeMeta(Map<String, Object> m) {
        File tmp = new File(dir, "meta.json.tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(Json.write(m));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(meta)) { meta.delete(); tmp.renameTo(meta); }
    }

    public File dir() { return dir; }
}
