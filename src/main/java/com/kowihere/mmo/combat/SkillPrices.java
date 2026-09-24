package com.kowihere.mmo.combat;

/**
 * What a skill point costs, as arithmetic with no world around it.
 *
 * <p>The points themselves are earned, one a level, and the player decides where
 * they go. This is only the toll on putting one somewhere - which is what gives
 * the class masters something to do besides undoing.
 */
public final class SkillPrices {

    private SkillPrices() {
    }

    /** What one rank costs at level one, and what each further level adds. */
    public static final int PER_LEVEL = 15;

    /** What is left of that price when the character is standing at their master. */
    public static final double MASTER_SHARE = 0.5;

    /**
     * What raising this skill costs from the character panel.
     *
     * <p>Flat per rank and rising with the level, rather than rising with the
     * rank. Income rises with the level too, so the level is what makes the
     * price weigh something; a price that rose with the rank instead would mean
     * that spreading points thinly is a way of paying less for the same number
     * of them.
     *
     * @param currentRank what the character has now - the first rank of anything
     *                    is free, so a new character can see the system work
     *                    before it has earned a coin
     */
    public static int toRaise(int currentRank, int level) {
        if (currentRank <= 0) {
            return 0;
        }
        return PER_LEVEL * Math.max(1, level);
    }

    /** The same point, bought while standing in front of the right master. */
    public static int atMaster(int fullPrice) {
        return (int) Math.round(fullPrice * MASTER_SHARE);
    }

    /**
     * What a master charges to give every spent point back.
     *
     * <p>Defined entirely by the price above rather than by a number of its own:
     * it is what putting those points back would cost here. So the free first
     * rank of each skill is free to undo as well, which is right - nothing was
     * paid for it.
     *
     * @param ranks how far each known skill has been taken
     */
    public static int toReset(Iterable<Integer> ranks, int level) {
        int total = 0;
        for (int rank : ranks) {
            for (int step = 1; step < rank; step++) {
                total += atMaster(toRaise(step, level));
            }
        }
        return total;
    }
}
