package com.nickrocks.pixelflowbot.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent, versioned game profiles.
 *
 * Layout: dir/&lt;id&gt;/current.json and dir/&lt;id&gt;/v&lt;N&gt;.json for every saved version, so an
 * older setup can be inspected or restored. Built-in profiles are created on first use and keep
 * their version history like any other.
 */
public final class ProfileStore {
    public final File dir;

    public ProfileStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    /** Saves a changed profile as a new version and returns it (with the new version number). */
    public synchronized GameProfile save(GameProfile p) throws IOException {
        if (p.id == null || p.id.isEmpty()) throw new IOException("profile needs an id");
        File d = new File(dir, p.id);
        d.mkdirs();
        GameProfile cur = get(p.id);
        GameProfile out = p.copy();
        long now = System.currentTimeMillis();
        out.version = (cur == null ? 0 : cur.version) + 1;
        out.created = cur == null ? now : cur.created;
        out.updated = now;
        write(new File(d, "v" + out.version + ".json"), out.toJson());
        write(new File(d, "current.json"), out.toJson());
        return out;
    }

    /** The library profile for an app package (one with a player wins), or null. */
    public synchronized GameProfile forPackage(String pkg) {
        if (pkg == null) return null;
        GameProfile found = null;
        for (GameProfile p : list()) {
            if (!pkg.equals(p.packageName)) continue;
            if (found == null || (found.adapterId == null && p.adapterId != null)) found = p;
        }
        return found;
    }

    /**
     * Makes sure each shipped profile exists. If you already added that app yourself (before it had
     * a player), your profile gets the player instead — your name and objective are kept.
     */
    public synchronized void adoptBuiltIns(List<GameProfile> builtIns) throws IOException {
        for (GameProfile b : builtIns) {
            GameProfile mine = b.packageName == null ? get(b.id) : forPackage(b.packageName);
            if (mine == null) { save(b); continue; }
            if (b.adapterId != null && !b.adapterId.equals(mine.adapterId)) {
                mine.adapterId = b.adapterId;
                mine.support = GameProfile.Support.SUPPORTED;
                if (mine.pace == GameProfile.Pace.UNKNOWN) mine.pace = b.pace;
                mine.notes = b.notes;
                save(mine);
            }
        }
    }

    /** Creates the profile only if it doesn't exist yet. */
    public synchronized GameProfile ensure(GameProfile p) throws IOException {
        GameProfile cur = get(p.id);
        return cur != null ? cur : save(p);
    }

    public synchronized GameProfile get(String id) {
        File f = new File(new File(dir, id), "current.json");
        if (!f.exists()) return null;
        try {
            return GameProfile.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** All profiles, built-in first, then by name. */
    public synchronized List<GameProfile> list() {
        List<GameProfile> out = new ArrayList<>();
        File[] ds = dir.listFiles(File::isDirectory);
        if (ds != null) for (File d : ds) {
            GameProfile p = get(d.getName());
            if (p != null) out.add(p);
        }
        out.sort((a, b) -> a.kind != b.kind ? a.kind.compareTo(b.kind) : a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Saved versions of a profile, newest first. */
    public synchronized List<Integer> versions(String id) {
        List<Integer> out = new ArrayList<>();
        File[] fs = new File(dir, id).listFiles((d, n) -> n.matches("v\\d+\\.json"));
        if (fs != null) for (File f : fs) out.add(Integer.parseInt(f.getName().replaceAll("\\D", "")));
        out.sort(Collections.reverseOrder());
        return out;
    }

    public synchronized GameProfile version(String id, int v) {
        File f = new File(new File(dir, id), "v" + v + ".json");
        try {
            return GameProfile.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** True for profiles that ship with the app (they can be edited but not deleted). */
    public static boolean shipped(String id) {
        for (GameProfile b : Adapters.builtInProfiles()) if (b.id.equals(id)) return true;
        return false;
    }

    /** Deletes a user-added profile and its history (profiles shipped with the app are refused). */
    public synchronized boolean delete(String id) {
        GameProfile p = get(id);
        if (p == null || shipped(id)) return false;
        File d = new File(dir, id);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        return d.delete();
    }

    /** An id not used yet, based on the name. */
    public synchronized String freeId(String name) {
        String base = GameProfile.slug(name), id = base;
        for (int k = 2; new File(dir, id).exists(); k++) id = base + "-" + k;
        return id;
    }

    private static void write(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), s.getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(f)) {
            Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
            tmp.delete();
        }
    }
}
