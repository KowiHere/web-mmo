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

    // ---- elements ---------------------------------------------------------

    /**
     * How long each affliction lasts, in rounds, and how hard it bites.
     *
     * <p>Written here rather than in content on purpose. A file that could set
     * its own burn to forty rounds at half the blow is a file that decides the
     * whole balance of the game; what content chooses is <em>which</em> element
     * a weapon carries and how often, which is a decision about that weapon.
     *
     * <p>They differ in shape, not only in size, or there would be no reason
     * for five of them: fire is short and hard, bleeding outruns mending,
     * poison feeds on the body rather than on the blow and so troubles the
     * toughest most, cold costs quickness and shock costs turns.
     */
    public static int roundsOf(Element element) {
        return switch (element) {
            case FIRE -> 3;
            case FROST -> 3;
            case SHOCK -> 2;
            case POISON -> 5;
            case BLEED -> 4;
        };
    }

    /** Hundredths of whatever the element feeds on, taken each round. */
    private static int biteOf(Element element) {
        return switch (element) {
            case FIRE -> 30;
            case BLEED -> 25;
            case POISON -> 2;
            case FROST, SHOCK -> 0;
        };
    }

    /**
     * What one round of an affliction costs.
     *
     * @param blow   what the blow that caused it was worth
     * @param maxHp  the whole body of whoever is suffering it
     * @return at least one when it bites at all: an affliction that takes
     *         nothing is an affliction whose icon is a lie
     */
    public static int burnPerRound(Element element, int blow, int maxHp) {
        if (!element.burns()) {
            return 0;
        }
        int of = element.fed() == Element.Fed.BODY ? maxHp : blow;
        return Math.max(1, of * biteOf(element) / 100);
    }

    /** How often somebody stunned loses the round entirely. */
    public static final double SHOCK_LOSES_THE_ROUND = 0.35;

    /**
     * How much of a character's mending bleeding undoes, in hundredths.
     *
     * <p>The one affliction that does not simply subtract: it is the reason a
     * wound is worse than the same damage taken all at once.
     */
    public static final int BLEEDING_UNDOES_MENDING = 50;

    /**
     * What an elemental blow is worth against somebody who resists it.
     *
     * <p>Armour has nothing to say here - that is the whole point of striking
     * with something other than force - so resistance is the only thing between
     * the blow and the body. Immunity really is immunity: no damage, and
     * nothing left behind either.
     */
    public int elementalDamage(int attack, int resistance) {
        int against = Resistances.clamp(resistance);
        if (against >= Resistances.MOST) {
            return 0;
        }
        double swing = MIN_SWING + random.nextDouble() * (MAX_SWING - MIN_SWING);
        int dealt = (int) Math.round(attack * swing * (100 - against) / 100.0);
        return Math.max(MINIMUM_DAMAGE, dealt);
    }

    /**
     * Whether an element takes hold, given how often the weapon manages it and
     * how much the victim resists.
     *
     * <p>Resistance is worth the same here as it is against the damage, so a
     * ring against fire is a ring against being set alight - which is what
     * anybody wearing one would expect of it. Immunity needs no case of its
     * own: a hundred of resistance leaves a hundredth of nothing, and no roll
     * is below nought.
     */
    public boolean takesHold(int chance, int resistance) {
        int against = Resistances.clamp(resistance);
        return random.nextDouble() < chance * (100 - against) / 10_000.0;
    }

    // ---- being set upon ---------------------------------------------------

    /**
     * How far above somebody a creature has to be before it attacks them on its
     * own, in levels.
     *
     * <p>Creatures stand where they were put and never take a step, so the only
     * way to be set upon is to walk up to one. Even then most of them wait: a
     * wolf does not pick a fight it might lose. What makes a creature dangerous
     * to stand beside is being far enough above you that the fight is not
     * really a fight, and twenty levels is that distance for now.
     *
     * <p>The effect on the world as it stands today is worth saying out loud:
     * nothing on any shipped map is twenty levels above a character who could
     * reach it, so nothing attacks first. Walking about is safe, and every
     * fight starts with a click.
     */
    public static final int LEVELS_ABOVE_TO_POUNCE = 20;

    /** Whether this creature would set upon a character of that level. */
    public static boolean pouncesOn(int creatureLevel, int characterLevel) {
        return creatureLevel - characterLevel >= LEVELS_ABOVE_TO_POUNCE;
    }

    // ---- getting out of a fight -------------------------------------------

    public static final double FLEE_CHANCE = 0.6;

    /**
     * How long a character lies there before it can be played again, and the
     * most it can ever be.
     *
     * <p>Rising with the level, like the price of a skill point: a death should
     * cost more the further along you are, or by the tenth level it costs
     * nothing at all. The ceiling is there because the other end of that rule
     * turns one mistake into a quarter of an hour looking at a screen, and no
     * lesson is worth that.
     */
    public static final long WAKE_SECONDS_PER_LEVEL = 20;
    public static final long WAKE_SECONDS_MAX = 300;

    /** How long being killed at this level puts a character out of action. */
    public static long wakeSeconds(int level) {
        return Math.min(WAKE_SECONDS_MAX, WAKE_SECONDS_PER_LEVEL * Math.max(1, level));
    }

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
