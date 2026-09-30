package com.kowihere.mmo.world;

import com.kowihere.mmo.combat.Resistances;
import com.kowihere.mmo.combat.Strikes;

/**
 * An immutable creature definition, shared by every copy of it in the world.
 *
 * <p>It has no speed, no aggression range and no leash. Creatures stand where
 * the map put them and never take a step, so all three described a journey
 * nothing makes any more. What decides whether one sets upon somebody standing
 * beside it is the gap in levels; see
 * {@link com.kowihere.mmo.combat.CombatRules#LEVELS_ABOVE_TO_POUNCE}.
 *
 * @param loot what killing it may leave behind; empty for most things
 */
public record MobDef(
        String id,
        String name,
        MobTier tier,
        int level,
        int hp,
        int attack,
        int armor,
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
                  int respawnSeconds, java.util.List<LootEntry> loot,
                  java.util.List<CoinDrop> coins) {
        this(id, name, tier, level, hp, attack, armor, respawnSeconds, loot, coins,
                null, Resistances.NONE);
    }
}
