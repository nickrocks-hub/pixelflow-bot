package com.nickrocks.pixelflowbot.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Experience storage, one JSON-lines file per game profile, kept on the device:
 * dir/&lt;profileId&gt;.jsonl, rotated to &lt;profileId&gt;.1.jsonl at {@link #MAX_BYTES}.
 * Episode ids continue across sessions. Writes are flushed per line, so a crash loses at most
 * the line being written.
 */
public final class ExperienceStore {
    public static final long MAX_BYTES = 16L << 20;

    public final File dir;

    public ExperienceStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    public File file(String profileId) { return new File(dir, profileId + ".jsonl"); }

    public synchronized void write(Experience e) throws IOException {
        if (e.time == 0) e.time = System.currentTimeMillis();
        File f = file(e.profileId);
        if (f.length() > MAX_BYTES) {
            File old = new File(dir, e.profileId + ".1.jsonl");
            old.delete();
            f.renameTo(old);
        }
        try (Writer w = new FileWriter(f, true)) {
            w.write(e.toJson());
            w.write('\n');
        }
    }

    /** Next free episode id for a profile (one past the highest stored). */
    public synchronized long nextEpisode(String profileId) {
        long max = 0;
        for (Experience e : recent(profileId, 4000)) max = Math.max(max, e.episode);
        return max + 1;
    }

    /** The last {@code n} experiences (oldest first). Unreadable lines are skipped. */
    public synchronized List<Experience> recent(String profileId, int n) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        File f = file(profileId);
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String s;
                while ((s = r.readLine()) != null) {
                    if (s.isEmpty()) continue;
                    lines.addLast(s);
                    if (lines.size() > n) lines.removeFirst();
                }
            } catch (IOException ignored) { }
        }
        List<Experience> out = new ArrayList<>();
        for (String s : lines) {
            try { out.add(Experience.fromJson(s)); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    public static final class Summary {
        public long steps, episodes, bytes;
        public float totalReward;
        public long lastTime;
    }

    public synchronized Summary summary(String profileId) {
        Summary s = new Summary();
        File f = file(profileId), old = new File(dir, profileId + ".1.jsonl");
        s.bytes = f.length() + old.length();
        for (Experience e : recent(profileId, Integer.MAX_VALUE)) {
            if (e.boundary == Experience.Boundary.START) s.episodes++;
            if (e.boundary == Experience.Boundary.STEP) s.steps++;
            s.totalReward += e.reward;
            s.lastTime = Math.max(s.lastTime, e.time);
        }
        return s;
    }

    /** Deletes everything stored for one profile. */
    public synchronized void clear(String profileId) {
        file(profileId).delete();
        new File(dir, profileId + ".1.jsonl").delete();
    }
}
