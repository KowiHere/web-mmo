package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.Element;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list of afflictions, counted by rounds.
 *
 * <p>Same rule as blessings - refreshed, never stacked - and one of its own:
 * the harder of two bites wins, so a glancing blow cannot water down a burn
 * that a hard one started.
 */
class AilmentsTest {

    @Test
    void theSameElementAgainRefreshesRatherThanStacking() {
        Ailments ailments = new Ailments();
        ailments.afflict(Element.FIRE, 3, 10);
        ailments.wearOff();

        ailments.afflict(Element.FIRE, 3, 10);

        assertThat(ailments.all()).hasSize(1);
        assertThat(ailments.all().get(0).roundsLeft).isEqualTo(3);
    }

    @Test
    void theHarderBiteWins() {
        Ailments ailments = new Ailments();
        ailments.afflict(Element.FIRE, 3, 20);

        ailments.afflict(Element.FIRE, 3, 4);

        assertThat(ailments.all().get(0).perRound)
                .as("a glancing blow must not put out half the fire")
                .isEqualTo(20);
    }

    @Test
    void theLongerOfTwoIsHowLongItLasts() {
        Ailments ailments = new Ailments();
        ailments.afflict(Element.POISON, 5, 3);
        ailments.wearOff();
        ailments.wearOff();

        ailments.afflict(Element.POISON, 2, 3);

        assertThat(ailments.all().get(0).roundsLeft).isEqualTo(3);
    }

    @Test
    void differentElementsAreDifferentAfflictions() {
        Ailments ailments = new Ailments();
        ailments.afflict(Element.FIRE, 3, 10);
        ailments.afflict(Element.BLEED, 4, 5);

        assertThat(ailments.all()).hasSize(2);
        assertThat(ailments.has(Element.FIRE)).isTrue();
        assertThat(ailments.has(Element.SHOCK)).isFalse();
    }

    @Test
    void theyRunOutAndSaySo() {
        Ailments ailments = new Ailments();
        ailments.afflict(Element.SHOCK, 2, 0);

        assertThat(ailments.wearOff()).isEmpty();
        assertThat(ailments.wearOff()).containsExactly(Element.SHOCK);
        assertThat(ailments.any()).isFalse();
    }

    @Test
    void anOpenWoundOutrunsMending() {
        // What makes bleeding worse than the same damage taken all at once,
        // and the reason it is an element of its own rather than a cooler fire.
        Actor ala = new Actor(1, "Ala", "ala", 1L, 5, 5, 1);
        ala.blessings.lay(new com.kowihere.mmo.world.BlessingDef(
                "probne-krzepienie", "Próbne krzepienie",
                com.kowihere.mmo.world.Rarity.COMMON, 5, 1,
                java.util.Map.of(com.kowihere.mmo.world.BlessingStat.HEAL_PER_ROUND,
                        com.kowihere.mmo.world.BlessingLine.flat(10))));
        assertThat(ala.healPerRound()).isEqualTo(10);

        ala.ailments.afflict(Element.BLEED, 4, 3);

        assertThat(ala.healPerRound())
                .as("half the mending goes into the wound")
                .isEqualTo(5);
    }

    @Test
    void theColdIsNotAboutHealthAtAll() {
        Actor ala = new Actor(1, "Ala", "ala", 1L, 5, 5, 1);
        ala.attributes = new com.kowihere.mmo.combat.Attributes(5, 20, 5);
        assertThat(ala.dodgeChance()).isPositive();
        assertThat(ala.secondBlowChance()).isPositive();

        ala.ailments.afflict(Element.FROST, 3, 0);

        assertThat(ala.dodgeChance()).isZero();
        assertThat(ala.secondBlowChance()).isZero();
    }

    @Test
    void theFightEndingEndsAllOfThem() {
        Ailments ailments = new Ailments();
        ailments.afflict(Element.FIRE, 3, 10);
        ailments.afflict(Element.POISON, 5, 2);

        ailments.clear();

        assertThat(ailments.any()).isFalse();
    }
}
