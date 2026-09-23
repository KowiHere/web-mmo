package com.kowihere.mmo.combat;

import com.kowihere.mmo.combat.Attributes.Attribute;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a character is made of, tested without a world.
 *
 * <p>Nothing here is stored anywhere, which is the point: every number below is
 * computed from the three that are, so there is nothing that can fall out of
 * agreement with anything else.
 */
class AttributesTest {

    @Test
    void strengthIsHealthAndStrengthIsDamage() {
        Attributes weak = new Attributes(5, 5, 5);
        Attributes strong = new Attributes(15, 5, 5);

        assertThat(strong.maxHp()).isGreaterThan(weak.maxHp());
        assertThat(strong.attack()).isGreaterThan(weak.attack());
    }

    @Test
    void intellectIsManaAndNothingElseYet() {
        Attributes plain = new Attributes(5, 5, 5);
        Attributes learned = new Attributes(5, 5, 20);

        assertThat(learned.maxMana()).isGreaterThan(plain.maxMana());
        // Deliberate: there are no spells to pay for, and an attribute that
        // quietly improved a sword would be a lie about what it is for.
        assertThat(learned.attack()).isEqualTo(plain.attack());
        assertThat(learned.maxHp()).isEqualTo(plain.maxHp());
    }

    @Test
    void agilityBuysEvasionAndSecondBlowsButOnlySoFar() {
        assertThat(new Attributes(5, 0, 5).dodgeChance()).isZero();

        double previous = -1;
        for (int agility = 0; agility <= 200; agility += 10) {
            double dodge = new Attributes(5, agility, 5).dodgeChance();
            assertThat(dodge).as("dodge at %d agility", agility).isGreaterThanOrEqualTo(previous);
            previous = dodge;
        }

        // Capped on purpose. A character that eventually cannot be hit is a
        // character in a fight that never ends - the same worry that puts a
        // floor of one under every blow, seen from the other side.
        assertThat(new Attributes(5, 10_000, 5).dodgeChance()).isLessThan(0.5);
        assertThat(new Attributes(5, 10_000, 5).secondBlowChance()).isLessThan(0.5);
    }

    @Test
    void aFreshCharacterHasExactlyWhatItHadBeforeAttributesExisted() {
        // 65 health and 7 attack are the numbers the starter map's creatures
        // were balanced against. Changing them here would have made that map
        // unwinnable again, quietly, and only for new players.
        assertThat(Attributes.FRESH.maxHp()).isEqualTo(65);
        assertThat(Attributes.FRESH.attack()).isEqualTo(7);
    }

    @Test
    void levellingIsWorthPointsAndLevelOneIsNotALevelUp() {
        assertThat(Attributes.pointsEarnedBy(1)).isZero();
        assertThat(Attributes.pointsEarnedBy(2)).isEqualTo(Attributes.POINTS_PER_LEVEL);
        assertThat(Attributes.pointsEarnedBy(10))
                .isEqualTo(Attributes.POINTS_PER_LEVEL * 9);
    }

    @Test
    void spendingAPointAddsToExactlyOneAttribute() {
        Attributes after = Attributes.FRESH.plus(Attribute.AGILITY, 1);

        assertThat(after.agility()).isEqualTo(Attributes.FRESH.agility() + 1);
        assertThat(after.strength()).isEqualTo(Attributes.FRESH.strength());
        assertThat(after.intellect()).isEqualTo(Attributes.FRESH.intellect());
    }

    @Test
    void equipmentAddsToWhatYouAlreadyHave() {
        Attributes fromItems = new Attributes(4, 0, 8);

        assertThat(Attributes.FRESH.plus(fromItems))
                .isEqualTo(new Attributes(9, 5, 13));
    }

    @Test
    void anAttributeCannotBeNegative() {
        // Not pedantry: negative strength would mean negative health, and a
        // character that is dead the moment it is created.
        assertThatThrownBy(() -> new Attributes(-1, 5, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAttributeNamedByAClientIsEitherRealOrNothing() {
        assertThat(Attribute.parse("strength")).isEqualTo(Attribute.STRENGTH);
        assertThat(Attribute.parse("AGILITY")).isEqualTo(Attribute.AGILITY);
        assertThat(Attribute.parse("charisma")).isNull();
        assertThat(Attribute.parse(null)).isNull();
    }
}
