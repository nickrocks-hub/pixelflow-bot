package com.nickrocks.pixelflowbot.core;

import com.nickrocks.pixelflowbot.learn.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A physical input to a game in screen pixels: tap, long press, swipe, drag, hold/release, or
 * several strokes at once (multi-touch). Game-level meaning ("fill row 3, columns 2–6") lives in
 * {@code label}; the executor only sees strokes.
 *
 * Every stroke is a path with a start offset and duration (ms). HOLD keeps the finger down at the
 * end ({@code continues}) until a matching RELEASE — on Android this uses continued gesture
 * strokes (API 26+).
 */
public final class GameAction {
    public enum Kind { TAP, LONG_PRESS, SWIPE, DRAG, HOLD, RELEASE, MULTI, MACRO }

    public static final class Stroke {
        public final float[] xy;        // x0,y0,x1,y1,… (at least one point)
        public final long startMs, durationMs;
        public final boolean continues; // finger stays down after this stroke (hold)

        public Stroke(float[] xy, long startMs, long durationMs, boolean continues) {
            if (xy == null || xy.length < 2 || xy.length % 2 != 0) throw new IllegalArgumentException("stroke needs x,y pairs");
            if (startMs < 0 || durationMs <= 0) throw new IllegalArgumentException("bad stroke timing");
            this.xy = xy.clone();
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.continues = continues;
        }
    }

    public final Kind kind;
    public final String label;
    private final List<Stroke> strokes;
    /** Who chose it: "user", "solver", "policy", "planner", "explore"… — stored with the experience. */
    public String source = "";

    private GameAction(Kind kind, String label, List<Stroke> strokes) {
        this.kind = kind;
        this.label = label;
        this.strokes = Collections.unmodifiableList(new ArrayList<>(strokes));
    }

    public List<Stroke> strokes() { return strokes; }

    public static GameAction tap(float x, float y, String label) {
        return new GameAction(Kind.TAP, label, Collections.singletonList(new Stroke(new float[]{x, y}, 0, 50, false)));
    }

    public static GameAction longPress(float x, float y, long ms, String label) {
        return new GameAction(Kind.LONG_PRESS, label, Collections.singletonList(new Stroke(new float[]{x, y}, 0, Math.max(500, ms), false)));
    }

    public static GameAction swipe(float x0, float y0, float x1, float y1, long ms, String label) {
        return new GameAction(Kind.SWIPE, label, Collections.singletonList(new Stroke(new float[]{x0, y0, x1, y1}, 0, Math.max(10, ms), false)));
    }

    /** A drag along several points (path), e.g. moving a piece around an obstacle. */
    public static GameAction drag(float[] path, long ms, String label) {
        return new GameAction(Kind.DRAG, label, Collections.singletonList(new Stroke(path, 0, Math.max(10, ms), false)));
    }

    /** Press and keep holding at (x, y); end it with {@link #release}. */
    public static GameAction hold(float x, float y, long ms, String label) {
        return new GameAction(Kind.HOLD, label, Collections.singletonList(new Stroke(new float[]{x, y}, 0, Math.max(10, ms), true)));
    }

    public static GameAction release(float x, float y, String label) {
        return new GameAction(Kind.RELEASE, label, Collections.singletonList(new Stroke(new float[]{x, y}, 0, 10, false)));
    }

    /** Several strokes at once (multi-touch), e.g. two thumbs. */
    public static GameAction multi(List<Stroke> strokes, String label) {
        if (strokes.isEmpty()) throw new IllegalArgumentException("no strokes");
        return new GameAction(Kind.MULTI, label, strokes);
    }

    /**
     * A sequence of several gestures recorded as one step (e.g. the Nonogram.com solver filling
     * several rows between two board checks). Not executed directly — a record only.
     */
    public static GameAction macro(int gestures, String label) {
        return new GameAction(Kind.MACRO, label + " (" + gestures + " gestures)", Collections.<Stroke>emptyList());
    }

    /** Null if every point is on screen; otherwise what's wrong. */
    public String checkBounds(int screenW, int screenH) {
        for (Stroke s : strokes) {
            for (int i = 0; i < s.xy.length; i += 2) {
                if (s.xy[i] < 0 || s.xy[i + 1] < 0 || s.xy[i] >= screenW || s.xy[i + 1] >= screenH) {
                    return String.format(Locale.US, "point %.0f,%.0f is off the %dx%d screen", s.xy[i], s.xy[i + 1], screenW, screenH);
                }
            }
        }
        return null;
    }

    /** Total time from first touch to last lift. */
    public long durationMs() {
        long end = 0;
        for (Stroke s : strokes) end = Math.max(end, s.startMs + s.durationMs);
        return end;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"kind\":").append(Json.q(kind.name())).append(",\"label\":").append(Json.q(label))
                .append(",\"source\":").append(Json.q(source)).append(",\"strokes\":[");
        for (int i = 0; i < strokes.size(); i++) {
            Stroke s = strokes.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"xy\":").append(Json.arr(s.xy)).append(",\"at\":").append(s.startMs).append(",\"ms\":").append(s.durationMs);
            if (s.continues) sb.append(",\"hold\":true");
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    @Override public String toString() { return kind.name().toLowerCase(Locale.US) + " · " + label; }
}
