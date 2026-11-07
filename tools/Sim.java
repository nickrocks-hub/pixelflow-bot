import com.nickrocks.aibot.core.*;

import java.util.*;

/**
 * A small top-down dungeon game with the same shape as Nick Rocks' roguelike (rooms with doors that
 * open when cleared, a minimap, hearts, a MOVE stick, a SING stick that aims and fires notes, four
 * action buttons and a ULT). It produces the same {@link Observation} the screen reader produces and
 * applies the bot's {@link Action}s, so the whole brain, logger, learner and session can be tested
 * and trained repeatably on a desktop. Screen units match a 480x432 capture (a floor tile = 19 px).
 */
public class Sim {

    public static final int W = 480, H = 432, CELL = 8;
    public static final float TILE = 19f;
    static final float RX0 = 40, RY0 = 80, RX1 = 440, RY1 = 336;   // room floor rectangle

    public static class Config {
        public int floors = 2, roomsPerFloor = 5;
        public boolean obstacles = true, hiddenObstacle = false, phantom = false;
        public int hp = 6;
        public float enemyScale = 1f;            // more/fewer enemies
        public String[] onlyRoom;                // single-room scenario: enemy types in the one room
        public boolean bossOnly;
        public int diveButton = 2;               // which on-screen button really dives
        public boolean shotsOn = true;
        public float noise = 1.0f;               // position jitter (px)
    }

    static class E {
        int id; String type; float x, y, vx, vy, hp, r, cd, t2; boolean boss, phantom;
        String sig() {
            switch (type) {
                case "bat": return "dark+red S";
                case "mage": return "dark+blue M";
                case "turret": return "dark+orange M";
                case "demon": return "red+dark M";
                case "boss": return "dark+purple L";
                default: return "dark M";
            }
        }
    }
    static class Shot { int id; float x, y, vx, vy, r, life; boolean own; float dmg; }
    static class Item { int id; float x, y; }
    static class Room {
        int gx, gy; boolean cleared, visited, stairs, bossRoom, spawned;
        List<float[]> obstacles = new ArrayList<>();    // {x0,y0,x1,y1}
        List<float[]> hidden = new ArrayList<>();
        List<String> enemies = new ArrayList<>();
    }

    final Config cfg;
    final Random rnd;
    int nextId = 1;
    public long tMs;
    // floor
    int floor = 1;
    Map<Long, Room> rooms = new HashMap<>();
    Room cur;
    // player
    float px, py, pvx, pvy;
    public int hp, hpMax;
    float invuln, diveT, diveDx, diveDy, fireCd, ult;
    final float[] btnCd = new float[4];
    List<E> enemies = new ArrayList<>();
    List<Shot> shots = new ArrayList<>();
    List<Item> items = new ArrayList<>();
    boolean stairsOpen;
    boolean sceneCut = true;
    // outcome
    public boolean victory, dead, over;
    float endTimer = -1;
    // truth counters (what really happened), to check the log against
    public int kills, bossKills, damageTaken, buttonPresses, ultPresses, roomsEntered = 1, floorsDone, itemsPicked, hitsLanded;
    public int contactHits, shotHits;
    public final int[] pressesPerButton = new int[4];

    public Sim(long seed, Config cfg) {
        this.cfg = cfg;
        this.rnd = new Random(seed);
        hp = hpMax = cfg.hp;
        buildFloor();
    }

    static long key(int x, int y) { return ((long) x << 32) ^ (y & 0xffffffffL); }

    // ------------------------------------------------------------------------ dungeon

