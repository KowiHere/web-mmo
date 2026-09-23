package com.kowihere.mmo.world;

import java.util.Locale;

/**
 * Where an item is worn.
 *
 * <p>Three for now, which is enough to prove everything the system has to do:
 * that bonuses add up, that a slot holds one thing at a time, and that a
 * requirement is enforced. A fourth would exercise none of it.
 */
public enum ItemSlot {
    WEAPON, CHEST, TRINKET;

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
