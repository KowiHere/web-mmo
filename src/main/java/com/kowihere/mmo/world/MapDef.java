package com.kowihere.mmo.world;

import java.util.BitSet;
import java.util.List;

/**
 * Immutable, shared definition of a single map: its dimensions and which tiles
 * block movement. Loaded once at startup and read concurrently from every map
 * thread, so nothing here may be mutable.
 */
public final class MapDef {

    private final String id;
    private final String name;
    private final int width;
    private final int height;
    private final int tileSize;
    private final int spawnX;
    private final int spawnY;
    private final BitSet blocked;
    private final List<String> collisionRows;

    MapDef(String id, String name, int width, int height, int tileSize,
           int spawnX, int spawnY, BitSet blocked, List<String> collisionRows) {
        this.id = id;
        this.name = name;
        this.width = width;
        this.height = height;
        this.tileSize = tileSize;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.blocked = blocked;
        this.collisionRows = List.copyOf(collisionRows);
    }

    public String id() { return id; }
    public String name() { return name; }
    public int width() { return width; }
    public int height() { return height; }
    public int tileSize() { return tileSize; }
    public int spawnX() { return spawnX; }
    public int spawnY() { return spawnY; }

    /** Collision as one string per row, '#' blocked and '.' free — what the client renders and the loader parsed. */
    public List<String> collisionRows() { return collisionRows; }

    public boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public boolean isBlocked(int x, int y) {
        return blocked.get(y * width + x);
    }

    /** The only question movement code should ask: may an actor stand here? */
    public boolean walkable(int x, int y) {
        return inBounds(x, y) && !isBlocked(x, y);
    }
}
