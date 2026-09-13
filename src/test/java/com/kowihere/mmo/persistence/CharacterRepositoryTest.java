package com.kowihere.mmo.persistence;

import com.kowihere.mmo.loop.ActorSnapshot;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.world.Direction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real (in-memory) database with the real migration applied, so
 * the SQL and the schema are tested together rather than mocked apart.
 */
@JdbcTest
@Import(CharacterRepository.class)
class CharacterRepositoryTest {

    @Autowired
    private CharacterRepository characters;

    @Test
    void storesACharacterAndReadsItBack() {
        characters.save(new ActorSnapshot("ala", "Ala", "starter", 7, 11, "LEFT"));

        Optional<SavedCharacter> found = characters.find("ala");

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Ala");
        assertThat(found.get().mapId()).isEqualTo("starter");
        assertThat(found.get().x()).isEqualTo(7);
        assertThat(found.get().y()).isEqualTo(11);
        assertThat(found.get().dir()).isEqualTo(Direction.LEFT);
    }

    @Test
    void savingTwiceUpdatesRatherThanDuplicating() {
        // The primary key would reject a second insert, so this also proves the
        // update-then-insert path picks update when the row is already there.
        characters.save(new ActorSnapshot("ala", "Ala", "starter", 7, 11, "LEFT"));
        characters.save(new ActorSnapshot("ala", "Ala", "starter", 9, 4, "UP"));

        SavedCharacter found = characters.find("ala").orElseThrow();

        assertThat(found.x()).isEqualTo(9);
        assertThat(found.y()).isEqualTo(4);
        assertThat(found.dir()).isEqualTo(Direction.UP);
    }

    @Test
    void reportsNothingForANameThatHasNeverPlayed() {
        assertThat(characters.find("nikt")).isEmpty();
    }

    @Test
    void keepsCharactersWithDifferentNamesApart() {
        characters.save(new ActorSnapshot("ala", "Ala", "starter", 7, 11, "LEFT"));
        characters.save(new ActorSnapshot("bogumil", "Bogumił", "starter", 2, 2, "DOWN"));

        assertThat(characters.find("ala").orElseThrow().x()).isEqualTo(7);
        assertThat(characters.find("bogumil").orElseThrow().name()).isEqualTo("Bogumił");
    }

    @Test
    void survivesADirectionItDoesNotRecognise() {
        // A row from a future version, or edited by hand. Facing the wrong way
        // is not a reason to refuse to let someone play.
        characters.save(new ActorSnapshot("ala", "Ala", "starter", 3, 3, "SIDEWAYS"));

        assertThat(characters.find("ala").orElseThrow().dir()).isEqualTo(Direction.DOWN);
    }
}
