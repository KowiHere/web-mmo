package com.kowihere.mmo.loop;

import com.kowihere.mmo.path.AStar;
import com.kowihere.mmo.world.MapDef;

import java.util.Collection;
import java.util.Deque;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * What a creature does when nobody is telling it anything.
 *
 * <p>A separate class but emphatically <em>not</em> a separate thread: every
 * method here runs inside the owning map's tick, so the single-writer rule that
 * the whole loop rests on is untouched. It lives apart from {@link MapRunner}
 * only because that class is long enough already, and mixing creature decisions
 * into the socket and delta plumbing would make both harder to read.
 *
 * <p>Three states, no scripts: stand, wander, chase. A chase that catches up
 * starts a fight, which then holds the creature as firmly as it holds the
 * player - so nothing here runs while one is in progress.
 */
final class MobBehaviour {

    /**
     * Ticks between decisions. A creature re-deciding every tick would re-run A*
     * ten times a second; at half a second it still turns promptly and costs a
     * tenth as much.
     */
    private static final int THINK_INTERVAL_TICKS = 5;

    /**
     * How far around a map's spawn creatures will not start a fight, in tiles.
     *
     * <p>Without it the tile every character appears on - and returns to after
     * dying - is a place where a wandering creature can attack someone who has
     * not had a chance to take a step, and a death near the spawn becomes a
     * death loop. It costs nothing: a player who wants a fight walks four tiles.
     */
    private static final int SAFE_RADIUS = 4;

    private static final int[] DX = {0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0};

    private final MapDef map;
    private final AStar pathfinder;
    private final Random random;
    private final BiConsumer<Actor, Actor> engage;

    MobBehaviour(MapDef map, AStar pathfinder, Random random, BiConsumer<Actor, Actor> engage) {
        this.map = map;
        this.pathfinder = pathfinder;
        this.random = random;
        this.engage = engage;
    }

    /**
     * Lets one creature decide, if it is due.
     *
     * @param mayPath whether the map still has pathfinding budget this tick.
     *                When it does not, the creature falls back to wandering
     *                rather than standing still - a crowd of chasers should slow
     *                down, not freeze.
     * @return how many path searches this used, so the caller can keep count
     */
    int think(Actor mob, Collection<Actor> everyone, long tick, boolean mayPath) {
        if (mob.inFight() || !mob.isAlive()) {
            return 0; // a fight holds it as firmly as it holds a player
        }
        if (tick < mob.nextThinkTick) {
            return 0;
        }
        mob.nextThinkTick = tick + THINK_INTERVAL_TICKS;

        Actor target = nearestPlayerWithinAggro(mob, everyone);
        if (target != null) {
            return chase(mob, target, mayPath);
        }
        if (distance(mob.x, mob.y, mob.homeX, mob.homeY) > mob.mob.leashRadius()) {
            return goHome(mob, mayPath);
        }
        wander(mob);
        return 0;
    }

    private Actor nearestPlayerWithinAggro(Actor mob, Collection<Actor> everyone) {
        int aggro = mob.mob.aggroRadius();
        int leash = mob.mob.leashRadius();
        Actor best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Actor other : everyone) {
            if (other.isMob() || !other.online() || !other.isAlive() || other.inFight()) {
                continue;
            }
            if (distance(other.x, other.y, map.spawnX(), map.spawnY()) <= SAFE_RADIUS) {
                continue; // standing where everybody appears is not a provocation
            }
            int distance = distance(mob.x, mob.y, other.x, other.y);
            if (distance > aggro || distance >= bestDistance) {
                continue;
            }
            // Refuse a target that would immediately drag it past its leash, or
            // it would set off, turn round and jitter on the spot for ever.
            if (distance(mob.homeX, mob.homeY, other.x, other.y) > leash) {
                continue;
            }
            best = other;
            bestDistance = distance;
        }
        return best;
    }

    private int chase(Actor mob, Actor target, boolean mayPath) {
        if (distance(mob.x, mob.y, target.x, target.y) <= 1) {
            mob.path.clear();
            engage.accept(target, mob); // caught you
            return 0;
        }
        if (!mayPath) {
            wander(mob);
            return 0;
        }
        return walkTowards(mob, target.x, target.y);
    }

    private int goHome(Actor mob, boolean mayPath) {
        if (!mayPath) {
            return 0;
        }
        return walkTowards(mob, mob.homeX, mob.homeY);
    }

    private int walkTowards(Actor mob, int x, int y) {
        Deque<int[]> path = pathfinder.findPath(mob.x, mob.y, x, y);
        mob.path.clear();
        mob.path.addAll(path);
        return 1;
    }

    /** One step to a random free neighbour, and never further than the leash from home. */
    private void wander(Actor mob) {
        if (!mob.path.isEmpty()) {
            return; // already going somewhere
        }
        int first = random.nextInt(DX.length);
        for (int i = 0; i < DX.length; i++) {
            int direction = (first + i) % DX.length;
            int x = mob.x + DX[direction];
            int y = mob.y + DY[direction];
            if (map.walkable(x, y) && distance(x, y, mob.homeX, mob.homeY) <= mob.mob.leashRadius()) {
                mob.path.add(new int[]{x, y});
                return;
            }
        }
    }

    /**
     * Chebyshev distance: a radius of 3 covers a square three tiles out in every
     * direction, including diagonally. Movement is four-way, but "how close does
     * it look" is what an aggro radius is really asking.
     */
    private static int distance(int x1, int y1, int x2, int y2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
    }
}
