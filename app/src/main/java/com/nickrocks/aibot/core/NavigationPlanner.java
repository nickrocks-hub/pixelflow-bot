package com.nickrocks.aibot.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Finds paths on the floor grid read from the screen, picks which door to take using the minimap,
 * remembers which rooms were visited, sweeps rooms for things it hasn't walked over, and notices
 * when the character is stuck.
 */
public final class NavigationPlanner {

    // ---- dungeon map in absolute room coordinates (the minimap keeps the current room centred)
    public int absX, absY;
    private final Map<Long, Long> visitedAt = new HashMap<>();
    private final Map<Long, Integer> known = new HashMap<>();     // 1 visited, 2 seen not visited
    public int[] lastExitDir;          // direction we left the previous room by

    // ---- coverage of the current room (cells in room coordinates)
    private final Map<Long, Boolean> covered = new HashMap<>();
    private int coverCells;

    // ---- stuck detection
    private float stuckRefX, stuckRefY;
    private long stuckRefT = -1;
    private final List<float[]> blocked = new ArrayList<>();   // {roomX, roomY, untilMs}
    public long escapeUntil;
    public float escapeX, escapeY;
    public int stuckCount;
    private long lastStuckT;

    // ---- last plan, for the overlay
    public final List<float[]> path = new ArrayList<>();
    public String goalText = "";

    public void resetFloor() {
        visitedAt.clear(); known.clear(); absX = absY = 0; lastExitDir = null;
    }

    public void enterRoom(int[] dir, long t) {
        if (dir != null) { absX += dir[0]; absY += dir[1]; }
        visitedAt.put(key(absX, absY), t);
        known.put(key(absX, absY), 1);
        covered.clear(); coverCells = 0;
        blocked.clear();
        stuckRefT = -1;
    }

    public void updateMap(Observation.MapRead m, long t) {
        if (!m.ok) return;
        known.put(key(absX, absY), 1);
        visitedAt.putIfAbsent(key(absX, absY), t);
        for (int[] c : m.visited) known.put(key(absX + c[0] - m.curX, absY + c[1] - m.curY), 1);
        for (int[] c : m.unvisited) {
            long k = key(absX + c[0] - m.curX, absY + c[1] - m.curY);
            if (!Integer.valueOf(1).equals(known.get(k))) known.put(k, 2);
        }
    }

    public int unvisitedCount() {
        int n = 0;
        for (int v : known.values()) if (v == 2) n++;
        return n;
    }

    public int knownCount() { return known.size(); }

    /**
     * Which way to leave the room: towards the nearest unvisited room on the map (breadth-first over
     * known rooms), or, when every known room is visited, towards the one visited longest ago.
     * Returns {dx, dy} or null if the map gives nothing.
     */
    public int[] exitDirection() {
        if (known.isEmpty()) return null;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        // BFS
        Map<Long, int[]> firstStep = new HashMap<>();
        java.util.ArrayDeque<long[]> q = new java.util.ArrayDeque<>();
        q.add(new long[]{absX, absY});
        firstStep.put(key(absX, absY), null);
        long bestOld = Long.MAX_VALUE; int[] oldDir = null;
        while (!q.isEmpty()) {
            long[] c = q.poll();
            for (int[] d : dirs) {
                int nx = (int) c[0] + d[0], ny = (int) c[1] + d[1];
                long k = key(nx, ny);
                if (!known.containsKey(k) || firstStep.containsKey(k)) continue;
                int[] fs = firstStep.get(key((int) c[0], (int) c[1]));
                if (fs == null) fs = d;
                firstStep.put(k, fs);
                if (known.get(k) == 2) return fs;
                Long va = visitedAt.get(k);
                long age = va == null ? Long.MIN_VALUE : va;
                if (age < bestOld) { bestOld = age; oldDir = fs; }
                q.add(new long[]{nx, ny});
            }
        }
        return oldDir;
    }

    // ------------------------------------------------------------------------------ coverage

