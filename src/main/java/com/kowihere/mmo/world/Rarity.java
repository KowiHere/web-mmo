package com.kowihere.mmo.world;

import java.util.Locale;

/**
 * How rare a blessing is. Says nothing about what it does - two blessings of
 * the same rarity can be worth quite different things, and that is the point:
 * rarity is a promise about how often you see one, not about how strong it is.
 */
public enum Rarity {

    COMMON("Pospolite"),
    UNCOMMON("Niezwykłe"),
    HEROIC("Heroiczne"),
    LEGENDARY("Legendarne");

    private final String label;

    Rarity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** @return the rarity, or null when content names one that does not exist */
    public static Rarity parse(String raw) {
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
