package com.kowihere.mmo.combat;

/**
 * What a weapon - or a creature's own claws - puts into a blow beyond force.
 *
 * <p>Two things, because one without the other is useless: which element, and
 * how often it takes hold. A sword that sets everything alight every single
 * round is not a fire sword, it is the only sword anybody would ever carry.
 *
 * @param chance percentage points, 1..100, that the blow leaves the element
 *               behind on whoever it hit
 */
public record Strikes(Element element, int chance) {

    public Strikes {
        if (element == null) {
            throw new IllegalArgumentException("Strikes with no element at all");
        }
    }
}
