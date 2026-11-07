package com.nickrocks.aibot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * What the bot sees in one frame, read from the screen only (the same picture a player sees).
 * Positions are capture pixels; velocities are capture pixels per second, relative to the room
 * (camera movement removed).
 */
public final class Observation {

    public enum Kind { PLAYER, ENEMY, BOSS, ENEMY_SHOT, OWN_SHOT, PICKUP, PROP, FX }

    public static final class Thing {
        public int id;
        public Kind kind;
        public float x, y, vx, vy;        // centre, velocity
        public float w, h;                // bounding box size
        public float r;                   // collision radius
        public String sig = "";           // enemy look, e.g. "dark+blue M" (used to learn per enemy type)
        public int ageFrames;
        public float danger;              // filled by the agent from experience
        public boolean seenMoving;
        public String label() { return kind + "#" + id + (sig.isEmpty() ? "" : " " + sig); }
    }

    public long timeMs;
    public int w, h;
    /** Pixels per "unit": one unit is 1/432 of the shorter screen side; a floor tile is about 19 units. */
    public float unit = 1;

    public boolean hudVisible;
    public boolean paused;            // the game's pause menu is showing
    public boolean hurtFlash;         // red damage vignette at the screen edges
    public boolean sceneCut;          // the whole picture changed (new room, flash)
    public float camDx, camDy;        // camera shift since the previous frame

    public int hp = -1, hpMax = -1;   // in half hearts, -1 = unreadable
    public float ultFill;             // 0..1
    public boolean ultReady;

    public Thing player;
    public final List<Thing> enemies = new ArrayList<>();
    public final List<Thing> shots = new ArrayList<>();      // enemy projectiles only
    public final List<Thing> ownShots = new ArrayList<>();
    public final List<Thing> pickups = new ArrayList<>();
    public final List<Thing> props = new ArrayList<>();

    /** Walkable grid (true = floor), cell size {@link #cell} pixels, origin at the screen's top-left. */
    public boolean[] walk;
    public int gw, gh, cell;
    public int roomX0, roomY0, roomX1, roomY1;   // bounding box of the room's floor

    /** Minimap: current room grid position and the rooms around it. */
    public final MapRead map = new MapRead();

    public static final class MapRead {
        public boolean ok;
        public int curX, curY;                       // grid position of the white (current) room
        public final List<int[]> visited = new ArrayList<>();    // {gx, gy}
        public final List<int[]> unvisited = new ArrayList<>();
        public int total() { return (ok ? 1 : 0) + visited.size() + unvisited.size(); }
    }

    public boolean walkable(float px, float py) {
        if (walk == null) return true;
        int cx = (int) (px / cell), cy = (int) (py / cell);
        if (cx < 0 || cy < 0 || cx >= gw || cy >= gh) return false;
        return walk[cy * gw + cx];
    }

    public int enemyCount() { return enemies.size(); }
}
