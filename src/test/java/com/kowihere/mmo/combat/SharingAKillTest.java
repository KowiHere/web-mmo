package com.kowihere.mmo.combat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one kill is worth to a party, in numbers somebody can check.
 *
 * <p>Equal shares out of a pot slightly larger than the kill, and a guard on
 * how far apart the levels may be. The guard is the interesting half: it is
 * what stops the bonus being an argument for playing five accounts at once.
 */
class SharingAKillTest {

    @Test
    void aloneIsExactlyWhatItAlwaysWas() {
        // Pinned on purpose. This milestone must not move anybody's levelling
        // curve by a single point as a side effect.
        assertThat(CombatRules.shareOf(100, 1)).isEqualTo(100);
        assertThat(CombatRules.shareOf(7, 1)).isEqualTo(7);
    }

    @Test
    void twoTogetherAreWorthMoreThanTwoApart() {
        // 115 between two is 57 each: less than a kill of their own, more than
        // half of one. The party is worth joining and not worth farming.
        assertThat(CombatRules.shareOf(100, 2)).isEqualTo(57);
        assertThat(CombatRules.shareOf(100, 2) * 2)
                .as("and together they take home more than the creature was worth")
                .isGreaterThan(100);
    }

    @Test
    void theBonusDoesNotGrowWithTheCrowd() {
        // The whole point of one flat figure: the fifth member is not a reason
        // to open a fifth account, because they bring nothing extra to the pot.
        assertThat(CombatRules.shareOf(100, 5)).isEqualTo(23);
        assertThat(CombatRules.shareOf(100, 5) * 5)
                .as("five share the same pot two did")
                .isEqualTo(115);
    }

    @Test
    void whatTheDivisionCannotSplitIsLost() {
        // 115 between three is 38 each and one left over, which goes nowhere.
        // Handing it to whoever struck last would be a quiet bonus for the last
        // blow - the very thing this replaces.
        assertThat(CombatRules.shareOf(100, 3)).isEqualTo(38);
        assertThat(CombatRules.shareOf(100, 3) * 3).isEqualTo(114);
    }

    @Test
    void twoThirdsIsTheFurthestApartAPartyMayBe() {
        assertThat(CombatRules.worthSharingBetween(20, 30))
                .as("two thirds exactly, which counts")
                .isTrue();
        assertThat(CombatRules.worthSharingBetween(19, 30)).isFalse();
        assertThat(CombatRules.worthSharingBetween(30, 30)).isTrue();
    }

    @Test
    void anAltCannotBeCarried() {
        // The answer to the whole multi-account worry: a level-one beside a
        // level-twenty does not take a small share, it ends the sharing.
        assertThat(CombatRules.worthSharingBetween(1, 20)).isFalse();
        assertThat(CombatRules.worthSharingBetween(1, 1))
                .as("two beginners together are perfectly fine")
                .isTrue();
    }
}
