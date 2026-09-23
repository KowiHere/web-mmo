package com.kowihere.mmo.world;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.Attributes.Attribute;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Classes are content like everything else, and the loader's job is the same:
 * turn a typo into a server that will not start, rather than into a class
 * somebody picks and then cannot play.
 */
class ClassDefLoaderTest {

    private final Map<String, ClassDef> classes = new ClassDefLoader().loadAll();

    @Test
    void everyClassFightsWithADifferentAttribute() {
        // This is what makes them classes rather than three sets of bonuses. If
        // two of them drew their blows from the same attribute, one would
        // simply be the worse way to play the other.
        assertThat(classes.values())
                .extracting(ClassDef::damageFrom)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder(Attribute.STRENGTH, Attribute.AGILITY, Attribute.INTELLECT);
    }

    @Test
    void everyClassStartsWithItsOwnAttributeHighest() {
        // A mage that starts with more strength than intellect would be a mage
        // in name only, and nobody would find out until they played one.
        for (ClassDef def : classes.values()) {
            Attributes start = def.startingAttributes();

            for (Attribute other : Attribute.values()) {
                if (other == def.damageFrom()) {
                    continue;
                }
                assertThat(start.of(def.damageFrom()))
                        .as("%s fights with %s but starts with more %s",
                                def.name(), def.damageFrom(), other)
                        .isGreaterThan(start.of(other));
            }
        }
    }

    @Test
    void theClassEverythingFallsBackOnExists() {
        // The database lets class_id be empty, and a character whose class was
        // renamed has to land somewhere. That somewhere has to be real.
        assertThat(classes).containsKey(ClassDefLoader.FALLBACK_ID);
    }

    @Test
    void theOrderOfferedToAPlayerIsTheSameEveryTime() {
        // The loaders hand back immutable maps, whose iteration order is not
        // specified. A selection screen whose options move between restarts is
        // a small thing that looks like a bug.
        assertThat(classes.keySet())
                .containsExactlyElementsOf(new ClassDefLoader().loadAll().keySet());
    }

    @Test
    void refusesAnAttributeThatDoesNotExist() {
        assertThatThrownBy(() -> new ClassDefLoader("classpath:bad-classes-attribute/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHARISMA");
    }

    @Test
    void refusesArmourPiercingThatIsNotAFraction() {
        // Above one would mean blows that count armour in the wearer's favour.
        assertThatThrownBy(() -> new ClassDefLoader("classpath:bad-classes-armor/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("armorIgnored");
    }

    @Test
    void refusesAServerWithNoClassesAtAll() {
        // Every character has a class, so a server with none could not create
        // one. The fixture directory exists and simply holds no definitions:
        // a missing directory is a different failure, and the resolver reports
        // that one by itself.
        assertThatThrownBy(() -> new ClassDefLoader("classpath:classes-empty/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No character classes");
    }
}