    void buildFloor() {
        rooms.clear();
        enemies.clear(); shots.clear(); items.clear(); stairsOpen = false;
        int n = cfg.onlyRoom != null || cfg.bossOnly ? 1 : cfg.roomsPerFloor + floor - 1;
        List<int[]> cells = new ArrayList<>();
        cells.add(new int[]{0, 0});
        while (cells.size() < n) {
            int[] c = cells.get(rnd.nextInt(cells.size()));
            int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            int[] dd = d[rnd.nextInt(4)];
            int nx = c[0] + dd[0], ny = c[1] + dd[1];
            boolean exists = false;
            for (int[] e : cells) if (e[0] == nx && e[1] == ny) exists = true;
            if (!exists) cells.add(new int[]{nx, ny});
        }
        for (int[] c : cells) {
            Room r = new Room(); r.gx = c[0]; r.gy = c[1];
            rooms.put(key(c[0], c[1]), r);
        }
        // farthest room by BFS = boss or stairs room
        Room far = farthest(rooms.get(key(0, 0)));
        boolean last = floor >= cfg.floors;
        for (Room r : rooms.values()) {
            boolean start = r.gx == 0 && r.gy == 0;
            if (cfg.obstacles && !(cfg.bossOnly)) {
                int k = 1 + rnd.nextInt(3);
                for (int i = 0; i < k; i++) {
                    float w = TILE * (1 + rnd.nextInt(2)), h = TILE * (1 + rnd.nextInt(2));
                    float x = RX0 + TILE * 3 + rnd.nextFloat() * (RX1 - RX0 - TILE * 6 - w);
                    float y = RY0 + TILE * 2.5f + rnd.nextFloat() * (RY1 - RY0 - TILE * 5 - h);
                    if (Math.abs(x + w / 2 - (RX0 + RX1) / 2) < TILE * 2 && Math.abs(y + h / 2 - (RY0 + RY1) / 2) < TILE * 2) continue;
                    r.obstacles.add(new float[]{x, y, x + w, y + h});
                }
            }
            if (cfg.onlyRoom != null) { r.enemies.addAll(Arrays.asList(cfg.onlyRoom)); continue; }
            if (cfg.bossOnly) { r.enemies.add("boss"); r.bossRoom = true; continue; }
            if (start) { r.cleared = true; continue; }
            if (r == far) {
                if (last) { r.bossRoom = true; r.enemies.add("boss"); }
                else { r.stairs = true; addMix(r, 3 + floor); }
            } else addMix(r, (int) Math.ceil((2 + rnd.nextInt(3) + floor - 1) * cfg.enemyScale));
        }
        if (cfg.hiddenObstacle) {
            Room s = rooms.get(key(0, 0));
            // invisible blocks right in front of each door of the first room (the reader can't see them)
            float cx = (RX0 + RX1) / 2, cy = (RY0 + RY1) / 2;
            if (hasDoor(s, 1, 0)) s.hidden.add(new float[]{RX1 - TILE * 2.6f, cy - TILE * 3.2f, RX1 - TILE * 1.6f, cy + TILE * 3.2f});
            if (hasDoor(s, -1, 0)) s.hidden.add(new float[]{RX0 + TILE * 1.6f, cy - TILE * 3.2f, RX0 + TILE * 2.6f, cy + TILE * 3.2f});
            if (hasDoor(s, 0, 1)) s.hidden.add(new float[]{cx - TILE * 4f, RY1 - TILE * 2.6f, cx + TILE * 4f, RY1 - TILE * 1.6f});
            if (hasDoor(s, 0, -1)) s.hidden.add(new float[]{cx - TILE * 4f, RY0 + TILE * 1.6f, cx + TILE * 4f, RY0 + TILE * 2.6f});
        }
        cur = rooms.get(key(0, 0));
        cur.visited = true;
        px = (RX0 + RX1) / 2; py = (RY0 + RY1) / 2;
        enterRoom(cur);
        sceneCut = true;
    }

    void addMix(Room r, int k) {
        String[] types = {"bat", "bat", "mage", "turret", "demon"};
        for (int i = 0; i < k; i++) r.enemies.add(types[rnd.nextInt(types.length)]);
    }

    Room farthest(Room s) {
        Map<Room, Integer> d = new HashMap<>();
        ArrayDeque<Room> q = new ArrayDeque<>();
        d.put(s, 0); q.add(s);
        Room far = s;
        while (!q.isEmpty()) {
            Room r = q.poll();
            if (d.get(r) > d.get(far)) far = r;
            for (Room n : neighbours(r)) if (!d.containsKey(n)) { d.put(n, d.get(r) + 1); q.add(n); }
        }
        return far;
    }

