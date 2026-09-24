package com.kowihere.mmo.world;

import java.util.Locale;

/**
 * Where an item is worn, or {@link #NONE} for one that is only ever carried.
 *
 * <p>Three worn slots, which is enough to prove everything the system has to
 * do: that bonuses add up, that a slot holds one thing at a time, and that a
 * requirement is enforced. A fourth would exercise none of it.
 */
public enum ItemSlot {
    WEAPON, CHEST, TRINKET,

    /**
     * Carried, never put on: a torch burnt to get into a cave, and before long
     * a potion drunk to stand up again. Such a thing grants nothing while it
     * sits in the bag, which is exactly why it needs saying out loud - an item
     * with no bonuses is otherwise indistinguishable from one whose bonuses
     * were misspelled.
     */
    NONE;

    /** Whether this is somewhere on the body rather than just in the bag. */
    public boolean isWorn() {
        return this != NONE;
    }

    /** @return the slot, or null when a client names one that does not exist */
    public static ItemSlot parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