    public void cover(Observation o, float camAccX, float camAccY) {
        if (o.player == null || o.walk == null) return;
        int c = o.cell;
        float tile = 19 * o.unit;
        int r = Math.max(1, Math.round(tile * 1.4f / c));
        int px = (int) ((o.player.x - camAccX) / c), py = (int) ((o.player.y - camAccY) / c);
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
            long k = key(px + dx, py + dy);
            if (covered.put(k, true) == null) coverCells++;
        }
    }

    public boolean isCovered(int roomCx, int roomCy) { return covered.containsKey(key(roomCx, roomCy)); }

    /** Share of the room's walkable cells already walked near (0..1). */
    public float coverage(Observation o, float camAccX, float camAccY) {
        if (o.walk == null) return 1;
        int tot = 0, cov = 0;
        int ox = Math.round(camAccX / o.cell), oy = Math.round(camAccY / o.cell);
        for (int y = 0; y < o.gh; y++) for (int x = 0; x < o.gw; x++) {
            if (!o.walk[y * o.gw + x]) continue;
            tot++;
            if (covered.containsKey(key(x - ox, y - oy))) cov++;
        }
        return tot == 0 ? 1 : cov / (float) tot;
    }

    // ------------------------------------------------------------------------------ targets

    /** The walkable cell furthest towards {@code dir} (a doorway), near the room's middle line. */
    public int[] doorCell(Observation o, int[] dir) {
        if (o.walk == null) return null;
        float cx = (o.roomX0 + o.roomX1) / 2f / o.cell, cy = (o.roomY0 + o.roomY1) / 2f / o.cell;
        int best = -1; float bestS = -1e9f;
        for (int y = 0; y < o.gh; y++) for (int x = 0; x < o.gw; x++) {
            if (!o.walk[y * o.gw + x]) continue;
            float along = dir[0] * x + dir[1] * y;
            float across = dir[0] != 0 ? Math.abs(y - cy) : Math.abs(x - cx);
            float s = along * 3 - across;
            if (s > bestS) { bestS = s; best = y * o.gw + x; }
        }
        return best < 0 ? null : new int[]{best % o.gw, best / o.gw};
    }

    /** Nearest walkable cell not yet walked near, or null. */
    public int[] sweepCell(Observation o, float camAccX, float camAccY) {
        if (o.walk == null || o.player == null) return null;
        int ox = Math.round(camAccX / o.cell), oy = Math.round(camAccY / o.cell);
        int px = (int) (o.player.x / o.cell), py = (int) (o.player.y / o.cell);
        int best = -1; float bd = 1e9f;
        for (int y = 0; y < o.gh; y++) for (int x = 0; x < o.gw; x++) {
            if (!o.walk[y * o.gw + x] || covered.containsKey(key(x - ox, y - oy))) continue;
            if (!clearAround(o, x, y)) continue;
            float d = Math.abs(x - px) + Math.abs(y - py);
            if (d < bd) { bd = d; best = y * o.gw + x; }
        }
        return best < 0 ? null : new int[]{best % o.gw, best / o.gw};
    }

    private static boolean clearAround(Observation o, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int nx = x + dx, ny = y + dy;
            if (nx < 0 || ny < 0 || nx >= o.gw || ny >= o.gh || !o.walk[ny * o.gw + nx]) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------------ paths

    /**
     * Shortest path (8 directions) from the player to cell (tx, ty). Cells next to walls and near
     * enemies cost more, and temporarily blocked spots (where we got stuck) are avoided.
     * Returns the direction to the next waypoint a few cells ahead, or null if unreachable.
     */
    public float[] pathDir(Observation o, int tx, int ty, float camAccX, float camAccY, long now) {
        path.clear();
        if (o.walk == null || o.player == null) return null;
        int gw = o.gw, gh = o.gh, n = gw * gh;
        int sx = clamp((int) (o.player.x / o.cell), 0, gw - 1), sy = clamp((int) (o.player.y / o.cell), 0, gh - 1);
        float[] cost = new float[n];
        float tile = 19 * o.unit;
        for (int i = 0; i < n; i++) {
            if (!o.walk[i]) { cost[i] = -1; continue; }
            int x = i % gw, y = i / gw;
            float c = 1;
            if (x == 0 || y == 0 || x == gw - 1 || y == gh - 1 || !o.walk[i - 1] || !o.walk[i + 1] || !o.walk[i - gw] || !o.walk[i + gw]) c += 2.5f;
            float wx = (x + 0.5f) * o.cell, wy = (y + 0.5f) * o.cell;
            for (Observation.Thing e : o.enemies) {
                float d = (float) Math.hypot(wx - e.x, wy - e.y);
                if (d < tile * 2.5f) c += 6 * (1 - d / (tile * 2.5f));
            }
            for (float[] b : blocked) {
                if (b[2] < now) continue;
                if (Math.hypot(wx - (b[0] + camAccX), wy - (b[1] + camAccY)) < tile * 0.9f) c += 40;
            }
            cost[i] = c;
        }
        // let the start cell be walkable even if the reader marked it (the player stands on it)
        cost[sy * gw + sx] = 1;
        tx = clamp(tx, 0, gw - 1); ty = clamp(ty, 0, gh - 1);
        if (cost[ty * gw + tx] < 0) return null;
        float[] dist = new float[n];
        int[] prev = new int[n];
        java.util.Arrays.fill(dist, Float.MAX_VALUE);
        java.util.Arrays.fill(prev, -1);
        PriorityQueue<float[]> pq = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));
        int s = sy * gw + sx, t = ty * gw + tx;
        dist[s] = 0;
        pq.add(new float[]{0, s});
        int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DY = {0, 0, 1, -1, 1, -1, 1, -1};
        while (!pq.isEmpty()) {
            float[] c = pq.poll();
            int u = (int) c[1];
            if (c[0] > dist[u]) continue;
            if (u == t) break;
            int ux = u % gw, uy = u / gw;
            for (int k = 0; k < 8; k++) {
                int vx = ux + DX[k], vy = uy + DY[k];
                if (vx < 0 || vy < 0 || vx >= gw || vy >= gh) continue;
                int v = vy * gw + vx;
                if (cost[v] < 0) continue;
                if (k >= 4 && (cost[uy * gw + vx] < 0 || cost[vy * gw + ux] < 0)) continue;   // no corner cutting
                float nd = dist[u] + cost[v] * (k >= 4 ? 1.414f : 1f);
                if (nd < dist[v]) { dist[v] = nd; prev[v] = u; pq.add(new float[]{nd, v}); }
            }
        }
        if (prev[t] < 0 && t != s) return null;
        List<Integer> cells = new ArrayList<>();
        for (int v = t; v != -1; v = prev[v]) { cells.add(0, v); if (v == s) break; }
        for (int v : cells) path.add(new float[]{(v % gw + 0.5f) * o.cell, (v / gw + 0.5f) * o.cell});
        // aim at a point ~3 cells along the path (smoother than the next cell)
        int look = Math.min(cells.size() - 1, 3);
        if (look <= 0) return new float[]{0, 0};
        float[] wp = path.get(look);
        float dx = wp[0] - o.player.x, dy = wp[1] - o.player.y;
        float len = (float) Math.hypot(dx, dy);
        return len < 1e-3f ? new float[]{0, 0} : new float[]{dx / len, dy / len};
    }

    // ------------------------------------------------------------------------------ stuck

    /**
     * Call every tick with the stick deflection we commanded. Returns true once when the character
     * has been pushing for over a second without moving (relative to the room).
     */
    public boolean checkStuck(Observation o, float moveMag, float mvx, float mvy, float camAccX, float camAccY, long now) {
        if (o.player == null || moveMag < 0.5f || now < escapeUntil) { stuckRefT = -1; return false; }
        float rx = o.player.x - camAccX, ry = o.player.y - camAccY;
        float tile = 19 * o.unit;
        if (stuckRefT < 0 || Math.hypot(rx - stuckRefX, ry - stuckRefY) > tile * 0.45f) {
            stuckRefX = rx; stuckRefY = ry; stuckRefT = now;
            return false;
        }
        if (now - stuckRefT < 1200) return false;
        // stuck: remember the spot ahead as blocked for a while and side-step
        blocked.add(new float[]{rx + mvx * tile * 0.9f, ry + mvy * tile * 0.9f, now + 8000});
        boolean left = (stuckCount & 1) == 0;
        escapeX = left ? mvy : -mvy; escapeY = left ? -mvx : mvx;
        if (now - lastStuckT < 6000) { escapeX = -mvx * 0.7f + escapeX * 0.7f; escapeY = -mvy * 0.7f + escapeY * 0.7f; }
        escapeUntil = now + 700;
        stuckCount++;
        lastStuckT = now;
        stuckRefT = -1;
        return true;
    }

    static long key(int x, int y) { return ((long) x << 32) ^ (y & 0xffffffffL); }
    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
