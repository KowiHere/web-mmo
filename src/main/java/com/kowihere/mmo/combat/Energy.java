package com.kowihere.mmo.combat;

/**
 * What a character has to spend on skills, and how it gets it.
 *
 * <p>Energy belongs to a <em>fight</em>, not to a character. It starts every
 * fight at zero, charges as the rounds go by, and is gone when the fight ends.
 * That is the whole difference between this and the mana it replaces: mana was
 * a bar that was always full, never moved and was spent by nothing, which is a
 * decoration rather than a resource.
 *
 * <p>The cap is the same hundred for everybody on purpose. The only thing that
 * decides how quickly a character can afford anything is how many points it put
 * into charging faster — one lever, in one place, rather than the same knob
 * turned from two directions.
 */
public final class Energy {

    /** The same ceiling for everyone; what differs is how fast you reach it. */
    public static final int MAX = 100;

    /** What a character charges per round having put nothing into it at all. */
    public static final int BASE_PER_ROUND = 10;

    /** And what each rank of the regeneration skill adds to that. */
    public static final int PER_RANK = 5;

    private Energy() {
    }

    public static int perRound(int regenerationRank) {
        return BASE_PER_ROUND + PER_RANK * Math.max(0, regenerationRank);
    }

    /** One round's worth of charging, never past the ceiling. */
    public static int charged(int current, int regenerationRank) {
        return Math.min(MAX, Math.max(0, current) + perRound(regenerationRank));
    }

    /**
     * How many rounds of a fight must pass before {@code cost} is affordable,
     * counting the charge that happens at the start of the first one.
     *
     * <p>Not used by the loop - it is here because it is the question anyone
     * balancing a skill actually asks, and answering it in a test is worth more
     * than answering it by playing.
     */
    public static int roundsToAfford(int cost, int regenerationRank) {
        if (cost <= 0) {
            return 1;
        }
        int rate = perRound(regenerationRank);
        if (cost > MAX) {
            return Integer.MAX_VALUE; // never; the ceiling is lower than the price
        }
        return (int) Math.ceil(cost / (double) rate);
    }
}
