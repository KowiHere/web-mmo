package com.kowihere.mmo.world;

/**
 * An immutable creature definition, shared by every copy of it in the world.
 *
 * <p>The combat numbers are carried but unused: there is no combat yet, so
 * nothing reads hp, attack or armour. They are here because the shape of a
 * creature should not have to change when combat arrives - and because leaving
 * them out would mean inventing them later against code that already exists.
 *
 * @param stepTicks    ticks to cross one tile; higher is slower
 * @param aggroRadius  how close a player must come before this creature reacts
 * @param leashRadius  how far it will chase from home before giving up
 */
public record MobDef(
        String id,
        String name,
        MobTier tier,
        int level,
        int hp,
        int attack,
        int armor,
        int stepTicks,
        int aggroRadius,
        int leashRadius,
        int respawnSeconds
) {
}
