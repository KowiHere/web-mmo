package com.kowihere.mmo.world;

/**
 * Four-way movement, as in the games this engine takes after. Diagonals are
 * deliberately absent: they complicate collision around corners and buy nothing
 * for a tile world rendered from a fixed camera.
 */
public enum Direction {
    DOWN(0, 1),
    LEFT(-1, 0),
    RIGHT(1, 0),
    UP(0, -1);

    public final int dx;
    public final int dy;

    Direction(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public static Direction between(int fromX, int fromY, int toX, int toY) {
        if (toY > fromY) return DOWN;
        if (toY < fromY) return UP;
        if (toX < fromX) return LEFT;
        return RIGHT;
    }
}
