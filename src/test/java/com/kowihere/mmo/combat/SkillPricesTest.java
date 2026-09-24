package com.kowihere.mmo.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a skill point costs, worked out without a world around it.
 *
 * <p>The points are earned and the player decides where they go; this is only
 * the toll on putting one somewhere, and the reason the class masters have
 * anything to do besides undoing.
 */
class SkillPricesTest {

    @Test
    void theFirstRankOfAnythingIsFree() {
        // A new character has one point and no money. Charging for the first
        // rank would mean half an hour of owning a point that cannot be spent,
        // which looks exactly like a broken button.
        assertThat(SkillPrices.toRaise(0, 1)).isZero();
        assertThat(SkillPrices.toRaise(0, 40))
                .as("and it stays free however far along the character is")
                .isZero();
    }

    @Test
    void everyFurtherRankCostsTheSameAtOneLevel() {
        // Flat per rank on purpose. A price that rose with the rank would mean
        // spreading points thinly is a way of paying less for the same number
        // of them.
        int level = 6;

        assertThat(SkillPrices.toRaise(1, level))
                .isEqualTo(SkillPrices.toRaise(4, level))
                .isPositive();
    }

    @Test
    void thePriceRisesWithTheLevel() {
        // Income rises with the level too, so the level is what makes the price
        // weigh anything at all.
        assertThat(SkillPrices.toRaise(1, 10)).isGreaterThan(SkillPrices.toRaise(1, 2));
        assertThat(SkillPrices.toRaise(1, 4)).isEqualTo(SkillPrices.PER_LEVEL * 4);
    }

    @Test
    void aMasterChargesHalf() {
        assertThat(SkillPrices.atMaster(SkillPrices.toRaise(1, 8)))
                .isEqualTo(SkillPrices.toRaise(1, 8) / 2);
    }

    @Test
    void nonsenseInDoesNotProduceNonsenseOut() {
        assertThat(SkillPrices.toRaise(1, 0)).isEqualTo(SkillPrices.PER_LEVEL);
        assertThat(SkillPrices.toRaise(-3, 5)).isZero();
    }

    @Test
    void aResetCostsWhatPuttingItAllBackWouldCostHere() {
        // The whole definition, and the reason it introduces no number of its
        // own: undoing is priced by the thing it undoes.
        int level = 5;
        int perPoint = SkillPrices.atMaster(SkillPrices.toRaise(1, level));

        // One skill at rank 3 is two paid ranks; the first was free.
        assertThat(SkillPrices.toReset(List.of(3), level)).isEqualTo(2 * perPoint);
        assertThat(SkillPrices.toReset(List.of(3, 2), level)).isEqualTo(3 * perPoint);
    }

    @Test
    void whatWasNeverPaidForIsFreeToUndo() {
        // Two skills at rank one is two free ranks, so giving them back is free.
        assertThat(SkillPrices.toReset(List.of(1, 1), 12)).isZero();
        assertThat(SkillPrices.toReset(List.of(), 12)).isZero();
    }

    @Test
    void undoingIsCheaperThanHavingDoneItFromThePanel() {
        // Otherwise a reset would cost more than the mistake did, and nobody
        // would ever use the one thing these NPCs exist for.
        int level = 7;
        int paidFromThePanel = SkillPrices.toRaise(1, level) * 3;

        assertThat(SkillPrices.toReset(List.of(4), level)).isLessThan(paidFromThePanel);
    }
}
