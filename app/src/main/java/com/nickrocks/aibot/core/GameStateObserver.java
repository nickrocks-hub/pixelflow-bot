package com.nickrocks.aibot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the game from screen pixels (0xRRGGBB, capture resolution) into an {@link Observation}.
 * It only uses what is drawn on screen: HUD hearts and ULT bar, the minimap, the room floor, and the
 * sprites on it. Objects are tracked from frame to frame, with the camera's own movement removed, so
 * the bot can tell moving things (enemies, projectiles) from furniture.
 *
 * Not thread-safe; one instance per capture stream.
 */
public final class GameStateObserver {

    private final ScreenLayout L;

    // ---- per-frame buffers
    private int w, h, cell, gw, gh;
    private float unit;
    private int[] cellBg;            // mean floor colour per cell, -1 = none
    private byte[] cellType;         // 0 none/void, 1 floor, 2 wall/other
    private boolean[] interior;
    private boolean[] sal;           // salient at 2x2 block resolution
    private int bw, bh;
    private int[] label;
    private int[] gray, prevGray;
    private int grayW, grayH;
    private boolean[] noObj;         // blocks where objects are never read (controls, HUD)

    // ---- tracking
    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;
    private int playerId = -1;
    private long lastT = -1;
    private float playerX = -1, playerY = -1;
    private float camAccX, camAccY;

    /** Debug image: classes per block, for the replay tool and the overlay. */
    public byte[] debugBlocks;
    public int debugBw, debugBh;

    public GameStateObserver(ScreenLayout layout) { this.L = layout; }

    public void reset() {
        tracks.clear();
        playerId = -1;
        lastT = -1;
        prevGray = null;
        playerX = playerY = -1;
    }

    private static final class Track {
        int id;
        float x, y, vx, vy, w, h;
        int area;
        long born, seen;
        int frames;
        float moved;              // largest distance from where it first appeared, in room coordinates
        float wx0, wy0;
        int movingFrames;
        long lastMoveT;
        boolean ownShot, bornNearPlayer;
        Observation.Kind kind = Observation.Kind.PROP;
        Feat f;
        String sig = "";
        boolean enemyLook;
        boolean matched;
    }

    /** Colour features of one blob. */
    static final class Feat {
        int n, dark, red, orange, yellow, magenta, white, pink, blue, cyan, purple, green;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        long sx, sy;
        long rsx, rsy; int rn;          // red (plaid) centroid
        void add(int x, int y, int c) {
            n++; sx += x; sy += y;
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            if (mx < 32) { dark++; return; }
            if (mn > 205) { white++; return; }
            if (r > 140 && g < 70 && b < 90 && r - g > 100) { red++; rsx += x; rsy += y; rn++; return; }
            if (r > 190 && g > 80 && g < 175 && b < 90) { orange++; return; }
            if (r > 180 && g > 165 && b < 130) { yellow++; return; }
            if (r > 160 && b > 160 && g < 140 && Math.abs(r - b) < 80) { magenta++; return; }
            if (r > 200 && g > 90 && b > 110 && r > b) { pink++; return; }
            if (b > 150 && g > 150 && r < 140) { cyan++; return; }
            if (b > 120 && b > r + 30 && b > g + 20) { blue++; return; }
            if (g > 130 && g > r + 40 && g > b + 40) { green++; return; }
            if (b > 90 && r > 70 && g < Math.min(r, b) - 25) { purple++; }
        }
        float cx() { return n == 0 ? 0 : (float) sx / n; }
        float cy() { return n == 0 ? 0 : (float) sy / n; }
        int bwid() { return x1 - x0 + 1; }
        int bhei() { return y1 - y0 + 1; }
    }

    // ------------------------------------------------------------------------------------------

    public Observation observe(int[] px, int w, int h, long tMs) {
        Observation o = new Observation();
        o.timeMs = tMs;
        o.w = w; o.h = h;
        if (w != this.w || h != this.h) alloc(w, h);
        o.unit = unit;
        float dt = lastT < 0 ? 0.05f : Math.max(0.005f, Math.min(0.5f, (tMs - lastT) / 1000f));
        lastT = tMs;

        curPx = px;
        readHud(px, o);
        readMap(px, o);
        o.hurtFlash = hurtFlash(px);
        // the red damage vignette and menus make the hearts unreadable for a moment
        if (o.hurtFlash || !o.hudVisible) { o.hp = -1; o.hpMax = -1; }

        if (!o.hudVisible || o.paused) {
            prevGray = null;
            return o;
        }
        cameraShift(px, o);
        classifyCells(px);
        findRoom(o);
        findPlayer(px, o);
        findSalient(px);
        List<Feat> blobs = blobs(px);
        track(blobs, o, dt);
        buildWalk(o);
        return o;
    }