    List<Room> neighbours(Room r) {
        List<Room> l = new ArrayList<>();
        int[][] dd = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dd) { Room n = rooms.get(key(r.gx + d[0], r.gy + d[1])); if (n != null) l.add(n); }
        return l;
    }

    boolean hasDoor(Room r, int dx, int dy) { return rooms.containsKey(key(r.gx + dx, r.gy + dy)); }

    void enterRoom(Room r) {
        enemies.clear(); shots.clear(); items.clear();
        if (!r.cleared && !r.spawned) {
            r.spawned = true;
            for (String t : r.enemies) spawn(t);
            if (cfg.phantom && !r.enemies.isEmpty()) {
                // furniture the screen reader mistakes for an enemy: it never moves and can't be hurt
                E f = new E(); f.id = nextId++; f.type = "phantom"; f.phantom = true; f.hp = 1e9f; f.r = TILE * 0.5f;
                f.x = RX0 + TILE * 2; f.y = RY0 + TILE * 1.5f;
                enemies.add(f);
            }
        }
        stairsOpen = r.stairs && r.cleared;
    }

    void spawn(String t) {
        E e = new E(); e.id = nextId++; e.type = t;
        for (int tries = 0; tries < 50; tries++) {
            e.x = RX0 + TILE * 1.5f + rnd.nextFloat() * (RX1 - RX0 - TILE * 3);
            e.y = RY0 + TILE * 1.5f + rnd.nextFloat() * (RY1 - RY0 - TILE * 3);
            if (Math.hypot(e.x - px, e.y - py) > TILE * 5 && !blocked(e.x, e.y, false)) break;
        }
        switch (t) {
            case "bat": e.hp = 3; e.r = TILE * 0.45f; break;
            case "mage": e.hp = 4; e.r = TILE * 0.5f; e.cd = 1.5f + rnd.nextFloat(); break;
            case "turret": e.hp = 6; e.r = TILE * 0.55f; e.cd = 2f + rnd.nextFloat(); break;
            case "demon": e.hp = 5; e.r = TILE * 0.55f; e.cd = 2f; break;
            case "boss": e.hp = 60; e.r = TILE * 1.5f; e.boss = true; e.cd = 2f; e.x = (RX0 + RX1) / 2; e.y = RY0 + TILE * 3; break;
        }
        enemies.add(e);
    }

    boolean blocked(float x, float y, boolean includeHidden) {
        if (x < RX0 + 4 || x > RX1 - 4 || y < RY0 + 4 || y > RY1 - 4) {
            // doorways stick out of the rectangle
            return !inDoorway(x, y);
        }
        for (float[] o : cur.obstacles) if (x > o[0] && x < o[2] && y > o[1] && y < o[3]) return true;
        if (includeHidden) for (float[] o : cur.hidden) if (x > o[0] && x < o[2] && y > o[1] && y < o[3]) return true;
        return false;
    }

    boolean inDoorway(float x, float y) {
        float cx = (RX0 + RX1) / 2, cy = (RY0 + RY1) / 2, half = TILE * 1.2f;
        boolean open = cur.cleared;
        if (!open) return false;
        if (hasDoor(cur, 1, 0) && x >= RX1 - 4 && x < RX1 + TILE * 2 && Math.abs(y - cy) < half) return true;
        if (hasDoor(cur, -1, 0) && x <= RX0 + 4 && x > RX0 - TILE * 2 && Math.abs(y - cy) < half) return true;
        if (hasDoor(cur, 0, 1) && y >= RY1 - 4 && y < RY1 + TILE * 2 && Math.abs(x - cx) < half) return true;
        if (hasDoor(cur, 0, -1) && y <= RY0 + 4 && y > RY0 - TILE * 2 && Math.abs(x - cx) < half) return true;
        return false;
    }

    // ------------------------------------------------------------------------ observation

    public Observation observe() {
        Observation o = new Observation();
        o.timeMs = tMs; o.w = W; o.h = H; o.unit = 1;
        o.sceneCut = sceneCut;
        sceneCut = false;
        boolean showHud = !(over && endTimer <= 0) && !(victory && endTimer < 0.0f);
        o.hudVisible = !over || endTimer > 0;
        if (!o.hudVisible) return o;
        o.hp = hp; o.hpMax = hpMax;
        o.ultFill = Math.min(1, ult); o.ultReady = ult >= 1;
        if (dead) return o;    // hearts empty, screen about to change
        Observation.Thing p = new Observation.Thing();
        p.kind = Observation.Kind.PLAYER; p.x = px + jit(); p.y = py + jit(); p.vx = pvx; p.vy = pvy; p.w = 32; p.h = 50; p.r = TILE * 0.45f;
        o.player = p;
        for (E e : enemies) {
            Observation.Thing t = new Observation.Thing();
            t.id = e.id; t.kind = e.boss ? Observation.Kind.BOSS : Observation.Kind.ENEMY;
            t.x = e.x + jit(); t.y = e.y + jit(); t.vx = e.vx; t.vy = e.vy; t.w = e.r * 2; t.h = e.r * 2; t.r = e.r;
            t.sig = e.phantom ? "dark M" : e.sig(); t.ageFrames = 10; t.seenMoving = !e.type.equals("turret") && !e.phantom;
            o.enemies.add(t);
        }
        for (Shot s : shots) {
            Observation.Thing t = new Observation.Thing();
            t.id = s.id; t.kind = s.own ? Observation.Kind.OWN_SHOT : Observation.Kind.ENEMY_SHOT;
            t.x = s.x; t.y = s.y; t.vx = s.vx; t.vy = s.vy; t.w = s.r * 2; t.h = s.r * 2; t.r = s.r; t.ageFrames = 5;
            (s.own ? o.ownShots : o.shots).add(t);
        }
        for (Item it : items) {
            Observation.Thing t = new Observation.Thing();
            t.id = it.id; t.kind = Observation.Kind.PICKUP; t.x = it.x; t.y = it.y; t.w = 10; t.h = 10; t.r = 5; t.sig = "coin";
            o.pickups.add(t);
        }
        // walk grid from the visible room (hidden obstacles are, by definition, not in it)
        o.cell = CELL; o.gw = W / CELL; o.gh = H / CELL;
        o.walk = new boolean[o.gw * o.gh];
        for (int y = 0; y < o.gh; y++) for (int x = 0; x < o.gw; x++) {
            float cx = (x + 0.5f) * CELL, cy = (y + 0.5f) * CELL;
            o.walk[y * o.gw + x] = !blocked(cx, cy, false) || (inDoorwayAny(cx, cy));
        }
        o.roomX0 = (int) RX0; o.roomY0 = (int) RY0; o.roomX1 = (int) RX1; o.roomY1 = (int) RY1;
        // minimap: current room centred at (3,3); seen = visited + their neighbours
        o.map.ok = true; o.map.curX = 3; o.map.curY = 3;
        Set<Room> seenRooms = new HashSet<>();
        for (Room r : rooms.values()) if (r.visited) { seenRooms.add(r); seenRooms.addAll(neighbours(r)); }
        for (Room r : seenRooms) {
            if (r == cur) continue;
            int[] c = {3 + r.gx - cur.gx, 3 + r.gy - cur.gy};
            (r.visited ? o.map.visited : o.map.unvisited).add(c);
        }
        return o;
    }

    boolean inDoorwayAny(float x, float y) {
        // doorway shape is visible even when closed (the reader sees the arch), but closed ones are blocked
        float cx = (RX0 + RX1) / 2, cy = (RY0 + RY1) / 2, half = TILE * 1.2f;
        if (!cur.cleared) return false;
        if (hasDoor(cur, 1, 0) && x >= RX1 - 4 && x < RX1 + TILE * 2 && Math.abs(y - cy) < half) return true;
        if (hasDoor(cur, -1, 0) && x <= RX0 + 4 && x > RX0 - TILE * 2 && Math.abs(y - cy) < half) return true;
        if (hasDoor(cur, 0, 1) && y >= RY1 - 4 && y < RY1 + TILE * 2 && Math.abs(x - cx) < half) return true;
        if (hasDoor(cur, 0, -1) && y <= RY0 + 4 && y > RY0 - TILE * 2 && Math.abs(x - cx) < half) return true;
        return false;
    }

    float jit() { return cfg.noise == 0 ? 0 : (float) (rnd.nextGaussian() * cfg.noise); }

    // ------------------------------------------------------------------------ physics

    public void apply(Action a, float dt) {
        if (over) {
            endTimer -= dt;
            tMs += Math.round(dt * 1000);
            return;
        }
        tMs += Math.round(dt * 1000);
        // buttons
        if (a.button >= 0 && a.button < 4) { buttonPresses++; pressesPerButton[a.button]++; }   // every tap reaches the game
        if (a.ult) ultPresses++;
        if (a.button >= 0 && a.button < 4 && btnCd[a.button] <= 0) {
            int b = a.button;
            if (b == cfg.diveButton) {
                float l = (float) Math.hypot(a.moveX, a.moveY);
                diveDx = l > 0.1f ? a.moveX / l : 1; diveDy = l > 0.1f ? a.moveY / l : 0;
                diveT = 0.25f; invuln = Math.max(invuln, 0.3f); btnCd[b] = 1.2f;
            } else {
                String role = b == 1 ? "SMASH" : b == 3 ? "SCREAM" : "DROP";
                float rad = role.equals("SMASH") ? TILE * 2 : role.equals("DROP") ? TILE * 4 : TILE * 6;
                float dmg = role.equals("SMASH") ? 3 : 2;
                for (E e : enemies) if (Math.hypot(e.x - px, e.y - py) < rad + e.r) hurt(e, dmg);
                btnCd[b] = 3f;
            }
        }
        if (a.ult && ult >= 1) {
            ult = 0;
            for (E e : enemies) if (Math.hypot(e.x - px, e.y - py) < TILE * 7) hurt(e, 10);
        }
        for (int i = 0; i < 4; i++) btnCd[i] -= dt;

        // movement (sub-steps for walls)
        float speed = 5.5f * TILE;
        float mx = a.moveX, my = a.moveY;
        float ml = (float) Math.hypot(mx, my);
        if (ml > 1) { mx /= ml; my /= ml; }
        float vx = mx * speed, vy = my * speed;
        if (diveT > 0) { vx = diveDx * TILE * 14; vy = diveDy * TILE * 14; diveT -= dt; }
        float ox = px, oy = py;
        int sub = 4;
        for (int i = 0; i < sub; i++) {
            float nx = px + vx * dt / sub, ny = py + vy * dt / sub;
            if (!blocked(nx, py + 6, true)) px = nx;
            if (!blocked(px, ny + 6, true)) py = ny;
        }
        pvx = (px - ox) / dt; pvy = (py - oy) / dt;
        invuln -= dt;

        // room transitions
        float cx = (RX0 + RX1) / 2, cy = (RY0 + RY1) / 2;
        int[] go = null;
        if (px > RX1 + TILE * 0.8f) go = new int[]{1, 0};
        else if (px < RX0 - TILE * 0.8f) go = new int[]{-1, 0};
        else if (py > RY1 + TILE * 0.8f) go = new int[]{0, 1};
        else if (py < RY0 - TILE * 0.8f) go = new int[]{0, -1};
        if (go != null) {
            Room n = rooms.get(key(cur.gx + go[0], cur.gy + go[1]));
            if (n != null) {
                cur = n;
                if (!n.visited) roomsEntered++;
                n.visited = true;
                px = go[0] > 0 ? RX0 + TILE * 1.2f : go[0] < 0 ? RX1 - TILE * 1.2f : cx;
                py = go[1] > 0 ? RY0 + TILE * 1.2f : go[1] < 0 ? RY1 - TILE * 1.2f : cy;
                enterRoom(n);
                sceneCut = true;
                return;
            }
        }
        // stairs
        if (stairsOpen && Math.hypot(px - cx, py - cy) < TILE * 0.9f) {
            floorsDone++;
            floor++;
            buildFloor();
            return;
        }

        // singing: notes along the aim stick
        fireCd -= dt;
        if (a.sing && fireCd <= 0 && (a.aimX != 0 || a.aimY != 0)) {
            fireCd = 1 / 2.94f;
            float l = (float) Math.hypot(a.aimX, a.aimY);
            Shot s = new Shot(); s.id = nextId++; s.own = true; s.x = px; s.y = py - 6; s.r = 4; s.dmg = 1; s.life = 280f / (12 * TILE) * 2;
            s.vx = a.aimX / l * 12 * TILE; s.vy = a.aimY / l * 12 * TILE;
            shots.add(s);
        }

        // enemies
        for (E e : enemies) {
            if (e.phantom) continue;
            float dx = px - e.x, dy = py - e.y, d = (float) Math.max(1, Math.hypot(dx, dy));
            e.cd -= dt;
            switch (e.type) {
                case "bat": {
                    e.t2 += dt;
                    float wob = (float) Math.sin(e.t2 * 5) * 0.6f;
                    e.vx = (dx / d - dy / d * wob) * 3.2f * TILE; e.vy = (dy / d + dx / d * wob) * 3.2f * TILE;
                    break;
                }
                case "mage": {
                    float want = TILE * 5;
                    float s = d > want + TILE ? 1 : d < want - TILE ? -1 : 0;
                    e.vx = dx / d * s * 2 * TILE; e.vy = dy / d * s * 2 * TILE;
                    if (e.cd <= 0 && cfg.shotsOn) { e.cd = 2.2f; fire(e, dx / d, dy / d, 4.5f); }
                    break;
                }
                case "turret":
                    e.vx = e.vy = 0;
                    if (e.cd <= 0 && cfg.shotsOn) {
                        e.cd = 2.8f;
                        for (int k = -1; k <= 1; k++) {
                            double ang = Math.atan2(dy, dx) + k * 0.35;
                            fire(e, (float) Math.cos(ang), (float) Math.sin(ang), 4f);
                        }
                    }
                    break;
                case "demon":
                    if (e.cd <= 0) { e.cd = 3f; e.t2 = 0.6f; e.vx = dx / d * 7 * TILE; e.vy = dy / d * 7 * TILE; }
                    if (e.t2 > 0) e.t2 -= dt; else { e.vx *= 0.9f; e.vy *= 0.9f; }
                    break;
                case "boss":
                    e.vx = dx / d * 1.5f * TILE; e.vy = dy / d * 1.5f * TILE;
                    if (e.cd <= 0 && cfg.shotsOn) {
                        e.cd = 3f;
                        for (int k = 0; k < 12; k++) { double ang = k * Math.PI / 6; fire(e, (float) Math.cos(ang), (float) Math.sin(ang), 3.5f); }
                    }
                    e.t2 -= dt;
                    if (e.t2 <= 0 && cfg.shotsOn) {
                        e.t2 = 1.5f;
                        for (int k = -1; k <= 1; k++) { double ang = Math.atan2(dy, dx) + k * 0.25; fire(e, (float) Math.cos(ang), (float) Math.sin(ang), 4.5f); }
                    }
                    break;
            }
            float nx = e.x + e.vx * dt, ny = e.y + e.vy * dt;
            if (!blocked(nx, e.y, false)) e.x = nx;
            if (!blocked(e.x, ny, false)) e.y = ny;
            // contact
            if (d < e.r + TILE * 0.45f && invuln <= 0) { damage(e.boss ? 2 : 1); contactHits++; }
        }

        // shots
        for (Iterator<Shot> it = shots.iterator(); it.hasNext(); ) {
            Shot s = it.next();
            s.x += s.vx * dt; s.y += s.vy * dt; s.life -= dt;
            if (s.life <= 0 || blocked(s.x, s.y, false)) { it.remove(); continue; }
            if (s.own) {
                for (E e : enemies) if (!e.phantom && Math.hypot(e.x - s.x, e.y - s.y) < e.r + s.r) { hurt(e, s.dmg); hitsLanded++; ult += 0.04f; it.remove(); break; }
            } else if (Math.hypot(px - s.x, py - s.y) < TILE * 0.45f + s.r && invuln <= 0) {
                damage(1); shotHits++; it.remove();
            }
        }
        // deaths
        for (Iterator<E> it = enemies.iterator(); it.hasNext(); ) {
            E e = it.next();
            if (e.hp > 0) continue;
            it.remove();
            kills++;
            if (e.boss) bossKills++;
            if (rnd.nextFloat() < 0.4f) { Item i = new Item(); i.id = nextId++; i.x = e.x; i.y = e.y; items.add(i); }
        }
        boolean anyReal = false;
        for (E e : enemies) if (!e.phantom) anyReal = true;
        if (!cur.cleared && !anyReal) {
            cur.cleared = true;
            enemies.clear();   // the phantom "enemy" was furniture; it stays on screen only as long as enemies do
            if (cur.stairs) stairsOpen = true;
            if (cur.bossRoom && floor >= cfg.floors) { victory = true; over = true; endTimer = 1.0f; }
            else if (cur.bossRoom && cfg.bossOnly) { victory = true; over = true; endTimer = 1.0f; }
        }
        // items
        for (Iterator<Item> it = items.iterator(); it.hasNext(); ) {
            Item i = it.next();
            if (Math.hypot(i.x - px, i.y - py) < TILE * 0.8f) { it.remove(); itemsPicked++; }
        }
    }

    void fire(E e, float ux, float uy, float tilesPerS) {
        Shot s = new Shot(); s.id = nextId++; s.x = e.x; s.y = e.y; s.r = 6; s.vx = ux * tilesPerS * TILE; s.vy = uy * tilesPerS * TILE; s.life = 4;
        shots.add(s);
    }

    void hurt(E e, float d) { if (!e.phantom) e.hp -= d; }

    void damage(int d) {
        if (dead) return;
        hp = Math.max(0, hp - d);
        damageTaken += d;
        invuln = 0.8f;
        if (hp == 0) { dead = true; over = true; endTimer = 1.0f; }
    }
}
