package com.nickrocks.pixelflowbot.core;

import com.nickrocks.pixelflowbot.learn.Json;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A game the platform knows about. Profiles are data, saved per version (see {@link ProfileStore}),
 * so every stored experience can say which version of the setup produced it.
 *
 * {@code support} is an honest status, never a promise:
 *  - SUPPORTED: an adapter exists and has been checked on real screens or a simulator.
 *  - CALIBRATING: the user added it; the shared core can watch and record, but nothing here
 *    understands this game yet. The UI says so.
 *  - UNSUPPORTED: known not to work (e.g. needs reactions faster than capture allows).
 */
public final class GameProfile {
    public enum Kind { BUILT_IN, EXTERNAL_APP }
    public enum Support { SUPPORTED, CALIBRATING, UNSUPPORTED }
    public enum Pace { TURN_BASED, REAL_TIME, UNKNOWN }

    public String id;              // stable slug, e.g. "nonogram-com"
    public String name;
    public Kind kind = Kind.EXTERNAL_APP;
    public String packageName;     // Android package for external games (null for built-in)
    public String adapterId;       // known adapter, or null when the shared core has to cope alone
    public Support support = Support.CALIBRATING;
    public Pace pace = Pace.UNKNOWN;
    public int version;            // bumped by ProfileStore on every saved change
    public long created, updated;
    public String notes = "";
    /** Free-form settings (calibration values, user-confirmed objective, …). */
    public final Map<String, String> settings = new LinkedHashMap<>();

    public GameProfile copy() { return fromJson(toJson()); }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(Json.q(id));
        sb.append(",\"name\":").append(Json.q(name));
        sb.append(",\"kind\":").append(Json.q(kind.name()));
        sb.append(",\"packageName\":").append(packageName == null ? "null" : Json.q(packageName));
        sb.append(",\"adapterId\":").append(adapterId == null ? "null" : Json.q(adapterId));
        sb.append(",\"support\":").append(Json.q(support.name()));
        sb.append(",\"pace\":").append(Json.q(pace.name()));
        sb.append(",\"version\":").append(version);
        sb.append(",\"created\":").append(created);
        sb.append(",\"updated\":").append(updated);
        sb.append(",\"notes\":").append(Json.q(notes));
        sb.append(",\"settings\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : settings.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.q(e.getKey())).append(':').append(Json.q(e.getValue()));
        }
        sb.append("}}");
        return sb.toString();
    }

    public static GameProfile fromJson(String s) {
        Map<String, Object> m = Json.obj(Json.parse(s));
        GameProfile p = new GameProfile();
        p.id = Json.str(m, "id", null);
        p.name = Json.str(m, "name", p.id);
        p.kind = Kind.valueOf(Json.str(m, "kind", Kind.EXTERNAL_APP.name()));
        p.packageName = m.get("packageName") instanceof String ? (String) m.get("packageName") : null;
        p.adapterId = m.get("adapterId") instanceof String ? (String) m.get("adapterId") : null;
        p.support = Support.valueOf(Json.str(m, "support", Support.CALIBRATING.name()));
        p.pace = Pace.valueOf(Json.str(m, "pace", Pace.UNKNOWN.name()));
        p.version = (int) Json.num(m, "version", 0);
        p.created = (long) Json.num(m, "created", 0);
        p.updated = (long) Json.num(m, "updated", 0);
        p.notes = Json.str(m, "notes", "");
        Object st = m.get("settings");
        if (st instanceof Map) {
            for (Map.Entry<String, Object> e : Json.obj(st).entrySet()) p.settings.put(e.getKey(), String.valueOf(e.getValue()));
        }
        if (p.id == null || p.id.isEmpty()) throw new IllegalArgumentException("profile has no id");
        return p;
    }

    /** A filesystem-safe id from a name ("Block Puzzle!" → "block-puzzle"). */
    public static String slug(String name) {
        String s = name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "game" : s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** One-line status for lists. */
    public String statusLine() {
        switch (support) {
            case SUPPORTED: return "Supported · adapter " + adapterId;
            case UNSUPPORTED: return "Not supported";
            default: return "yes".equals(settings.get("calibration_confirmed")) ? "AI vision agent · calibrated"
                    : settings.containsKey("calibration") ? "AI vision agent · calibration to confirm" : "AI vision agent · not calibrated yet";
        }
    }
}