    private void alloc(int w, int h) {
        this.w = w; this.h = h;
        unit = Math.min(w, h) / 432f;
        cell = Math.max(4, Math.round(8 * unit));
        gw = (w + cell - 1) / cell; gh = (h + cell - 1) / cell;
        cellBg = new int[gw * gh];
        cellType = new byte[gw * gh];
        interior = new boolean[gw * gh];
        objOk = new boolean[gw * gh];
        bigHole = new boolean[gw * gh];
        bw = w / 2; bh = h / 2;
        sal = new boolean[bw * bh];
        label = new int[bw * bh];
        debugBlocks = new byte[bw * bh];
        debugBw = bw; debugBh = bh;
        grayW = w / 4; grayH = h / 4;
        gray = new int[grayW * grayH];
        prevGray = null;
        noObj = new boolean[bw * bh];
        int hudB = (int) (L.hudBottom * h);
        float sr = L.stickR * h * 1.55f, br = 0.05f * h;
        for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
            int x = bx * 2, y = by * 2;
            boolean no = y < hudB;
            no |= near(x, y, L.moveX * w, L.moveY * h, sr) || near(x, y, L.singX * w, L.singY * h, sr);
            for (int i = 0; i < 4; i++) no |= near(x, y, L.btnX[i] * w, L.btnY[i] * h, br);
            no |= near(x, y, L.ultX * w, L.ultY * h, br);
            noObj[by * bw + bx] = no;
        }
        tracks.clear();
    }

    private static boolean near(float x, float y, float cx, float cy, float r) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy < r * r;
    }

    // ------------------------------------------------------------------------------ HUD

    private void readHud(int[] px, Observation o) {
        // portrait: a red ring. Bright = playing, dim = a menu darkens the game (pause, game over)
        int cx = (int) (L.portraitX * w), cy = (int) (L.portraitY * h), r = (int) (L.portraitR * h);
        int bright = 0, dim = 0, total = 0;
        for (int y = cy - r; y <= cy + r; y++) for (int x = cx - r; x <= cx + r; x++) {
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            int c = px[y * w + x], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
            total++;
            if (R > 150 && G < 80 && B < 110 && R - G > 110) bright++;
            else if (R > 55 && R < 150 && G < 45 && B < 75 && R - G > 40) dim++;
        }
        o.hudVisible = bright > total * 0.03f;
        o.paused = !o.hudVisible && dim > total * 0.03f;

        // hearts: one scan line through their middle
        int y = (int) (L.heartsY * h);
        int x0 = (int) (L.heartsX0 * w), x1 = (int) (L.heartsX1 * w);
        float pitch = 0.0315f * h;
        int runStart = -1, gap = 0;
        int redCount = 0, filled = 0;
        List<int[]> runs = new ArrayList<>();
        byte[] cls = new byte[x1 - x0 + 1];
        for (int x = x0; x <= x1 && x < w; x++) {
            int red = 0, gr = 0;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = Math.max(0, Math.min(h - 1, y + dy));
                int c = px[yy * w + x], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
                int mx = Math.max(R, Math.max(G, B));
                if (R > 140 && G < 100 && B < 120 && R - G > 70) red++;
                else if (mx > 30 && mx < 130 && Math.abs(R - B) < 40 && Math.abs(R - G) < 45) gr++;
            }
            byte k = red >= 2 ? (byte) 1 : gr >= 2 ? (byte) 2 : 0;
            cls[x - x0] = k;
            if (k != 0) {
                if (runStart < 0) runStart = x;
                gap = 0;
            } else if (runStart >= 0 && ++gap > Math.max(2, pitch * 0.35f)) {
                runs.add(new int[]{runStart, x - gap});
                runStart = -1; gap = 0;
            }
        }
        if (runStart >= 0) runs.add(new int[]{runStart, Math.min(x1, w - 1)});
        int slots = 0, half = 0;
        for (int[] run : runs) {
            int len = run[1] - run[0] + 1;
            if (len < pitch * 0.45f) continue;
            int n = Math.max(1, Math.round(len / pitch));
            float seg = len / (float) n;
            for (int i = 0; i < n; i++) {
                int a = run[0] + Math.round(i * seg), b = run[0] + Math.round((i + 1) * seg) - 1;
                int red = 0, tot = 0;
                for (int x = a; x <= b; x++) { int k = cls[x - x0]; if (k != 0) { tot++; if (k == 1) red++; } }
                if (tot == 0) continue;
                float f = red / (float) tot;
                slots++;
                if (f > 0.7f) half += 2; else if (f > 0.2f) half += 1;
            }
            if (slots > 12) break;
        }
        if (slots > 0 && slots <= 12) { o.hp = half; o.hpMax = slots * 2; }

        // ULT bar and star button
        int uy = (int) (L.ultBarY * h), ux0 = (int) (L.ultBarX0 * w), ux1 = (int) (L.ultBarX1 * w);
        int fill = 0, yel = 0, n = 0;
        for (int x = ux0; x <= ux1 && x < w; x++) {
            n++;
            int c = px[uy * w + x], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
            if (G > 130 && R < 170 && B < 140 && G > R) fill++;
            else if (R > 170 && G > 150 && B < 130) { fill++; yel++; }
        }
        o.ultFill = n == 0 ? 0 : fill / (float) n;
        int sx = (int) (L.ultX * w), sy = (int) (L.ultY * h), sr = (int) (0.035f * h), gold = 0, st = 0;
        for (int yy = sy - sr; yy <= sy + sr; yy += 2) for (int xx = sx - sr; xx <= sx + sr; xx += 2) {
            if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
            st++;
            int c = px[yy * w + xx], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
            if (R > 110 && G > 60 && G < 200 && B < 90 && R > G + 20) gold++;
        }
        o.ultReady = (n > 0 && yel > n * 0.75f) || (st > 0 && gold > st * 0.35f);
    }

    private boolean hurtFlash(int[] px) {
        int red = 0, n = 0;
        int[][] spots = {{2, 40}, {2, 60}, {98, 40}, {98, 60}, {50, 99}, {3, 97}, {97, 97}};
        for (int[] s : spots) {
            int cx = s[0] * (w - 1) / 100, cy = s[1] * (h - 1) / 100;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
                int x = Math.max(0, Math.min(w - 1, cx + dx)), y = Math.max(0, Math.min(h - 1, cy + dy));
                int c = px[y * w + x], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
                n++;
                if (R > 110 && R > G * 3 && R > B * 1.8f) red++;
            }
        }
        return red > n * 0.45f;
    }

    // ---------------------------------------------------------------------------- minimap

    private void readMap(int[] px, Observation o) {
        int x0 = (int) (L.mapX0 * w), x1 = (int) (L.mapX1 * w), y0 = (int) (L.mapY0 * h), y1 = (int) (L.mapY1 * h);
        int mw = x1 - x0, mh = y1 - y0;
        if (mw < 8 || mh < 8) return;
        // 1 = white (current), 2 = visited (light grey), 3 = seen, not visited (dark grey)
        byte[] k = new byte[mw * mh];
        for (int y = 0; y < mh; y++) for (int x = 0; x < mw; x++) {
            int c = px[(y0 + y) * w + x0 + x], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
            int mx = Math.max(R, Math.max(G, B)), mn = Math.min(R, Math.min(G, B));
            if (mn > 190) k[y * mw + x] = 1;
            else if (mx >= 78 && mx <= 150 && mx - mn < 50 && Math.abs(R - B) < 40) k[y * mw + x] = 2;
            else if (mx >= 36 && mx < 78 && mx - mn < 30 && Math.abs(R - B) < 30) k[y * mw + x] = 3;
        }
        // connected pieces of each kind
        int[] lab = new int[mw * mh];
        int[] stack = new int[mw * mh];
        List<float[]> parts = new ArrayList<>();   // {cx, cy, kind, size}
        int nl = 0;
        for (int i = 0; i < mw * mh; i++) {
            if (k[i] == 0 || lab[i] != 0) continue;
            nl++;
            int sp = 0; stack[sp++] = i; lab[i] = nl;
            long sx = 0, sy = 0; int n = 0;
            while (sp > 0) {
                int j = stack[--sp]; int jx = j % mw, jy = j / mw;
                sx += jx; sy += jy; n++;
                for (int d = 0; d < 4; d++) {
                    int nx = jx + (d == 0 ? 1 : d == 1 ? -1 : 0), ny = jy + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx < 0 || ny < 0 || nx >= mw || ny >= mh) continue;
                    int q = ny * mw + nx;
                    if (lab[q] == 0 && k[q] == k[i]) { lab[q] = nl; stack[sp++] = q; }
                }
            }
            float minArea = (L.mapPitchX * w) * (L.mapPitchY * h) * 0.22f;
            if (n >= minArea) parts.add(new float[]{(float) sx / n, (float) sy / n, k[i], n});
        }
        float[] cur = null;
        for (float[] p : parts) if (p[2] == 1 && (cur == null || p[3] > cur[3])) cur = p;
        if (cur == null) return;
        float px_ = L.mapPitchX * w, py_ = L.mapPitchY * h;
        o.map.ok = true;
        // absolute grid coordinates, anchored to the minimap frame, so moving between rooms changes them
        o.map.curX = Math.round(cur[0] / px_);
        o.map.curY = Math.round(cur[1] / py_);
        for (float[] p : parts) {
            if (p == cur) continue;
            int gx = Math.round(p[0] / px_), gy = Math.round(p[1] / py_);
            if (gx == o.map.curX && gy == o.map.curY) continue;
            if (p[2] == 2) o.map.visited.add(new int[]{gx, gy});
            else if (p[2] == 3) o.map.unvisited.add(new int[]{gx, gy});
        }
    }

    // ------------------------------------------------------------------------ camera shift

    private void cameraShift(int[] px, Observation o) {
        int top = (int) (L.hudBottom * h) / 4, bot = (int) (0.76f * h) / 4;
        for (int y = 0; y < grayH; y++) for (int x = 0; x < grayW; x++) {
            int c = px[(y * 4) * w + x * 4];
            gray[y * grayW + x] = (((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 4 + (c & 255)) >> 3;
        }
        if (prevGray == null) {
            prevGray = gray.clone();
            o.sceneCut = true;
            return;
        }
        int R = 4, bestDx = 0, bestDy = 0;
        long best = Long.MAX_VALUE, zero = 0;
        for (int dy = -R; dy <= R; dy++) for (int dx = -R; dx <= R; dx++) {
            long s = 0; int n = 0;
            for (int y = top + R; y < bot - R; y += 2) {
                int yo = y - dy;
                for (int x = R + 2; x < grayW - R - 2; x += 2) {
                    s += Math.abs(gray[y * grayW + x] - prevGray[yo * grayW + x - dx]);
                    n++;
                }
            }
            if (n == 0) continue;
            s = s * 1000 / n;
            if (dx == 0 && dy == 0) zero = s;
            if (s < best || (s == best && dx == 0 && dy == 0)) { best = s; bestDx = dx; bestDy = dy; }
        }
        // prefer "no shift" unless shifting is clearly better (avoids jitter on still rooms)
        if (zero <= best * 1.08 + 200) { bestDx = 0; bestDy = 0; best = zero; }
        o.camDx = bestDx * 4; o.camDy = bestDy * 4;
        // mean abs difference above ~40 grey levels over the whole playfield = a different picture
        o.sceneCut = best > 40_000;
        System.arraycopy(gray, 0, prevGray, 0, gray.length);
    }

    // ------------------------------------------------------------------------ floor / room

    private static boolean floorish(int R, int G, int B) {
        int mx = Math.max(R, Math.max(G, B)), mn = Math.min(R, Math.min(G, B));
        return mx >= 28 && mx <= 140 && G == mn && R - B < 22 && B - R < 30 && mx - mn >= 12 && mx - mn <= 62;
    }

    private void classifyCells(int[] px) {
        int hudB = (int) (L.hudBottom * h);
        for (int cy = 0; cy < gh; cy++) for (int cx = 0; cx < gw; cx++) {
            int i = cy * gw + cx;
            int y0 = cy * cell, x0 = cx * cell;
            if (y0 < hudB) { cellType[i] = 0; cellBg[i] = -1; continue; }
            int fl = 0, vd = 0, n = 0, pink = 0, bright = 0; long sr = 0, sg = 0, sb = 0;
            for (int y = y0; y < Math.min(h, y0 + cell); y += 2) for (int x = x0; x < Math.min(w, x0 + cell); x += 2) {
                int c = px[y * w + x], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
                n++;
                int mx = Math.max(R, Math.max(G, B));
                if (mx < 24) vd++;
                else if (floorish(R, G, B)) { fl++; sr += R; sg += G; sb += B; }
                if (R - B > 22 && mx > 40) pink++;
                if (mx > 150) bright++;
            }
            if (n == 0) { cellType[i] = 0; cellBg[i] = -1; continue; }
            if (fl >= n * 0.55f && pink < n * 0.2f && bright < n * 0.12f) {
                cellType[i] = 1;
                cellBg[i] = (int) ((sr / fl) << 16 | (sg / fl) << 8 | (sb / fl));
            } else {
                cellType[i] = (byte) (vd >= n * 0.6f ? 0 : 2);
                cellBg[i] = fl >= n * 0.2f ? (int) ((sr / fl) << 16 | (sg / fl) << 8 | (sb / fl)) : -1;
            }
        }
    }

    private void findRoom(Observation o) {
        // largest 4-connected group of floor cells (the one under the player if we know where they are)
        int[] lab = new int[gw * gh];
        int[] stack = new int[gw * gh];
        int best = 0, bestN = 0, nl = 0, playerLab = 0;
        int pc = playerX < 0 ? -1 : ((int) (playerY / cell)) * gw + (int) (playerX / cell);
        for (int i = 0; i < gw * gh; i++) {
            if (cellType[i] != 1 || lab[i] != 0) continue;
            nl++;
            int sp = 0, n = 0; stack[sp++] = i; lab[i] = nl;
            while (sp > 0) {
                int j = stack[--sp]; n++;
                int jx = j % gw, jy = j / gw;
                if (jx > 0 && cellType[j - 1] == 1 && lab[j - 1] == 0) { lab[j - 1] = nl; stack[sp++] = j - 1; }
                if (jx < gw - 1 && cellType[j + 1] == 1 && lab[j + 1] == 0) { lab[j + 1] = nl; stack[sp++] = j + 1; }
                if (jy > 0 && cellType[j - gw] == 1 && lab[j - gw] == 0) { lab[j - gw] = nl; stack[sp++] = j - gw; }
                if (jy < gh - 1 && cellType[j + gw] == 1 && lab[j + gw] == 0) { lab[j + gw] = nl; stack[sp++] = j + gw; }
            }
            if (n > bestN) { bestN = n; best = nl; }
        }
        if (pc >= 0 && pc < lab.length) {
            // the player's own cells are "not floor"; look a little around them
            for (int r = 0; r <= 4 && playerLab == 0; r++)
                for (int dy = -r; dy <= r && playerLab == 0; dy++) for (int dx = -r; dx <= r; dx++) {
                    int x = (int) (playerX / cell) + dx, y = (int) (playerY / cell) + dy;
                    if (x < 0 || y < 0 || x >= gw || y >= gh) continue;
                    int l = lab[y * gw + x];
                    if (l != 0) { playerLab = l; break; }
                }
        }
        int room = best;
        if (playerLab != 0) {
            int pn = 0;
            for (int l : lab) if (l == playerLab) pn++;
            if (pn > bestN * 0.25f) room = playerLab;
        }
        // interior = room cells + anything fully enclosed by them (furniture, enemies, the player)
        boolean[] outside = new boolean[gw * gh];
        for (int x = 0; x < gw; x++) { push(outside, stack, lab, room, x, 0); push(outside, stack, lab, room, x, gh - 1); }
        for (int y = 0; y < gh; y++) { push(outside, stack, lab, room, 0, y); push(outside, stack, lab, room, gw - 1, y); }
        // flood from every border cell that isn't room
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int i = 0; i < gw * gh; i++) if (outside[i]) q.add(i);
        while (!q.isEmpty()) {
            int j = q.poll(); int jx = j % gw, jy = j / gw;
            int[] nb = {jx > 0 ? j - 1 : -1, jx < gw - 1 ? j + 1 : -1, jy > 0 ? j - gw : -1, jy < gh - 1 ? j + gw : -1};
            for (int k : nb) if (k >= 0 && !outside[k] && lab[k] != room) { outside[k] = true; q.add(k); }
        }
        int rx0 = gw, ry0 = gh, rx1 = -1, ry1 = -1;
        java.util.Arrays.fill(bigHole, false);
        for (int i = 0; i < gw * gh; i++) {
            interior[i] = room != 0 && !outside[i];
            if (lab[i] == room && room != 0) {
                int x = i % gw, y = i / gw;
                rx0 = Math.min(rx0, x); ry0 = Math.min(ry0, y); rx1 = Math.max(rx1, x); ry1 = Math.max(ry1, y);
            }
        }
        // the player standing at the very edge of the room can make the enclosure leak: add a margin
        if (rx1 >= 0) {
            for (int y = ry0; y <= ry1; y++) for (int x = rx0; x <= rx1; x++) {
                int i = y * gw + x;
                if (!interior[i] && cellType[i] == 2 && enclosedRow(x, y, room, lab) && enclosedCol(x, y, room, lab)) interior[i] = true;
            }
            o.roomX0 = rx0 * cell; o.roomY0 = ry0 * cell; o.roomX1 = (rx1 + 1) * cell; o.roomY1 = (ry1 + 1) * cell;
        }
        roomLab = lab; roomId = room;
        // enclosed non-floor patches: small ones are things standing on the floor, big ones are walls/pillars
        java.util.Arrays.fill(objOk, false);
        int[] hl = new int[gw * gh];
        int maxHole = Math.round(sq(19 * unit * 4.5f / cell));
        int nh = 0;
        for (int i = 0; i < gw * gh; i++) {
            if (!interior[i]) continue;
            if (lab[i] == room) { objOk[i] = true; continue; }
            if (hl[i] != 0) continue;
            nh++;
            q.clear(); q.add(i); hl[i] = nh;
            List<Integer> members = new ArrayList<>();
            while (!q.isEmpty()) {
                int j = q.poll(); members.add(j);
                int jx = j % gw, jy = j / gw;
                int[] nb = {jx > 0 ? j - 1 : -1, jx < gw - 1 ? j + 1 : -1, jy > 0 ? j - gw : -1, jy < gh - 1 ? j + gw : -1};
                for (int k : nb) if (k >= 0 && interior[k] && lab[k] != room && hl[k] == 0) { hl[k] = nh; q.add(k); }
            }
            boolean small = members.size() <= maxHole;
            for (int j : members) { objOk[j] = small; if (!small) bigHole[j] = true; }
        }
        // the room's edge strip (wall lip, door frames) is never an object
        boolean[] ok2 = objOk.clone();
        for (int y = 0; y < gh; y++) for (int x = 0; x < gw; x++) {
            int i = y * gw + x;
            if (!objOk[i]) continue;
            boolean edge = x == 0 || y == 0 || x == gw - 1 || y == gh - 1
                    || !interior[i - 1] || !interior[i + 1] || !interior[i - gw] || !interior[i + gw]
                    || bigHole[i - 1] || bigHole[i + 1] || bigHole[i - gw] || bigHole[i + gw];
            if (edge) ok2[i] = false;
        }
        System.arraycopy(ok2, 0, objOk, 0, ok2.length);
    }

    private static float sq(float v) { return v * v; }
    private boolean[] objOk, bigHole;

    private int[] roomLab; private int roomId;

    private void push(boolean[] outside, int[] stack, int[] lab, int room, int x, int y) {
        int i = y * gw + x;
        if (lab[i] != room) outside[i] = true;
    }

    private boolean enclosedRow(int x, int y, int room, int[] lab) {
        boolean l = false, r = false;
        for (int k = 1; k < 12 && !l; k++) if (x - k >= 0 && lab[y * gw + x - k] == room) l = true;
        for (int k = 1; k < 12 && !r; k++) if (x + k < gw && lab[y * gw + x + k] == room) r = true;
        return l && r;
    }

    private boolean enclosedCol(int x, int y, int room, int[] lab) {
        boolean u = false, d = false;
        for (int k = 1; k < 12 && !u; k++) if (y - k >= 0 && lab[(y - k) * gw + x] == room) u = true;
        for (int k = 1; k < 12 && !d; k++) if (y + k < gh && lab[(y + k) * gw + x] == room) d = true;
        return u && d;
    }

    // ------------------------------------------------------------------------ objects

    private int bgAt(int x, int y) {
        int cx = Math.min(gw - 1, x / cell), cy = Math.min(gh - 1, y / cell);
        int bg = cellBg[cy * gw + cx];
        if (bg >= 0 && cellType[cy * gw + cx] == 1) return bg;
        // nearest floor colour around
        long sr = 0, sg = 0, sb = 0; int n = 0;
        for (int r = 1; r <= 3 && n == 0; r++)
            for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
                int xx = cx + dx, yy = cy + dy;
                if (xx < 0 || yy < 0 || xx >= gw || yy >= gh) continue;
                int c = cellBg[yy * gw + xx];
                if (c < 0) continue;
                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255; n++;
            }
        return n == 0 ? 0x3A2A48 : (int) ((sr / n) << 16 | (sg / n) << 8 | (sb / n));
    }

    private void findSalient(int[] px) {
        for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
            int i = by * bw + bx;
            sal[i] = false;
            debugBlocks[i] = 0;
            int x = bx * 2, y = by * 2;
            int cx = x / cell, cy = y / cell;
            if (noObj[i] || cx >= gw || cy >= gh || !objOk[cy * gw + cx]) continue;
            debugBlocks[i] = 1;
            if (x >= pbx0 && x <= pbx1 && y >= pby0 && y <= pby1) { debugBlocks[i] = 3; continue; }
            int bg = bgAt(x, y);
            int br = (bg >> 16) & 255, bgg = (bg >> 8) & 255, bb = bg & 255;
            int bl = br * 3 + bgg * 4 + bb;
            int hits = 0;
            for (int k = 0; k < 4; k++) {
                int xx = x + (k & 1), yy = y + (k >> 1);
                if (xx >= w || yy >= h) continue;
                int c = px[yy * w + xx], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
                if (G > 200 && R < 60 && B < 60) continue;      // the bot's own debug drawing
                int lum = R * 3 + G * 4 + B;
                int dr = R - br, dg = G - bgg, db = B - bb;
                int d2 = dr * dr + dg * dg + db * db;
                if (lum * 100 < bl * 42 || d2 > 75 * 75) hits++;
            }
            if (hits >= 2) { sal[i] = true; debugBlocks[i] = 2; }
        }
    }

    private List<Feat> blobs(int[] px) {
        java.util.Arrays.fill(label, 0);
        List<Feat> out = new ArrayList<>();
        int[] stack = new int[bw * bh];
        int nl = 0;
        int minBlocks = Math.max(3, Math.round(5 * unit * unit));
        for (int i = 0; i < bw * bh; i++) {
            if (!sal[i] || label[i] != 0) continue;
            nl++;
            Feat f = new Feat();
            int sp = 0; stack[sp++] = i; label[i] = nl;
            while (sp > 0) {
                int j = stack[--sp];
                int jx = j % bw, jy = j / bw;
                int x = jx * 2, y = jy * 2;
                for (int k = 0; k < 4; k++) {
                    int xx = Math.min(w - 1, x + (k & 1)), yy = Math.min(h - 1, y + (k >> 1));
                    f.add(xx, yy, px[yy * w + xx]);
                }
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = jx + dx, ny = jy + dy;
                    if (nx < 0 || ny < 0 || nx >= bw || ny >= bh) continue;
                    int q = ny * bw + nx;
                    if (sal[q] && label[q] == 0) { label[q] = nl; stack[sp++] = q; }
                }
            }
            if (f.n / 4 >= minBlocks) out.add(f);
        }
        // merge pieces that almost touch (bat wings, a hat over a shirt)
        float gap = 3 * unit;
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int a = 0; a < out.size(); a++) for (int b = a + 1; b < out.size(); b++) {
                Feat A = out.get(a), B = out.get(b);
                if (A.x0 - gap <= B.x1 && B.x0 - gap <= A.x1 && A.y0 - gap <= B.y1 && B.y0 - gap <= A.y1) {
                    // don't glue small projectiles onto big things
                    float tile = 19 * unit;
                    boolean smallA = A.bwid() < tile * 0.9f && A.bhei() < tile * 0.9f;
                    boolean smallB = B.bwid() < tile * 0.9f && B.bhei() < tile * 0.9f;
                    if (smallA != smallB && (A.magenta + A.pink + A.white > A.n / 3 || B.magenta + B.pink + B.white > B.n / 3)) continue;
                    int mw = Math.max(A.x1, B.x1) - Math.min(A.x0, B.x0), mh = Math.max(A.y1, B.y1) - Math.min(A.y0, B.y0);
                    if (mw > tile * 2.6f || mh > tile * 2.6f) continue;
                    mergeInto(A, B);
                    out.remove(b);
                    merged = true;
                    break outer;
                }
            }
        }
        return out;
    }

    private static void mergeInto(Feat A, Feat B) {
        A.n += B.n; A.dark += B.dark; A.red += B.red; A.orange += B.orange; A.yellow += B.yellow;
        A.magenta += B.magenta; A.white += B.white; A.pink += B.pink; A.blue += B.blue; A.cyan += B.cyan;
        A.purple += B.purple; A.green += B.green;
        A.x0 = Math.min(A.x0, B.x0); A.y0 = Math.min(A.y0, B.y0); A.x1 = Math.max(A.x1, B.x1); A.y1 = Math.max(A.y1, B.y1);
        A.sx += B.sx; A.sy += B.sy; A.rsx += B.rsx; A.rsy += B.rsy; A.rn += B.rn;
    }

    // ------------------------------------------------------------------------ tracking

    private int[] curPx;

    /** Share of near-black pixels in the box where the cowboy hat sits above a red shirt at (rx, ry). */
    private float hatShare(float rx, float ry) {
        float tile = 19 * unit;
        int x0 = Math.round(rx - tile * 0.7f), x1 = Math.round(rx + tile * 0.7f);
        int y0 = Math.round(ry - tile * 1.5f), y1 = Math.round(ry - tile * 0.55f);
        int n = 0, d = 0;
        for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++)
            for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
                int c = curPx[y * w + x];
                n++;
                if (Math.max((c >> 16) & 255, Math.max((c >> 8) & 255, c & 255)) < 34) d++;
            }
        return n == 0 ? 0 : d / (float) n;
    }

    private float pfx = -1, pfy = -1, pfScore;
    private int pbx0, pby0, pbx1, pby1;   // player box excluded from object reading

    /** Finds the cowboy: a patch of bright red plaid with a black hat right above it. */
    private void findPlayer(int[] px, Observation o) {
        float tile = 19 * unit;
        int c2 = Math.max(4, Math.round(tile * 0.5f));
        int cw = (w + c2 - 1) / c2, ch = (h + c2 - 1) / c2;
        int[] red = new int[cw * ch], yel = new int[cw * ch];
        long[] sx = new long[cw * ch], sy = new long[cw * ch];
        int hudB = (int) (L.hudBottom * h);
        for (int y = hudB; y < h; y += 1) for (int x = 0; x < w; x += 1) {
            int cx = x / cell, cy = y / cell;
            if (!interior[Math.min(gh - 1, cy) * gw + Math.min(gw - 1, cx)]) continue;
            int c = px[y * w + x], R = (c >> 16) & 255, G = (c >> 8) & 255, B = c & 255;
            int k = (y / c2) * cw + x / c2;
            if (R > 140 && G < 70 && B < 90 && R - G > 100) { red[k]++; sx[k] += x; sy[k] += y; }
            else if (R > 180 && G > 120 && B < 110) yel[k]++;
        }
        pfx = pfy = -1; pfScore = 0;
        int need = Math.round(c2 * c2 * 0.12f);
        for (int k = 0; k < red.length; k++) {
            if (red[k] < need) continue;
            // gather the 3x3 neighbourhood
            int kx = k % cw, ky = k / cw;
            long tx = 0, ty = 0; int tn = 0, ty_ = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int nx = kx + dx, ny = ky + dy;
                if (nx < 0 || ny < 0 || nx >= cw || ny >= ch) continue;
                int q = ny * cw + nx;
                tx += sx[q]; ty += sy[q]; tn += red[q]; ty_ += yel[q];
            }
            if (red[k] < tn / 4) continue;          // only score each cluster around its strongest cell
            float rx = (float) tx / tn, ry = (float) ty / tn;
            if (ty_ > tn * 0.35f) continue;          // barrels: red with a big yellow mark
            float hat = hatShare(rx, ry);
            if (hat < 0.14f) continue;
            float sc = tn * (0.5f + hat * 3);
            if (playerX >= 0) {
                float d = (float) Math.hypot(rx - playerX, ry - playerY);
                if (d < tile * 2.5f) sc *= 3; else if (d > tile * 6) sc *= 0.5f;
            }
            if (sc > pfScore) { pfScore = sc; pfx = rx; pfy = ry; }
        }
        if (pfx >= 0) {
            pbx0 = Math.round(pfx - tile * 0.85f); pbx1 = Math.round(pfx + tile * 0.85f);
            pby0 = Math.round(pfy - tile * 1.6f); pby1 = Math.round(pfy + tile * 1.1f);
        } else { pbx0 = pby0 = 1; pbx1 = pby1 = 0; }
    }

    private float playerScore(Feat f) {
        float tile = 19 * unit;
        if (f.rn < 6 * unit * unit) return 0;
        if (f.bwid() > tile * 4 || f.bhei() > tile * 5) return 0;
        // a black cowboy hat above the red plaid shirt
        float rx = (float) f.rsx / f.rn, ry = (float) f.rsy / f.rn;
        float hat = hatShare(rx, ry);
        if (hat < 0.12f) return 0;
        float s = f.rn * (0.5f + hat * 3);
        // barrels: red with a big yellow mark, no dark hat
        if (f.yellow + f.orange > f.rn * 0.5f) s *= 0.2f;
        if (playerX >= 0) {
            float d = (float) Math.hypot(f.cx() - playerX, f.cy() - playerY);
            if (d < tile * 2.5f) s *= 3; else if (d > tile * 6) s *= 0.5f;
        }
        return s;
    }

    private void track(List<Feat> blobs, Observation o, float dt) {
        float tile = 19 * unit;
        if (o.sceneCut) { tracks.clear(); playerId = -1; camAccX = camAccY = 0; }
        else { camAccX += o.camDx; camAccY += o.camDy; }

        // 1. the player (found before objects were read)
        if (pfx >= 0) {
            float x = pfx, y = pfy;
            Observation.Thing p = new Observation.Thing();
            p.kind = Observation.Kind.PLAYER;
            p.id = 0;
            if (playerX >= 0 && !o.sceneCut) {
                float vx = (x - playerX - o.camDx) / dt, vy = (y - playerY - o.camDy) / dt;
                Track pt = find(playerId);
                if (pt != null) { p.vx = pt.vx * 0.5f + vx * 0.5f; p.vy = pt.vy * 0.5f + vy * 0.5f; }
                else { p.vx = vx; p.vy = vy; }
            }
            p.x = x; p.y = y;
            p.w = pbx1 - pbx0; p.h = pby1 - pby0;
            p.r = tile * 0.45f;
            o.player = p;
            playerX = x; playerY = y;
            Track pt = find(playerId);
            if (pt == null) { pt = new Track(); pt.id = playerId = nextId++; pt.born = o.timeMs; tracks.add(pt); }
            pt.x = x; pt.y = y; pt.vx = p.vx; pt.vy = p.vy; pt.seen = o.timeMs; pt.kind = Observation.Kind.PLAYER; pt.matched = true;
        }

        // 2. match the rest to existing tracks
        for (Track t : tracks) if (t.id != playerId) t.matched = false;
        for (Feat f : blobs) {
            float cx = f.cx(), cy = f.cy();
            Track bestT = null; float bestD = Float.MAX_VALUE;
            for (Track t : tracks) {
                if (t.matched || t.id == playerId) continue;
                float ex = t.x + t.vx * dt + o.camDx, ey = t.y + t.vy * dt + o.camDy;
                float gate = Math.max(tile * 1.2f, Math.max(t.w, t.h)) + (float) Math.hypot(t.vx, t.vy) * dt;
                float d = (float) Math.hypot(cx - ex, cy - ey);
                // size must roughly agree
                float sz = Math.max(f.bwid(), f.bhei()), tsz = Math.max(t.w, t.h);
                if (sz > tsz * 2.5f + tile * 0.5f || tsz > sz * 2.5f + tile * 0.5f) continue;
                if (d < gate && d < bestD) { bestD = d; bestT = t; }
            }
            if (bestT == null) {
                bestT = new Track();
                bestT.id = nextId++;
                bestT.born = o.timeMs;
                bestT.x = cx - o.camDx; bestT.y = cy - o.camDy;
                bestT.wx0 = cx - camAccX; bestT.wy0 = cy - camAccY;
                bestT.bornNearPlayer = o.player != null && Math.hypot(cx - o.player.x, cy - o.player.y) < tile * 2.2f;
                tracks.add(bestT);
            }
            float rx = cx - (bestT.x + o.camDx), ry = cy - (bestT.y + o.camDy);
            if (bestT.frames > 0) {
                float a = 0.55f;
                bestT.vx = bestT.vx * (1 - a) + (rx / dt) * a;
                bestT.vy = bestT.vy * (1 - a) + (ry / dt) * a;
                float step = (float) Math.hypot(rx, ry);
                if (step > 19 * unit * 0.12f && step < 19 * unit * 2.5f && Math.abs(o.camDx) < 9 && Math.abs(o.camDy) < 9) { bestT.movingFrames++; bestT.lastMoveT = o.timeMs; }
                bestT.moved = Math.max(bestT.moved, (float) Math.hypot(cx - camAccX - bestT.wx0, cy - camAccY - bestT.wy0));
            }
            bestT.x = cx; bestT.y = cy; bestT.w = f.bwid(); bestT.h = f.bhei(); bestT.area = f.n;
            bestT.seen = o.timeMs; bestT.frames++; bestT.matched = true; bestT.f = f;
            classify(bestT, o, tile);
        }
        // forget tracks not seen for a while (furniture may be hidden behind an enemy for a moment)
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track t = tracks.get(i);
            if (t.matched) continue;
            long gone = o.timeMs - t.seen;
            boolean fast = t.kind == Observation.Kind.ENEMY_SHOT || t.kind == Observation.Kind.OWN_SHOT;
            if (gone > (fast ? 120 : t.kind == Observation.Kind.ENEMY || t.kind == Observation.Kind.BOSS ? 700 : 400)) tracks.remove(i);
        }

        // 3. emit
        float medEnemy = 0; int ne = 0;
        for (Track t : tracks) if (t.matched && t.kind == Observation.Kind.ENEMY) { medEnemy += t.area; ne++; }
        medEnemy = ne == 0 ? 0 : medEnemy / ne;
        for (Track t : tracks) {
            if (!t.matched || t.id == playerId) continue;
            Observation.Thing th = new Observation.Thing();
            th.id = t.id; th.x = t.x; th.y = t.y; th.vx = t.vx; th.vy = t.vy; th.w = t.w; th.h = t.h;
            th.r = Math.max(t.w, t.h) * 0.45f; th.sig = t.sig; th.ageFrames = t.frames; th.seenMoving = t.moved > tile * 0.8f;
            th.kind = t.kind;
            if (t.kind == Observation.Kind.ENEMY && t.area > Math.max(tile * tile * 9, medEnemy * 4) && ne >= 1) th.kind = Observation.Kind.BOSS;
            switch (th.kind) {
                case ENEMY: case BOSS: o.enemies.add(th); break;
                case ENEMY_SHOT: o.shots.add(th); break;
                case OWN_SHOT: o.ownShots.add(th); break;
                case PICKUP: o.pickups.add(th); break;
                case PROP: o.props.add(th); break;
                default: break;
            }
        }
    }

    private Track find(int id) {
        for (Track t : tracks) if (t.id == id) return t;
        return null;
    }

    private void classify(Track t, Observation o, float tile) {
        Feat f = t.f;
        float sz = Math.max(f.bwid(), f.bhei());
        float speed = (float) Math.hypot(t.vx, t.vy);
        boolean small = sz < tile * 1.3f;
        int bright = f.magenta + f.pink + f.white;
        boolean moving = t.moved > tile * 0.8f && t.movingFrames >= 3 && o.timeMs - t.lastMoveT < 4000;
        boolean fastNow = speed > tile * 3.5f && t.frames >= 2;

        // projectiles: small, bright, fast
        if (small && fastNow && bright + f.orange + f.cyan > f.n * 0.25f) {
            if (t.ownShot || (t.bornNearPlayer && f.magenta < f.n * 0.25f)) { t.ownShot = true; t.kind = Observation.Kind.OWN_SHOT; return; }
            if (o.player != null && t.frames <= 3) {
                // moving away from the player right after appearing next to them = our own note
                float ax = t.x - o.player.x, ay = t.y - o.player.y;
                if (t.bornNearPlayer && ax * t.vx + ay * t.vy > 0) { t.ownShot = true; t.kind = Observation.Kind.OWN_SHOT; return; }
            }
            t.kind = Observation.Kind.ENEMY_SHOT;
            return;
        }
        if (t.kind == Observation.Kind.ENEMY_SHOT && small && speed > tile * 1.5f) return;   // keep while slowing in a frame
        if (t.ownShot) { t.kind = Observation.Kind.OWN_SHOT; return; }

        // coins and keys: small, mostly yellow, still
        if (small && f.yellow + f.orange > f.n * 0.35f && !moving) { t.kind = Observation.Kind.PICKUP; t.sig = "coin"; return; }

        // enemy looks: a glowing orange eye on black (turret), glowing blue/cyan faces, red eyes on dark bodies
        boolean orangeEye = f.orange > 3 && f.orange < f.n * 0.25f && f.dark > f.n * 0.35f && f.yellow < 4;
        boolean glow = f.cyan + f.blue > f.n * 0.15f && f.dark > f.n * 0.15f && f.white < f.n * 0.1f;
        t.enemyLook = (orangeEye || glow) && t.frames > 3;
        boolean bigEnough = sz > tile * 0.55f;
        boolean thin = f.bwid() > f.bhei() * 3 || f.bhei() > f.bwid() * 3
                || (sz > tile * 1.6f && f.n < f.bwid() * f.bhei() * 0.22f);
        if (thin) { t.kind = Observation.Kind.PROP; return; }
        if (bigEnough && (moving || t.enemyLook)) {
            t.kind = Observation.Kind.ENEMY;
            if (t.sig.isEmpty() || t.frames % 15 == 0) t.sig = signature(f, tile);
            return;
        }
        // an enemy keeps its label while it pauses (casting, aiming) but not forever
        if (t.kind == Observation.Kind.ENEMY && o.timeMs - t.lastMoveT < 4000) return;
        t.kind = small && speed > tile * 2 ? Observation.Kind.FX : Observation.Kind.PROP;
    }

    static String signature(Feat f, float tile) {
        String[] names = {"dark", "red", "orange", "yellow", "magenta", "white", "pink", "blue", "cyan", "purple", "green"};
        int[] v = {f.dark, f.red, f.orange, f.yellow, f.magenta, f.white, f.pink, f.blue, f.cyan, f.purple, f.green};
        int a = -1, b = -1;
        for (int i = 0; i < v.length; i++) {
            if (a < 0 || v[i] > v[a]) { b = a; a = i; } else if (b < 0 || v[i] > v[b]) b = i;
        }
        float sz = Math.max(f.bwid(), f.bhei()) / tile;
        String s = sz < 1.1f ? "S" : sz < 2.2f ? "M" : "L";
        String second = b >= 0 && v[b] > f.n * 0.08f ? "+" + names[b] : "";
        return names[a] + second + " " + s;
    }

    // ------------------------------------------------------------------------ walk grid

    private void buildWalk(Observation o) {
        boolean[] walk = new boolean[gw * gh];
        for (int i = 0; i < gw * gh; i++) walk[i] = interior[i] && !bigHole[i] && (cellType[i] == 1 || roomLab[i] == roomId);
        // cells covered by moving things are floor underneath; cells covered by furniture are blocked
        List<Observation.Thing> mobile = new ArrayList<>(o.enemies);
        if (o.player != null) mobile.add(o.player);
        mobile.addAll(o.shots); mobile.addAll(o.ownShots); mobile.addAll(o.pickups);
        for (Observation.Thing t : mobile) mark(walk, t, true);
        for (Observation.Thing t : o.props) mark(walk, t, false);
        // enclosed non-floor cells with nothing tracked on them are furniture or wall bits: blocked
        o.walk = walk; o.gw = gw; o.gh = gh; o.cell = cell;
    }

    private void mark(boolean[] walk, Observation.Thing t, boolean v) {
        int x0 = (int) ((t.x - t.w / 2) / cell), x1 = (int) ((t.x + t.w / 2) / cell);
        int y0 = (int) ((t.y - t.h / 2) / cell), y1 = (int) ((t.y + t.h / 2) / cell);
        if (!v) { y0 = (int) ((t.y) / cell); }   // furniture blocks its lower half (the top part is drawn "behind")
        for (int y = Math.max(0, y0); y <= Math.min(gh - 1, y1); y++)
            for (int x = Math.max(0, x0); x <= Math.min(gw - 1, x1); x++)
                if (interior[y * gw + x]) walk[y * gw + x] = v;
    }
}
