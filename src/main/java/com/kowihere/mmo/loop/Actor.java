package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.MobDef;

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

    /** What this actor is. Players and creatures share the loop but little else. */
    enum Kind { PLAYER, MOB }

    final Kind kind;
    /** The creature this is a copy of, or null for a player. */
    final MobDef mob;
    /** Where a creature came from, and where it returns to when it gives up a chase. */
    final int homeX;
    final int homeY;
    /** Ticks this actor takes to cross one tile. */
    final int stepTicks;

    /** Tick from which this creature may next decide what to do. Throttles pathfinding. */
    long nextThinkTick;

    final int id;
    final String name;
    /** Case-folded name: the character's identity, and its primary key. */
    final String nameKey;
    /** Which account this character belongs to. */
    final long accountId;

    /** Set when this actor has moved since it was last handed to persistence. */
    boolean dirty;

    int x;
    int y;
    int fromX;
    int fromY;
    Direction dir = Direction.DOWN;

    final Deque<int[]> path = new ArrayDeque<>();
    long nextStepTick;

    /**
     * Where this actor has been asked to walk, not yet turned into a path.
     * Requests are collected during the drain and resolved once per tick, so a
     * client cannot buy more pathfinding than a tick's worth no matter how fast
     * it clicks. Null when there is nothing pending.
     */
    int[] pendingMove;

    Client client;
    long offlineSinceTick;
    /**
     * Tick from which this actor may speak again. A deadline rather than a
     * timestamp of the last message: "how long ago" would have to be computed
     * against a sentinel, and subtracting one from a growing tick overflows.
     */
    long nextChatTick;

    /** A player. */
    Actor(int id, String name, String nameKey, long accountId, int x, int y, int stepTicks) {
        this(Kind.PLAYER, null, id, name, nameKey, accountId, x, y, stepTicks);
    }

    /**
     * A creature. It has no account and no session: it is derived from map data,
     * so it is never saved and never reaped for being offline.
     */
    Actor(int id, MobDef mob, int x, int y) {
        this(Kind.MOB, mob, id, mob.name(), "mob:" + mob.id() + "#" + id, 0, x, y, mob.stepTicks());
    }

    private Actor(Kind kind, MobDef mob, int id, String name, String nameKey, long accountId,
                  int x, int y, int stepTicks) {
        this.kind = kind;
        this.mob = mob;
        this.homeX = x;
        this.homeY = y;
        this.stepTicks = stepTicks;
        this.id = id;
        this.name = name;
        this.nameKey = nameKey;
        this.accountId = accountId;
        this.x = x;
        this.y = y;
        this.fromX = x;
        this.fromY = y;
    }

    boolean online() {
        return client != null;
    }

    boolean isMob() {
        return kind == Kind.MOB;
    }
}
