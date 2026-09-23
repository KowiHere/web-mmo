package com.kowihere.mmo.world;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.Attributes.Attribute;

/**
 * What kind of character this is.
 *
 * <p>A class is not a bundle of bonuses handed to an otherwise identical
 * character. It decides two things that matter in every fight: <strong>which
 * attribute its blows are made of</strong>, and how much of an opponent's
 * armour those blows care about. Without the first, every class would want
 * strength and the other two attributes would be a tax; without the second, the
 * class with the most health would simply be the best one.
 *
 * <p>Points stay the player's to spend. A class says where a character starts
 * and how it fights, not what it is allowed to become.
 *
 * @param damageFrom      the attribute this class's attack is computed from
 * @param armorIgnored    the fraction of an opponent's armour its blows pass
 *                        through, 0..1 - this is what stops the frailest class
 *                        from simply being the worst one
 * @param hpBonus         flat health, so a class can be sturdy without needing
 *                        strength it would never otherwise want
 */
public record ClassDef(
        String id,
        String name,
        String description,
        Attributes startingAttributes,
        Attribute damageFrom,
        double armorIgnored,
        int hpBonus
) {

    /** What this class's blows are worth, before equipment and weakness. */
    public int attackFrom(Attributes attributes) {
        return Attributes.attackFrom(attributes.of(damageFrom));
    }
}
