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

    /**
     * What a character wakes up with.
     *
     * <p>One, not a full bar: nothing in this game regenerates, so a death that
     * healed you was the only cure in it - and the best thing a hurt character
     * could do was find a wolf and lose to it. One rather than zero because the
     * loop reads no health as dead, and a corpse that is standing up is a class
     * of bug nobody needs.
     */
    public static final int HEALTH_AFTER_DEATH = 1;

    private final Random random;

    public CombatRules(Random random) {
        this.random = random;
    }

    // ---- derived statistics ------------------------------------------------

    /**
     * Where a character's numbers come from now lives in {@link Attributes}.
     * Nothing derived is stored, which was the whole point of deriving it from
     * the level in the first place.
     */

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
        return damage(attack, armor, 0);
    }

    /**
     * @param armorIgnored the fraction of the target's armour these blows pass
     *                     through. It is what lets the frailest class be worth
     *                     playing: against something in heavy armour it hits
     *                     for what the armour does not stop.
     */
    public int damage(int attack, int armor, double armorIgnored) {
        double effective = armor * (1 - Math.max(0, Math.min(1, armorIgnored)));
        double reduction = effective / (effective + ARMOR_SOFTENING);
        double swing = MIN_SWING + random.nextDouble() * (MAX_SWING - MIN_SWING);
        return Math.max(MINIMUM_DAMAGE, (int) Math.round(attack * swing * (1 - reduction)));
    }

    public boolean escapes() {
        return random.nextDouble() < FLEE_CHANCE;
    }

    /** Whether a blow misses entirely. Agility's first payoff. */
    public boolean dodges(double dodgeChance) {
        return random.nextDouble() < dodgeChance;
    }

    /** Whether this swing is followed by a second one in the same round. */
    public boolean landsSecondBlow(double chance) {
        return random.nextDouble() < chance;
    }

    /** One roll against a stated chance, for anything that is simply luck. */
    public boolean rolls(double chance) {
        return random.nextDouble() < chance;
    }
}
