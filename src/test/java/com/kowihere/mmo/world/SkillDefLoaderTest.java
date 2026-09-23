package com.kowihere.mmo.world;

import com.kowihere.mmo.combat.Energy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Skills are hand-edited content, so the loader turns a typo into a server that
 * will not start rather than into a skill somebody spends a point on and then
 * finds does nothing. Most of these are about refusing things.
 */
class SkillDefLoaderTest {

    private final Map<String, SkillDef> skills = new SkillDefLoader().loadAll();

    @Test
    void loadsTheShippedSkills() {
        SkillDef lightning = skills.get("blyskawica");

        assertThat(lightning).isNotNull();
        assertThat(lightning.classId()).isEqualTo("mag");
        assertThat(lightning.cost()).isPositive();
        assertThat(lightning.overridesArmorIgnored()).isTrue();
    }

    @Test
    void everyClassHasSomethingOfItsOwn() {
        // A class whose only skill is the one everybody shares would have
        // nothing to spend energy on, and energy would be back to being a bar
        // that does not move.
        for (ClassDef characterClass : new ClassDefLoader().loadAll().values()) {
            assertThat(skills.values())
                    .as("%s has no skill of its own", characterClass.name())
                    .anyMatch(skill -> characterClass.id().equals(skill.classId()));
        }
    }

    @Test
    void theOneThatChargesEnergyIsPassiveAndOpenToEverybody() {
        SkillDef regeneration = skills.get(SkillDefLoader.REGENERATION_ID);

        assertThat(regeneration.isPassive())
                .as("a skill that has to be used to make energy could never be used first")
                .isTrue();
        assertThat(regeneration.classId())
                .as("every class needs energy, so every class needs this")
                .isNull();
        assertThat(regeneration.energyPerRank()).isPositive();
    }

    @Test
    void noShippedSkillCostsMoreThanAnybodyCanHold() {
        assertThat(skills.values())
                .allSatisfy(skill -> assertThat(skill.cost()).isLessThanOrEqualTo(Energy.MAX));
    }

    @Test
    void aSkillIsOfferedToItsOwnClassAndToNobodyElse() {
        SkillDef lightning = skills.get("blyskawica");

        assertThat(lightning.availableTo("mag")).isTrue();
        assertThat(lightning.availableTo("wojownik")).isFalse();
        assertThat(skills.get(SkillDefLoader.REGENERATION_ID).availableTo("wojownik")).isTrue();
    }

    @Test
    void refusesASkillBelongingToAClassThatDoesNotExist() {
        assertThatThrownBy(() -> new SkillDefLoader("classpath:bad-skills-class/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tancerz");
    }

    @Test
    void refusesAPassiveSkillThatGrantsNothing() {
        // The fixture writes "energiaNaRange" where it means "energyPerRank".
        // Loading it would produce a skill that is learned, costs a point and
        // changes nothing whatsoever.
        assertThatThrownBy(() -> new SkillDefLoader("classpath:bad-skills-empty/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("energyPerRank");
    }

    @Test
    void refusesASkillThatIsPaidForAndStrikesNothing() {
        assertThatThrownBy(() -> new SkillDefLoader("classpath:bad-skills-idle/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never strikes");
    }

    @Test
    void refusesASkillNobodyCouldEverAfford() {
        assertThatThrownBy(() -> new SkillDefLoader("classpath:bad-skills-costly/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never be used");
    }

    @Test
    void refusesContentWithNothingToChargeEnergy() {
        // Without it every character charges at the base rate for ever and no
        // point spent on regeneration can do anything - and nothing else in the
        // game would report that.
        assertThatThrownBy(() ->
                new SkillDefLoader("classpath:skills-without-regen/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SkillDefLoader.REGENERATION_ID);
    }
}
