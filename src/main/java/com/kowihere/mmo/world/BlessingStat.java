package com.kowihere.mmo.world;

import java.util.Locale;

/**
 * Every line a blessing may carry, and nothing else.
 *
 * <p>Deliberately a closed list of things the engine <em>already computes</em>.
 * A blessing naming a statistic this game does not have would look, in the
 * tooltip, exactly like one that works - and would do nothing at all.
 */
public enum BlessingStat {

    STRENGTH("strength", "siły"),
    AGILITY("agility", "zwinności"),
    INTELLECT("intellect", "inteligencji"),
    ATTACK("attack", "ataku"),
    ARMOR("armor", "pancerza"),

    /**
     * Percentage points of dodge, added before the cap. The cap stays: a single
     * bottle must not turn somebody into a character nothing can hit, which is
     * the very case the cap exists for.
     */
    DODGE_POINTS("dodgePoints", "uniku"),
    SECOND_BLOW_POINTS("secondBlowPoints", "drugiego ciosu"),

    /** Flat health on top of what strength gives. */
    MAX_HP("maxHp", "życia"),

    /** Health given back once a round, and only in a fight - as in the original. */
    HEAL_PER_ROUND("healPerRound", "leczenia co rundę");

    private final String key;
    private final String label;

    BlessingStat(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** What content writes. */
    public String key() {
        return key;
    }

    /** What the tooltip says, after the number: "+6 siły". */
    public String label() {
        return label;
    }

    /** @return the statistic, or null when content names one that does not exist */
    public static BlessingStat parse(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim();
        for (BlessingStat stat : values()) {
            if (stat.key.equalsIgnoreCase(cleaned)
                    || stat.name().equalsIgnoreCase(cleaned.replace('-', '_'))) {
                return stat;
            }
        }
        return null;
    }

    public static String known() {
        StringBuilder all = new StringBuilder();
        for (BlessingStat stat : values()) {
            all.append(all.isEmpty() ? "" : ", ").append(stat.key);
        }
        return all.toString().toLowerCase(Locale.ROOT);
    }
}
