package com.nickrocks.pixelflowbot.core;

import com.nickrocks.pixelflowbot.learn.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A structured reading of the game at one moment, produced by a {@link Perception}.
 *
 * Every fact says how it was obtained, so guesses are never mixed up with what was seen:
 *  - OBSERVED: read directly from the screen (with a confidence),
 *  - INFERRED: derived from observations or by reasoning (e.g. "this line is complete"),
 *  - HIDDEN:   known to exist but not visible (a frozen clue, a covered card…).
 */
public final class StateObservation {
    public enum Screen { GAMEPLAY, MENU, LEVEL_COMPLETE, LOSS, TRANSITION, BLOCKED, UNKNOWN }
    public enum Source { OBSERVED, INFERRED, HIDDEN }

    public static final class Fact {
        public final String key, value;
        public final float confidence;
        public final Source source;

        public Fact(String key, String value, float confidence, Source source) {
            this.key = key;
            this.value = value;
            this.confidence = Math.max(0f, Math.min(1f, confidence));
            this.source = source;
        }
    }

    public final String profileId;
    public final int profileVersion;
    public final long time;
    public Screen screen = Screen.UNKNOWN;
    /** Overall trust in this reading (0..1). The session guard pauses below the profile's threshold. */
    public float confidence;
    /** Why the reading is incomplete or failed (null when fine). */
    public String problem;
    private final List<Fact> facts = new ArrayList<>();
    /** Adapter-specific payload for the planner (never serialized). */
    public transient Object detail;

    public StateObservation(String profileId, int profileVersion, long time) {
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.time = time;
    }

    public StateObservation add(String key, Object value, float confidence, Source source) {
        facts.add(new Fact(key, String.valueOf(value), confidence, source));
        return this;
    }

    public List<Fact> facts() { return Collections.unmodifiableList(facts); }

    public Fact fact(String key) {
        for (Fact f : facts) if (f.key.equals(key)) return f;
        return null;
    }

    public String value(String key, String def) {
        Fact f = fact(key);
        return f == null ? def : f.value;
    }

    public int intValue(String key, int def) {
        try { return Integer.parseInt(value(key, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    public int count(Source s) {
        int n = 0;
        for (Fact f : facts) if (f.source == s) n++;
        return n;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"profile\":").append(Json.q(profileId)).append(",\"profileVersion\":").append(profileVersion);
        sb.append(",\"t\":").append(time).append(",\"screen\":").append(Json.q(screen.name()));
        sb.append(",\"confidence\":").append(String.format(Locale.US, "%.3f", confidence));
        if (problem != null) sb.append(",\"problem\":").append(Json.q(problem));
        sb.append(",\"facts\":[");
        for (int i = 0; i < facts.size(); i++) {
            Fact f = facts.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"k\":").append(Json.q(f.key)).append(",\"v\":").append(Json.q(f.value))
                    .append(",\"c\":").append(String.format(Locale.US, "%.3f", f.confidence))
                    .append(",\"s\":\"").append(f.source.name().charAt(0)).append("\"}");
        }
        return sb.append("]}").toString();
    }

    /** Short readable form for logs and the UI. */
    public String summary() {
        StringBuilder sb = new StringBuilder(screen.name().toLowerCase(Locale.US));
        sb.append(String.format(Locale.US, " · conf %.2f", confidence));
        if (problem != null) sb.append(" · ").append(problem);
        sb.append(" · ").append(count(Source.OBSERVED)).append(" seen / ").append(count(Source.INFERRED))
                .append(" inferred / ").append(count(Source.HIDDEN)).append(" hidden");
        return sb.toString();
    }
}
