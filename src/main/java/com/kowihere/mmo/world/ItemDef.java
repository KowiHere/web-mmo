package com.kowihere.mmo.world;

import com.kowihere.mmo.combat.Attributes;

/**
 * An immutable item definition, shared by every copy of it in the world.
 *
 * <p>An item <em>is</em> its definition for now: two rusty swords are the same
 * sword. Rolled bonuses and rarities would make each copy its own thing, and
 * that is a milestone with its own questions — what rolls, how wide, and how a
 * player is supposed to compare two of them.
 *
 * @param requiresLevel the level below which this cannot be worn; 1 means anyone
 * @param bonuses       attribute points this grants while worn
 * @param attack        added to the wearer's attack
 * @param armor         added to the wearer's armour, which is otherwise almost nothing
 * @param healing       what drinking it gives back, or null when it is not
 *                      something you drink
 * @param grants        the blessing drinking it lays on, or null. A bottle
 *                      either mends you or blesses you; never both, because
 *                      "drink" would then mean two things at once
 * @param value         what it is worth. A trader sells at this and buys back at
 *                      a fraction of it, so the two prices cannot drift apart:
 *                      there is only one number to edit
 */
public record ItemDef(
        String id,
        String name,
        ItemSlot slot,
        int requiresLevel,
        Attributes bonuses,
        int attack,
        int armor,
        Healing healing,
        String grants,
        int value
) {

    public boolean isDrinkable() {
        return healing != null || grants != null;
    }

    public boolean isBlessing() {
        return grants != null;
    }
}
