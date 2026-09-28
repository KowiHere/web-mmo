package com.kowihere.mmo.world;

import com.kowihere.mmo.combat.Element;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Elements written in files, and every way of writing them wrongly.
 *
 * <p>The failure this guards against is always the same one: a sword whose
 * every tooltip says fire and which has never set anything alight. Nobody
 * reports that - they report the sword being weak, and it is never found.
 */
class ElementsAsContentTest {

    private static ItemDefLoader items(String where) {
        return new ItemDefLoader(where);
    }

    @Test
    void aWeaponSaysWhichElementAndHowOften() {
        ItemDef ember = new ItemDefLoader().loadAll().get("zarzewie");

        assertThat(ember.strikes()).isNotNull();
        assertThat(ember.strikes().element()).isEqualTo(Element.FIRE);
        assertThat(ember.strikes().chance()).isBetween(1, 100);
    }

    @Test
    void armourSaysWhatItShrugsOffAndWhatItDoesNot() {
        ItemDef mail = new ItemDefLoader().loadAll().get("kolczuga-hartowana");

        assertThat(mail.resists().of(Element.FIRE)).isPositive();
        assertThat(mail.resists().of(Element.SHOCK))
                .as("and a negative line is allowed, or resistance would only ever help")
                .isNegative();
    }

    @Test
    void aCreatureCanBeMadeOfTheThingItSpits() {
        MobDef bat = new MobDefLoader().loadAll().get("nietoperz");

        assertThat(bat.strikes().element()).isEqualTo(Element.POISON);
        assertThat(bat.resists().of(Element.POISON))
                .as("it is not going to poison itself")
                .isEqualTo(100);
    }

    @Test
    void refusesAnElementThisGameDoesNotHave() {
        assertThatThrownBy(() -> items("classpath:bad-items-element/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("swiatlo");
    }

    @Test
    void refusesAWeaponThatNeverSaysHowOften() {
        // Read leniently, "no chance given" becomes nought or a hundred, and
        // either is a decision about the whole balance of the game made by a
        // missing line.
        assertThatThrownBy(() -> items("classpath:bad-items-element-chance/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never says how often");
    }

    @Test
    void refusesAChanceThatIsNotAChance() {
        assertThatThrownBy(() -> items("classpath:bad-items-element-range/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("140");
    }

    @Test
    void refusesAnElementOnSomethingThatStrikesNoBlows() {
        // An element rides on a blow. On a breastplate it is a line that can
        // never happen, reading in the shop exactly like one that can.
        assertThatThrownBy(() -> items("classpath:bad-items-element-harmless/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never strikes at all");
    }

    @Test
    void refusesResistingSomethingThatDoesNotExist() {
        assertThatThrownBy(() -> items("classpath:bad-items-resist-unknown/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("swiatlo");
    }

    @Test
    void refusesResistingSomethingByNothing() {
        assertThatThrownBy(() -> items("classpath:bad-items-resist-zero/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("by nothing at all");
    }

    @Test
    void refusesResistanceBeyondImmune() {
        assertThatThrownBy(() -> items("classpath:bad-items-resist-range/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("250");
    }

    @Test
    void refusesAnEmptyBlockOfResistances() {
        assertThatThrownBy(() -> items("classpath:bad-items-resist-empty/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void refusesResistanceOnSomethingNobodyWears() {
        assertThatThrownBy(() -> items("classpath:bad-items-resist-carried/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could never reach anybody");
    }

    @Test
    void refusesACreatureStrikingWithSomethingInvented() {
        assertThatThrownBy(() ->
                new MobDefLoader("classpath:bad-mobs-element/*.json", Map.of()).loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ciemnosc");
    }
}
