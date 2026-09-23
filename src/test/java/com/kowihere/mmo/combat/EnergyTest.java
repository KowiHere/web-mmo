package com.kowihere.mmo.combat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resource skills are paid for, tested without a fight around it.
 *
 * <p>Energy replaced mana, and the difference is the whole point: mana was a bar
 * that was always full, never moved and was spent by nothing. Every test here
 * is about it moving.
 */
class EnergyTest {

    @Test
    void chargingAddsARoundsWorthAndStopsAtTheCeiling() {
        assertThat(Energy.charged(0, 0)).isEqualTo(Energy.BASE_PER_ROUND);
        assertThat(Energy.charged(Energy.MAX, 0))
                .as("a full bar stays full rather than overflowing")
                .isEqualTo(Energy.MAX);
        assertThat(Energy.charged(Energy.MAX - 1, 5))
                .as("and a nearly full one fills rather than overshooting")
                .isEqualTo(Energy.MAX);
    }

    @Test
    void everyRankOfRegenerationIsWorthTheSameAgain() {
        // The one lever. If the rate came from anywhere else as well, the same
        // thing would be balanced from two directions at once.
        int previous = Energy.perRound(0);
        for (int rank = 1; rank <= 5; rank++) {
            int now = Energy.perRound(rank);
            assertThat(now - previous)
                    .as("rank %d should be worth the same as every other rank", rank)
                    .isEqualTo(Energy.PER_RANK);
            previous = now;
        }
    }

    @Test
    void investingInRegenerationBuysRoundsRatherThanNothing() {
        // What a point in regeneration actually buys, stated as the question
        // anybody balancing a skill asks: how long until I can use this?
        int cost = 40;

        assertThat(Energy.roundsToAfford(cost, 0)).isEqualTo(4);
        assertThat(Energy.roundsToAfford(cost, 2))
                .as("two ranks should shorten the wait, not merely change a number")
                .isLessThan(Energy.roundsToAfford(cost, 0));
    }

    @Test
    void aSkillNobodyCouldEverAffordIsCalledOutAsSuch() {
        assertThat(Energy.roundsToAfford(Energy.MAX + 1, 100))
                .as("costing more than the ceiling means never, at any rank")
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void nonsenseInDoesNotProduceNonsenseOut() {
        assertThat(Energy.charged(-50, 0)).isEqualTo(Energy.BASE_PER_ROUND);
        assertThat(Energy.perRound(-3)).isEqualTo(Energy.BASE_PER_ROUND);
    }
}
