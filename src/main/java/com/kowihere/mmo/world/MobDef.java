package com.kowihere.mmo.world;

import com.kowihere.mmo.combat.Resistances;
import com.kowihere.mmo.combat.Strikes;

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
        java.util.List<LootEntry> loot,
        java.util.List<CoinDrop> coins,
        /** What its claws carry beyond force, or null for teeth and nothing more. */
        Strikes strikes,
        /** What it shrugs off; never null. A creature of ice minds fire. */
        Resistances resists
) {

    public MobDef {
        resists = resists == null ? Resistances.NONE : resists;
    }

    /** The shape every test that has no opinion about elements uses. */
    public MobDef(String id, String name, MobTier tier, int level, int hp, int attack, int armor,
                  int stepTicks, int aggroRadius, int leashRadius, int respawnSeconds,
                  java.util.List<LootEntry> loot, java.util.List<CoinDrop> coins) {
        this(id, name, tier, level, hp, attack, armor, stepTicks, aggroRadius, leashRadius,
                respawnSeconds, loot, coins, null, Resistances.NONE);
    }
}
