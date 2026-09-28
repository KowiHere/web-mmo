package com.kowihere.mmo.combat;

import java.util.EnumMap;
import java.util.Map;

/**
 * How much of each element somebody shrugs off, in percentage points.
 *
 * <p>Negative is allowed and means the opposite: a creature of straw at -50 of
 * fire takes half again as much from it. That is where most of the interest
 * lives - a world where resistances only ever help is a world where the right
 * answer is to wear all of them.
 *
 * <p>Immutable, like everything content is read into.
 */
public record Resistances(Map<Element, Integer> points) {

    public static final Resistances NONE = new Resistances(Map.of());

    /**
     * Nobody is ever more than immune, nor more than doubly hurt. Without the
     * floor, two cursed rings could turn one blow into four.
     */
    public static final int MOST = 100;
    public static final int LEAST = -100;

    public Resistances {
        points = Map.copyOf(points);
    }

    public int of(Element element) {
        return points.getOrDefault(element, 0);
    }

    public boolean isNothing() {
        return points.values().stream().allMatch(value -> value == 0);
    }

    /** Two sets of resistances worn at once, added line by line. */
    public Resistances plus(Resistances other) {
        Map<Element, Integer> total = new EnumMap<>(Element.class);
        for (Element element : Element.values()) {
            int sum = of(element) + other.of(element);
            if (sum != 0) {
                total.put(element, sum);
            }
        }
        return new Resistances(total);
    }

    /** What actually applies, once nothing can go past immune or past double. */
    public static int clamp(int points) {
        return Math.max(LEAST, Math.min(MOST, points));
    }
}
