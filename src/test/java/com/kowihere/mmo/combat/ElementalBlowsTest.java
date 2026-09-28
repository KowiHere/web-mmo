package com.kowihere.mmo.combat;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an element is worth, in numbers somebody can check.
 *
 * <p>The roll is fixed at the middle of its swing, so these are arithmetic
 * rather than samples. A combat formula nobody can pin down is a combat formula
 * nobody can balance.
 */
class ElementalBlowsTest {

    /** Every swing lands at the middle of its range: no swing at all. */
    private static final CombatRules EVEN = new CombatRules(new Random() {
        @Override
        public double nextDouble() {
            return 0.5;
        }
    });

    @Test
    void resistanceIsTheWholeOfTheDefence() {
        // Armour has nothing to say against an element, which is the reason to
        // carry a brand against something in plate.
        assertThat(EVEN.elementalDamage(100, 0)).isEqualTo(100);
        assertThat(EVEN.elementalDamage(100, 50)).isEqualTo(50);
    }

    @Test
    void beingVulnerableIsTheSameSumTheOtherWay() {
        assertThat(EVEN.elementalDamage(100, -50)).isEqualTo(150);
    }

    @Test
    void immunityReallyIsImmunity() {
        // Nought, not one. The floor of one under every blow exists so that a
        // fight cannot last for ever; an element nothing can hurt you with is
        // not that fight, because the same creature is still being hit.
        assertThat(EVEN.elementalDamage(100, 100)).isZero();
        assertThat(EVEN.takesHold(100, 100))
                .as("and nothing takes hold either")
                .isFalse();
    }

    @Test
    void nothingGoesPastImmuneOrPastDouble() {
        assertThat(EVEN.elementalDamage(100, 400)).isZero();
        assertThat(EVEN.elementalDamage(100, -400))
                .as("two cursed rings must not turn one blow into four")
                .isEqualTo(200);
    }

    @Test
    void resistanceMakesItTakeHoldLessOften() {
        // The same number does both jobs: a ring against fire is a ring against
        // being set alight, which is what anybody wearing one expects of it.
        CombatRules sixInTen = new CombatRules(new Random() {
            @Override
            public double nextDouble() {
                return 0.6;
            }
        });

        assertThat(sixInTen.takesHold(100, 0))
                .as("a brand that always takes hold, against nothing")
                .isTrue();
        assertThat(sixInTen.takesHold(100, 50))
                .as("the same brand is a coin flip against half resistance, and this roll lost")
                .isFalse();
    }

    @Test
    void fireFeedsOnTheBlowAndPoisonOnTheBody() {
        // The reason there are five of these and not one: poison troubles the
        // toughest creature most, and fire troubles whoever was hit hardest.
        assertThat(CombatRules.burnPerRound(Element.FIRE, 40, 1_000)).isEqualTo(12);
        assertThat(CombatRules.burnPerRound(Element.POISON, 40, 1_000)).isEqualTo(20);
        assertThat(CombatRules.burnPerRound(Element.BLEED, 40, 1_000)).isEqualTo(10);
    }

    @Test
    void whatCostsQuicknessOrTurnsCostsNoHealth() {
        assertThat(CombatRules.burnPerRound(Element.FROST, 40, 1_000)).isZero();
        assertThat(CombatRules.burnPerRound(Element.SHOCK, 40, 1_000)).isZero();
    }

    @Test
    void anAfflictionAlwaysTakesSomething() {
        // A burn of nought is an icon that lies: the bar does not move and the
        // player is left wondering what the brand is for.
        assertThat(CombatRules.burnPerRound(Element.FIRE, 1, 1)).isEqualTo(1);
        assertThat(CombatRules.burnPerRound(Element.POISON, 1, 1)).isEqualTo(1);
    }

    @Test
    void everyElementLastsSomeRounds() {
        for (Element element : Element.values()) {
            assertThat(CombatRules.roundsOf(element))
                    .as("%s has to last at least one round", element)
                    .isPositive();
        }
    }
}
