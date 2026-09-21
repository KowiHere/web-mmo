package com.kowihere.mmo.combat;

import com.kowihere.mmo.world.MobDef;

import java.util.Random;

/**
 * Every number that decides a fight, in one place.
 *
 * <p>The randomness is injected rather than taken from a static source. Without
 * that, none of this could be tested except by running it a hundred times and
 * hoping - and a combat formula nobody can pin down is a combat formula nobody
 * can balance.
 */
public final class CombatRules {

    // ---- how a character grows -------------------------------------------

    public static final int BASE_HP = 50;
    public static final int HP_PER_LEVEL = 15;
    public static final int BASE_ATTACK = 5;
    public static final int ATTACK_PER_LEVEL = 2;
    public static final int BASE_ARMOR = 1;
    public static final int ARMOR_PER_LEVEL = 1;

    /** Total experience needed to *be* level n, for the curve below. */
    private static final int XP_SCALE = 100;

    // ---- how a blow lands -------------------------------------------------

    /**
     * Softens armour into a percentage rather than a subtraction. Subtracting
     * armour from damage leaves only two outcomes - armour that does nothing, or
     * armour that stops everything - with nothing in between worth tuning.
     */
    private static final int ARMOR_SOFTENING = 20;

    private static final double MIN_SWING = 0.85;
    private static final double MAX_SWING = 1.15;

    /** A blow always lands for something, so no fight can last for ever. */
    private static final int MINIMUM_DAMAGE = 1;

    // ---- getting out of a fight -------------------------------------------

    public static final double FLEE_CHANCE = 0.6;

    /** What being newly dead costs, and for how long. */
    public static final double WEAKENED_MULTIPLIER = 0.5;
    public static final long WEAKENED_SECONDS = 60;

    private final Random random;

    public CombatRules(Random random) {
        this.random = random;
    }

    // ---- derived statistics ------------------------------------------------

    /**
     * Statistics come from the level rather than being stored beside it. There
     * is then no way for the two to drift apart, because there is nothing to
     * drift.
     */
    public static int maxHpForLevel(int level) {
        return BASE_HP + HP_PER_LEVEL * level;
    }

    public static int attackForLevel(int level) {
        return BASE_ATTACK + ATTACK_PER_LEVEL * level;
    }

    public static int armorForLevel(int level) {
        return BASE_ARMOR + ARMOR_PER_LEVEL * level;
    }

    /** Applies the penalty a freshly killed character carries for a while. */
    public static int weakened(int value) {
        return Math.max(1, (int) Math.round(value * WEAKENED_MULTIPLIER));
    }

    // ---- experience --------------------------------------------------------

    /** Total experience at which a character becomes {@code level}. */
    public static long xpForLevel(int level) {
        long steps = Math.max(0, level - 1);
        return (long) XP_SCALE * steps * steps;
    }

    public static int levelForXp(long xp) {
        return 1 + (int) Math.sqrt(Math.max(0, xp) / (double) XP_SCALE);
    }

    public static long xpReward(MobDef mob) {
        return Math.max(1, Math.round(mob.level() * 10.0 * mob.tier().xpMultiplier()));
    }

    // ---- resolving a blow ---------------------------------------------------

    public int damage(int attack, int armor) {
        double reduction = armor / (double) (armor + ARMOR_SOFTENING);
        double swing = MIN_SWING + random.nextDouble() * (MAX_SWING - MIN_SWING);
        return Math.max(MINIMUM_DAMAGE, (int) Math.round(attack * swing * (1 - reduction)));
    }

    public boolean escapes() {
        return random.nextDouble() < FLEE_CHANCE;
    }
}
