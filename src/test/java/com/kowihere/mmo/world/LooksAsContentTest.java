package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How content says a thing is drawn.
 *
 * <p>Only the identity: a creature says "I look like a wolf", and what a wolf
 * looks like stays with whoever is drawing. The registry exists for one reason
 * - a misspelt look is drawn as the plain one, so in the file it is
 * indistinguishable from a creature that was given a look, and in the game it
 * is indistinguishable from one that was not.
 */
class LooksAsContentTest {

    @Test
    void everyShippedCreatureSaysHowItIsDrawn() {
        // Not pedantry: until this milestone every creature of a tier was drawn
        // identically, so a wolf, a boar and a bear were three brown circles.
        for (MobDef mob : new MobDefLoader().loadAll().values()) {
            assertThat(mob.look())
                    .as("'%s' has no look, so it is drawn as whatever its tier is", mob.id())
                    .isNotNull();
        }
    }

    @Test
    void soDoesEveryPersonAndEveryClass() {
        for (NpcDef npc : new NpcDefLoader().loadAll().values()) {
            assertThat(npc.look()).as("'%s' has no look", npc.id()).isNotNull();
        }
        for (ClassDef characterClass : new ClassDefLoader().loadAll().values()) {
            assertThat(characterClass.look())
                    .as("class '%s' has no look", characterClass.id()).isNotNull();
        }
    }

    @Test
    void everyLookNamedByContentExists() {
        var looks = new LookDefLoader().loadAll();

        assertThat(looks).isNotEmpty();
        for (MobDef mob : new MobDefLoader().loadAll().values()) {
            assertThat(looks).containsKey(mob.look());
        }
    }

    @Test
    void refusesALookNobodyDeclared() {
        // The whole reason the registry is there.
        assertThatThrownBy(() ->
                new MobDefLoader("classpath:bad-looks-unknown/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nie-ma-takiego-wygladu");
    }

    @Test
    void somethingWithNoLookIsAllowedAndSaysSo() {
        // Every test fixture in the project is such a thing, and none of them
        // had to be rewritten for this milestone.
        MobDef plain = new MobDefLoader("classpath:test-mobs/*.json").loadAll().get("kloda");

        assertThat(plain).isNotNull();
        assertThat(plain.look()).isNull();
    }
}
