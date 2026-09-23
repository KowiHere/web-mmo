package com.kowihere.mmo.combat;

/**
 * The three numbers a character is actually made of, and everything that falls
 * out of them.
 *
 * <p>Statistics used to come straight from the level. The rule behind that was
 * never "the level decides"; it was <em>store nothing that can be computed</em>,
 * so that nothing can drift out of agreement with anything else. That rule is
 * untouched — only the chain got longer:
 *
 * <pre>
 *   level  →  points to spend  →  attributes + equipment  →  combat statistics
 * </pre>
 *
 * <p>So the database holds attributes and what is worn, and not one of the
 * numbers below.
 *
 * <p>The constants are chosen so that a fresh character has exactly what it had
 * before attributes existed — 65 health and 7 attack. That is not nostalgia: the
 * creatures on the starter map were balanced against those numbers, and moving
 * them would have quietly made the map unwinnable again.
 */
public record Attributes(int strength, int agility, int intellect) {

    /** What a character starts with, before spending a single point. */
    public static final int STARTING = 5;

    /** Points each level is worth. Level one is the starting point, not a level up. */
    public static final int POINTS_PER_LEVEL = 3;

    public static final Attributes FRESH = new Attributes(STARTING, STARTING, STARTING);

    private static final int BASE_HP = 40;
    private static final int HP_PER_STRENGTH = 5;
    private static final int BASE_ATTACK = 2;
    private static final int ATTACK_PER_STRENGTH = 1;
    private static final int BASE_ARMOR = 1;
    private static final int BASE_MANA = 10;
    private static final int MANA_PER_INTELLECT = 5;

    /**
     * Both of agility's payoffs are capped.
     *
     * <p>Uncapped evasion means a character that eventually stops taking damage
     * at all, and a fight that therefore never ends — the same worry that puts a
     * floor of one under every blow, seen from the other side.
     */
    private static final double DODGE_PER_AGILITY = 0.010;
    private static final double MAX_DODGE = 0.35;
    private static final double SECOND_BLOW_PER_AGILITY = 0.012;
    private static final double MAX_SECOND_BLOW = 0.40;

    public Attributes {
        if (strength < 0 || agility < 0 || intellect < 0) {
            throw new IllegalArgumentException(
                    "An attribute cannot be negative: " + strength + "/" + agility + "/" + intellect);
        }
    }

    /** Total points a character of this level has ever had to spend. */
    public static int pointsEarnedBy(int level) {
        return POINTS_PER_LEVEL * Math.max(0, level - 1);
    }

    public int maxHp() {
        return BASE_HP + HP_PER_STRENGTH * strength;
    }

    public int attack() {
        return BASE_ATTACK + ATTACK_PER_STRENGTH * strength;
    }

    /** Armour comes from what you are wearing; the body itself is worth one point. */
    public int armor() {
        return BASE_ARMOR;
    }

    public int maxMana() {
        return BASE_MANA + MANA_PER_INTELLECT * intellect;
    }

    public double dodgeChance() {
        return Math.min(MAX_DODGE, DODGE_PER_AGILITY * agility);
    }

    public double secondBlowChance() {
        return Math.min(MAX_SECOND_BLOW, SECOND_BLOW_PER_AGILITY * agility);
    }

    /** This one with {@code amount} more of {@code which}. */
    public Attributes plus(Attribute which, int amount) {
        return switch (which) {
            case STRENGTH -> new Attributes(strength + amount, agility, intellect);
            case AGILITY -> new Attributes(strength, agility + amount, intellect);
            case INTELLECT -> new Attributes(strength, agility, intellect + amount);
        };
    }

    public Attributes plus(Attributes other) {
        return new Attributes(strength + other.strength, agility + other.agility,
                intellect + other.intellect);
    }

    /** Which one, named the same way on the wire, in the database and in a JSON file. */
    public enum Attribute {
        STRENGTH, AGILITY, INTELLECT;

        public static Attribute parse(String raw) {
            if (raw == null) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null; // a client asking for an attribute that does not exist
            }
        }
    }
}
