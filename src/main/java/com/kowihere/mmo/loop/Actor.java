package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.Direction;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A player inside a running map. Mutable and completely unsynchronised by
 * design: only the owning map thread ever touches one.
 *
 * <p>{@code x}/{@code y} are authoritative the moment a step begins, not when
 * it visually finishes. The client animates from {@code fromX}/{@code fromY}
 * towards them, so the render always trails the truth by less than one step
 * instead of racing ahead of it.
 */
final class Actor {

    final int id;
    final String name;
    final String token;

    int x;
    int y;
    int fromX;
    int fromY;
    Direction dir = Direction.DOWN;

    final Deque<int[]> path = new ArrayDeque<>();
    long nextStepTick;

    Client client;
    long offlineSinceTick;
    /**
     * Tick from which this actor may speak again. A deadline rather than a
     * timestamp of the last message: "how long ago" would have to be computed
     * against a sentinel, and subtracting one from a growing tick overflows.
     */
    long nextChatTick;

    Actor(int id, String name, String token, int x, int y) {
        this.id = id;
        this.name = name;
        this.token = token;
        this.x = x;
        this.y = y;
        this.fromX = x;
        this.fromY = y;
    }

    boolean online() {
        return client != null;
    }
}
