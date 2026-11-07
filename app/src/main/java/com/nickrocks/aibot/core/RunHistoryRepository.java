package com.nickrocks.aibot.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs stored on the device: {root}/runs/{RUN ID}/meta.json + events.jsonl, plus the bot's learned
 * experience in {root}/experience.json. Plain files, so they survive app restarts.
 */
public final class RunHistoryRepository {

    private final File root, runs;

    public RunHistoryRepository(File root) {
        this.root = root;
        this.runs = new File(root, "runs");
        runs.mkdirs();
    }

    public synchronized String nextRunId() {
        File f = new File(root, "run_counter.txt");
        int n = 0;
        String s = read(f);
        if (s != null) try { n = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        // never reuse a folder that exists
        String id;
        do { n++; id = String.format(Locale.US, "NR-AI-%04d", n); } while (new File(runs, id).exists());
        write(f, Integer.toString(n));
        return id;
    }

    public File runDir(String id) { return new File(runs, id); }

    /** All runs, newest first. */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        File[] ds = runs.listFiles(File::isDirectory);
        if (ds == null) return out;
        for (File d : ds) {
            Map<String, Object> m = meta(d.getName());
            if (m != null) out.add(m);
        }
        out.sort((a, b) -> Double.compare(Json.num(b, "startWall", 0), Json.num(a, "startWall", 0)));
        return out;
    }

    public Map<String, Object> meta(String id) {
        String s = read(new File(runDir(id), "meta.json"));
        if (s == null) return null;
        Map<String, Object> m = Json.obj(s);
        if (!m.containsKey("runId")) m.put("runId", id);
        return m;
    }

    public List<Map<String, Object>> events(String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        File f = new File(runDir(id), "events.jsonl");
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                Map<String, Object> m = Json.obj(line);
                if (!m.isEmpty()) out.add(m);       // a half-written last line after a crash is skipped
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** Events containing {@code query} (case-insensitive) in type or text. */
    public List<Map<String, Object>> search(String id, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : events(id)) {
            String t = (Json.str(e, "type", "") + " " + Json.str(e, "msg", "") + " " + Json.write(e.get("data"))).toLowerCase(Locale.ROOT);
            if (t.contains(q)) out.add(e);
        }
        return out;
    }

    public boolean delete(String id) {
        File d = runDir(id);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        return d.delete();
    }

    /**
     * Marks runs that were still RUNNING/PAUSED/INITIALIZING/FINALIZING but are not the live run as
     * INTERRUPTED (the app was closed or crashed). Returns how many were fixed.
     */
    public int recoverInterrupted(String liveRunId) {
        int n = 0;
        for (Map<String, Object> m : list()) {
            String st = Json.str(m, "status", "");
            String id = Json.str(m, "runId", "");
            if (id.equals(liveRunId)) continue;
            if (st.equals("RUNNING") || st.equals("PAUSED") || st.equals("INITIALIZING") || st.equals("FINALIZING")) {
                m.put("status", "INTERRUPTED");
                m.put("outcome", "Interrupted");
                m.put("endReason", "The app stopped before the run finished (closed, crashed or killed by Android). The log up to the last save is kept.");
                long last = lastEventTime(id);
                if (last > Json.num(m, "durationMs", 0)) m.put("durationMs", last);
                String line = Json.write(Json.map("n", 0, "t", last, "clock", RunLogger.clock(last), "type", "STATE",
                        "msg", "RUN INTERRUPTED — log recovered after the app stopped"));
                append(new File(runDir(id), "events.jsonl"), line + "\n");
                write(new File(runDir(id), "meta.json"), Json.write(m));
                n++;
            }
        }
        return n;
    }

    private long lastEventTime(String id) {
        List<Map<String, Object>> ev = events(id);
        return ev.isEmpty() ? 0 : (long) Json.num(ev.get(ev.size() - 1), "t", 0);
    }

    /** Deletes the oldest finished runs until all logs fit in {@code maxBytes}. */
    public int enforceLimit(long maxBytes, String keepId) {
        List<Map<String, Object>> all = list();
        long total = 0;
        for (Map<String, Object> m : all) total += size(Json.str(m, "runId", ""));
        int removed = 0;
        for (int i = all.size() - 1; i >= 0 && total > maxBytes; i--) {
            String id = Json.str(all.get(i), "runId", "");
            if (id.equals(keepId)) continue;
            total -= size(id);
            if (delete(id)) removed++;
        }
        return removed;
    }

    public long size(String id) {
        long s = 0;
        File[] fs = runDir(id).listFiles();
        if (fs != null) for (File f : fs) s += f.length();
        return s;
    }

    // --------------------------------------------------------------------------- experience

    public Learner loadLearner() {
        String s = read(new File(root, "experience.json"));
        return s == null ? new Learner() : Learner.fromMap(Json.obj(s));
    }

    public void saveLearner(Learner l) {
        File tmp = new File(root, "experience.json.tmp");
        write(tmp, Json.write(l.toMap()));
        File f = new File(root, "experience.json");
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }

    public void resetLearner() { new File(root, "experience.json").delete(); }

    // ------------------------------------------------------------------------------- export

    public String exportJson(String id) {
        Map<String, Object> m = Json.map("meta", meta(id), "events", new ArrayList<Object>(events(id)));
        return Json.write(m);
    }

    public String exportCsv(String id) {
        StringBuilder b = new StringBuilder("n,time_ms,clock,type,message,data\n");
        for (Map<String, Object> e : events(id)) {
            b.append((long) Json.num(e, "n", 0)).append(',').append((long) Json.num(e, "t", 0)).append(',')
                    .append(Json.str(e, "clock", "")).append(',').append(csv(Json.str(e, "type", ""))).append(',')
                    .append(csv(Json.str(e, "msg", ""))).append(',').append(csv(e.get("data") == null ? "" : Json.write(e.get("data")))).append('\n');
        }
        return b.toString();
    }

    public String exportTxt(String id) {
        Map<String, Object> m = meta(id);
        StringBuilder b = new StringBuilder();
        b.append(PerformanceAnalyzer.summaryText(m)).append("\n\nFULL LOG\n\n");
        for (Map<String, Object> e : events(id)) {
            b.append(Json.str(e, "clock", "")).append(" — ").append(Json.str(e, "type", "")).append(": ").append(Json.str(e, "msg", "")).append('\n');
            Object d = e.get("data");
            if (d instanceof Map && "DECISION".equals(Json.str(e, "type", ""))) {
                @SuppressWarnings("unchecked") Map<String, Object> dm = (Map<String, Object>) d;
                for (String k : new String[]{"situation", "decision", "reason", "result", "assessment"})
                    if (dm.containsKey(k)) b.append("        ").append(cap(k)).append(": ").append(dm.get(k)).append('\n');
            }
        }
        b.append('\n').append(Json.str(m, "status", "")).append('\n');
        return b.toString();
    }

    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    private static String csv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    public static String wallTime(double epochMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date((long) epochMs));
    }

    // ------------------------------------------------------------------------------- files

    static String read(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) { int r = in.read(b, off, b.length - off); if (r < 0) break; off += r; }
            return new String(b, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static void write(File f, String s) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(s); } catch (IOException ignored) { }
    }

    static void append(File f, String s) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) { w.write(s); } catch (IOException ignored) { }
    }
}
