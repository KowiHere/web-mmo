package com.kowihere.mmo.world;

/**
 * An immutable creature definition, shared by every copy of it in the world.
 *
 * @param stepTicks    ticks to cross one tile; higher is slower
 * @param aggroRadius  how close a player must come before this creature reacts
 * @param leashRadius  how far it will chase from home before giving up
 * @param loot         what killing it may leave behind; empty for most things
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
        int respawnSeconds,
        java.util.List<LootEntry> loot
) {
}
