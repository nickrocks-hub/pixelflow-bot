package com.nickrocks.pixelflowbot.learn;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saves, loads and versions trained models for one adapter:
 *   <dir>/model_v1.json, model_v2.json, …   (every Save makes a new version)
 *   <dir>/model_latest.json                 (autosave during training; loaded on start)
 * A model file holds the weights, feature names, hyper-parameters and the stats per mode, so a
 * reloaded model reports exactly what it had achieved.
 */
public final class ModelStore {
    public final File dir;
    private final String adapterId;

    public ModelStore(File dir, String adapterId) {
        this.dir = dir;
        this.adapterId = adapterId;
        dir.mkdirs();
    }

    public static final class Info {
        public int version;
        public long created;
        public String note;
        public long trainEpisodes;
        public float evalWinRate;
        public File file;

        @Override public String toString() {
            return String.format(Locale.US, "v%d · %s · %d training episodes · eval win %.0f%%%s",
                    version, new java.text.SimpleDateFormat("MMM d HH:mm", Locale.US).format(new java.util.Date(created)),
                    trainEpisodes, 100 * evalWinRate, note == null || note.isEmpty() ? "" : " · " + note);
        }
    }

    public String serialize(Agent a, int version, String note) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"adapter\":").append(Json.q(adapterId));
        sb.append(",\"version\":").append(version);
        sb.append(",\"created\":").append(System.currentTimeMillis());
        sb.append(",\"note\":").append(Json.q(note));
        sb.append(",\"model\":").append(a.model.toJson());
        sb.append(",\"stats\":{");
        boolean first = true;
        for (Agent.Mode m : Agent.Mode.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.q(m.name())).append(':').append(a.stats.get(m).toJson());
        }
        sb.append("}}");
        return sb.toString();
    }

    /** Saves a new numbered version. */
    public Info saveVersion(Agent a, String note) throws IOException {
        int v = 1;
        for (Info i : list()) v = Math.max(v, i.version + 1);
        File f = new File(dir, "model_v" + v + ".json");
        write(f, serialize(a, v, note));
        write(new File(dir, "model_latest.json"), serialize(a, v, note));
        return info(f);
    }

    /** Overwrites the autosave (model_latest.json). */
    public void autosave(Agent a) throws IOException {
        write(new File(dir, "model_latest.json"), serialize(a, 0, "autosave"));
    }

    public boolean loadLatest(Agent a) throws IOException {
        File f = new File(dir, "model_latest.json");
        if (!f.exists()) return false;
        load(a, f);
        return true;
    }

    public void load(Agent a, File f) throws IOException {
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Map<String, Object> m = Json.obj(Json.parse(s));
        if (!adapterId.equals(Json.str(m, "adapter", ""))) throw new IOException("model is for " + Json.str(m, "adapter", "?"));
        LinearQ q = LinearQ.fromJson(Json.obj(m.get("model")));
        if (q.featureNames.length != a.model.featureNames.length)
            throw new IOException("model has " + q.featureNames.length + " features, this version expects " + a.model.featureNames.length);
        a.model = q;
        Map<String, Object> st = Json.obj(m.get("stats"));
        for (Agent.Mode mode : Agent.Mode.values()) {
            a.stats.put(mode, Stats.fromJson(st == null ? null : Json.obj(st.get(mode.name()))));
        }
    }

    public List<Info> list() {
        List<Info> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.matches("model_v\\d+\\.json"));
        if (fs == null) return out;
        for (File f : fs) {
            try { out.add(info(f)); } catch (Exception ignored) { }
        }
        out.sort((x, y) -> Integer.compare(y.version, x.version));
        return out;
    }

    private Info info(File f) throws IOException {
        Map<String, Object> m = Json.obj(Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        Info i = new Info();
        i.file = f;
        i.version = (int) Json.num(m, "version", 0);
        i.created = (long) Json.num(m, "created", f.lastModified());
        i.note = Json.str(m, "note", "");
        Map<String, Object> st = Json.obj(m.get("stats"));
        Stats tr = Stats.fromJson(st == null ? null : Json.obj(st.get("TRAINING")));
        Stats ev = Stats.fromJson(st == null ? null : Json.obj(st.get("EVALUATION")));
        i.trainEpisodes = tr.episodes;
        i.evalWinRate = ev.winRate();
        return i;
    }

    /** Deletes every saved model, the autosave and the stable mark. */
    public void deleteAll() {
        File[] fs = dir.listFiles((d, n) -> n.startsWith("model_"));
        if (fs != null) for (File f : fs) f.delete();
        new File(dir, "stable.txt").delete();
    }

    // ---------------------------------------------------------------- stable version / rollback

    /** Marks a saved version as the known-good one that {@link #rollbackToStable} returns to. */
    public void markStable(int version) throws IOException {
        if (!new File(dir, "model_v" + version + ".json").exists()) throw new IOException("no saved version v" + version);
        write(new File(dir, "stable.txt"), String.valueOf(version));
    }

    /** The version marked stable, or −1. */
    public int stableVersion() {
        File f = new File(dir, "stable.txt");
        try {
            int v = Integer.parseInt(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
            return new File(dir, "model_v" + v + ".json").exists() ? v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Replaces the agent's current model with the stable version (and makes that the autosave),
     * undoing training that made things worse. Returns the version restored.
     */
    public int rollbackToStable(Agent a) throws IOException {
        int v = stableVersion();
        if (v < 0) throw new IOException("no stable version marked");
        load(a, new File(dir, "model_v" + v + ".json"));
        a.replay.clear();
        autosave(a);
        return v;
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileWriter w = new FileWriter(tmp)) { w.write(s); }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("couldn't write " + f);
        }
    }
}
