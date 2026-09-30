package com.kowihere.mmo.combat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a creature sets upon somebody standing beside it.
 *
 * <p>Creatures no longer walk, so being attacked is something a player brings
 * upon themselves by stepping up to one. Even then most of them do nothing:
 * what makes a creature dangerous to stand next to is being so far above you
 * that the fight is not a fight, and that gap is the whole rule.
 */
class PouncingTest {

    @Test
    void anythingLevelWithYouLeavesYouAlone() {
        assertThat(CombatRules.pouncesOn(5, 5)).isFalse();
        assertThat(CombatRules.pouncesOn(20, 5))
                .as("fifteen levels up and it still will not start anything")
                .isFalse();
    }

    @Test
    void farEnoughAboveYouAndItDoesNotWait() {
        assertThat(CombatRules.pouncesOn(25, 5)).isTrue();
        assertThat(CombatRules.pouncesOn(21, 1))
                .as("exactly the gap counts: twenty is twenty")
                .isTrue();
    }

    @Test
    void nothingBelowYouEverStartsIt() {
        assertThat(CombatRules.pouncesOn(1, 30)).isFalse();
    }

    @Test
    void theGapIsWhatDecides() {
        // Not the creature's own level: a level-forty creature leaves a
        // level-forty character alone, and the same creature sets upon somebody
        // twenty levels beneath them.
        assertThat(CombatRules.pouncesOn(40, 40)).isFalse();
        assertThat(CombatRules.pouncesOn(40, 20)).isTrue();
        assertThat(CombatRules.LEVELS_ABOVE_TO_POUNCE).isEqualTo(20);
    }
}
