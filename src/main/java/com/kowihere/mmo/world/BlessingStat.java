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

    STRENGTH("strength", "siły", true),
    AGILITY("agility", "zwinności", true),
    INTELLECT("intellect", "inteligencji", true),
    ATTACK("attack", "ataku", true),
    ARMOR("armor", "pancerza", true),

    /**
     * Percentage points of dodge, added before the cap. The cap stays: a single
     * bottle must not turn somebody into a character nothing can hit, which is
     * the very case the cap exists for.
     */
    DODGE_POINTS("dodgePoints", "uniku", false),
    SECOND_BLOW_POINTS("secondBlowPoints", "drugiego ciosu", false),

    /** Flat health on top of what strength gives. */
    MAX_HP("maxHp", "życia", true),

    /** Health given back once a round, and only in a fight - as in the original. */
    HEAL_PER_ROUND("healPerRound", "leczenia co rundę", false);

    private final String key;
    private final String label;
    private final boolean share;

    BlessingStat(String key, String label, boolean share) {
        this.key = key;
        this.label = label;
        this.share = share;
    }

    /**
     * Whether a line on this may be written as a percentage.
     *
     * <p>Only statistics that are a quantity the character already has. The
     * three that are not - percentage points of dodge and of a second blow, and
     * health given back each round - are already shares or already rates, and
     * "10% of 5 percentage points" is a sentence with no agreed meaning. A file
     * that tries it is refused rather than rounded to nothing, which is how a
     * line ends up in a tooltip doing nothing at all.
     */
    public boolean takesAShare() {
        return share;
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
