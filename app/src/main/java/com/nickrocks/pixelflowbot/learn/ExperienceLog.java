package com.nickrocks.pixelflowbot.learn;

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
 * Append-only experience file (one JSON transition per line). Rotates at ~8 MB, keeping one old
 * file. Used for diagnosing failures and to refill the replay buffer after a restart.
 */
public final class ExperienceLog {
    private final File file;
    private Writer out;
    private long bytes;
    private static final long MAX = 8L << 20;

    public ExperienceLog(File file) {
        this.file = file;
        this.bytes = file.exists() ? file.length() : 0;
    }

    public File file() { return file; }

    public synchronized void write(Transition t) {
        try {
            if (out == null) {
                File dir = file.getParentFile();
                if (dir != null) dir.mkdirs();
                out = new FileWriter(file, true);
            }
            String line = t.toJson() + "\n";
            out.write(line);
            bytes += line.length();
            if (t.done) out.flush();
            if (bytes > MAX) rotate();
        } catch (IOException ignored) {
        }
    }

    public synchronized void flush() {
        try { if (out != null) out.flush(); } catch (IOException ignored) { }
    }

    private void rotate() throws IOException {
        out.close();
        out = null;
        File old = new File(file.getPath() + ".1");
        old.delete();
        file.renameTo(old);
        bytes = 0;
    }

    /** The last n transitions (for refilling replay after a restart). */
    public synchronized List<Transition> recent(int n) {
        flush();
        ArrayDeque<String> lines = new ArrayDeque<>();
        if (file.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String l;
                while ((l = r.readLine()) != null) {
                    lines.addLast(l);
                    if (lines.size() > n) lines.removeFirst();
                }
            } catch (IOException ignored) { }
        }
        List<Transition> out = new ArrayList<>();
        for (String l : lines) {
            try { out.add(Transition.fromJson(l)); } catch (Exception ignored) { }
        }
        return out;
    }

    /** The last n raw lines, for the log viewer. */
    public synchronized List<String> tail(int n) {
        flush();
        ArrayDeque<String> lines = new ArrayDeque<>();
        if (file.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String l;
                while ((l = r.readLine()) != null) {
                    lines.addLast(l);
                    if (lines.size() > n) lines.removeFirst();
                }
            } catch (IOException ignored) { }
        }
        return new ArrayList<>(lines);
    }

    public synchronized void delete() {
        try { if (out != null) out.close(); } catch (IOException ignored) { }
        out = null;
        file.delete();
        new File(file.getPath() + ".1").delete();
        bytes = 0;
    }
}
