package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.CombatRules;
import com.kowihere.mmo.world.MapDef;

import java.util.Collection;

/**
 * What a creature does when nobody is telling it anything, which is now very
 * nearly nothing.
 *
 * <p>Creatures stand where the map put them and never take a step. They used to
 * wander, chase and go home again, and all three are gone: a creature is a
 * fixed part of the map, like the tile it stands on and the tree beside it, and
 * what makes it interesting is where it was placed rather than where it might
 * wander to.
 *
 * <p>One thing is left to decide, and only about whoever is standing right next
 * to it: whether to set upon them. Most creatures do not - see
 * {@link CombatRules#LEVELS_ABOVE_TO_POUNCE}.
 *
 * <p>A separate class but emphatically <em>not</em> a separate thread: this
 * runs inside the owning map's tick, so the single-writer rule the whole loop
 * rests on is untouched.
 */
final class MobBehaviour {

    /**
     * Ticks between looks around. Nothing here searches or walks any more, so
     * this is no longer about cost - it is about a creature that has just been
     * escaped from not pouncing again in the same breath.
     */
    private static final int THINK_INTERVAL_TICKS = 5;

    /**
     * Tiles around the map spawn in which nothing may pick a fight. Not private:
     * content has to be able to be checked against it - a healer outside this
     * ring is a walk that a character with one point of health does not survive.
     */
    static final int SAFE_RADIUS = 4;

    private final MapDef map;
    private final EngageWith engage;

    /** How a creature tells the map to start a fight. */
    interface EngageWith {
        void start(Actor player, Actor creature);
    }

    MobBehaviour(MapDef map, EngageWith engage) {
        this.map = map;
        this.engage = engage;
    }

    /** Lets one creature look at whoever is beside it, if it is due a look. */
    void watch(Actor mob, Collection<Actor> everyone, long tick) {
        if (mob.inFight() || !mob.isAlive()) {
            return; // a fight holds it as firmly as it holds a player
        }
        if (tick < mob.nextThinkTick) {
            return;
        }
        mob.nextThinkTick = tick + THINK_INTERVAL_TICKS;

        Actor prey = someoneStandingBesideIt(mob, everyone);
        if (prey != null) {
            engage.start(prey, mob);
        }
    }

    /**
     * Whoever is on a neighbouring tile and far enough beneath this creature to
     * be worth its while.
     *
     * <p>Neighbouring counts corners too. Movement is four-way, so nobody
     * arrives diagonally - but somebody can certainly be standing there, and to
     * anything looking at them they are just as close.
     */
    private Actor someoneStandingBesideIt(Actor mob, Collection<Actor> everyone) {
        for (Actor other : everyone) {
            // A knocked out character cannot fight back, cannot run and cannot even
            // say no. Picking on one would turn a death into a second death.
            if (!other.isPlayer() || !other.online() || !other.isAlive() || other.inFight()
                    || other.isUnconscious(System.currentTimeMillis())) {
                continue;
            }
            if (distance(other.x, other.y, map.spawnX(), map.spawnY()) <= SAFE_RADIUS) {
                continue; // standing where everybody appears is not a provocation
            }
            if (distance(mob.x, mob.y, other.x, other.y) > 1) {
                continue; // it is not going to come and get you
            }
            if (!CombatRules.pouncesOn(mob.level, other.level)) {
                continue; // nothing picks a fight it might lose
            }
            return other;
        }
        return null;
    }

    /**
     * Chebyshev distance: one tile out in every direction, corners included.
     */
    private static int distance(int x1, int y1, int x2, int y2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
    }
}
