package com.kowihere.mmo.persistence;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.loop.ActorSnapshot;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.loop.StoredItem;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a real (in-memory) database with the real migrations applied, so
 * the SQL, the schema and its constraints are tested together rather than
 * mocked apart.
 */
@JdbcTest
@Import(CharacterRepository.class)
class CharacterRepositoryTest {

    @Autowired
    private CharacterRepository characters;

    @Autowired
    private JdbcTemplate jdbc;

    private long ala;
    private long bogumil;

    @BeforeEach
    void createAccounts() {
        ala = account("ala");
        bogumil = account("bogumil");
    }

    @Test
    void storesACharacterAndReadsItBack() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        SavedCharacter found = characters.find("ala").orElseThrow();

        assertThat(found.name()).isEqualTo("Ala");
        assertThat(found.nameKey()).isEqualTo("ala");
        assertThat(found.mapId()).isEqualTo("starter");
        assertThat(found.x()).isEqualTo(7);
        assertThat(found.y()).isEqualTo(11);
        assertThat(found.dir()).isEqualTo(Direction.LEFT);
    }

    @Test
    void savingMovesTheCharacterWithoutCreatingASecond() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        characters.save(new ActorSnapshot("ala", "Ala", "starter", 9, 4, "UP", 3, 450L, 27, 0L,
                Attributes.FRESH, 6, null, "wojownik"));

        SavedCharacter found = characters.find("ala").orElseThrow();
        assertThat(found.x()).isEqualTo(9);
        assertThat(found.y()).isEqualTo(4);
        assertThat(found.dir()).isEqualTo(Direction.UP);
        assertThat(characters.findByAccount(ala)).hasSize(1);
    }

    @Test
    void itemsComeBackWhereTheyWereLeft() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        characters.save(snapshot("ala", List.of(
                new StoredItem("item-1", "zardzewialy-miecz", ItemSlot.WEAPON),
                new StoredItem("item-2", "skorznia", null))));

        SavedCharacter found = characters.find("ala").orElseThrow();
        assertThat(found.items())
                .as("what was worn must come back worn, and what was carried carried")
                .containsExactlyInAnyOrder(
                        new StoredItem("item-1", "zardzewialy-miecz", ItemSlot.WEAPON),
                        new StoredItem("item-2", "skorznia", null));
    }

    @Test
    void savingItemsReplacesThemRatherThanPilingThemUp() {
        // The world hands over everything a character owns, every time. Without
        // the delete, giving somebody one sword twice would leave them holding
        // two - and the duplicate would be indistinguishable from a real one.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        characters.save(snapshot("ala", List.of(
                new StoredItem("item-1", "zardzewialy-miecz", ItemSlot.WEAPON))));

        characters.save(snapshot("ala", List.of(
                new StoredItem("item-1", "zardzewialy-miecz", null))));

        assertThat(characters.find("ala").orElseThrow().items())
                .containsExactly(new StoredItem("item-1", "zardzewialy-miecz", null));
    }

    @Test
    void aSaveThatCarriesNoItemListLeavesTheItemsAlone() {
        // Null means "nothing about the bag changed", which is every save made
        // by somebody merely walking. Reading it as "the bag is empty" would
        // quietly delete everything a character owns, one walk at a time.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        characters.save(snapshot("ala", List.of(
                new StoredItem("item-1", "zardzewialy-miecz", ItemSlot.WEAPON))));

        characters.save(snapshot("ala", null));

        assertThat(characters.find("ala").orElseThrow().items()).hasSize(1);
    }

    @Test
    void attributesAndUnspentPointsSurviveTheRoundTrip() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        characters.save(new ActorSnapshot("ala", "Ala", "starter", 7, 11, "LEFT", 4, 900L, 40, 0L,
                new Attributes(11, 6, 5), 2, null, "mag"));

        SavedCharacter found = characters.find("ala").orElseThrow();
        assertThat(found.attributes()).isEqualTo(new Attributes(11, 6, 5));
        assertThat(found.unspentPoints()).isEqualTo(2);
    }

    @Test
    void aCharacterThatEarnedLevelsBeforeAttributesExistedKeepsWhatTheyAreWorth() {
        // The migration hands existing characters the points their levels are
        // worth. Without it, everybody who had been playing would quietly find
        // themselves as weak as a character created that morning.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        jdbc.update("UPDATE game_character SET level = 5, unspent_points = 3 * (5 - 1)"
                + " WHERE name_key = 'ala'");

        SavedCharacter found = characters.find("ala").orElseThrow();
        assertThat(found.unspentPoints()).isEqualTo(Attributes.pointsEarnedBy(5));
    }

    private static ActorSnapshot snapshot(String nameKey, List<StoredItem> items) {
        return new ActorSnapshot(nameKey, "Ala", "starter", 7, 11, "LEFT", 1, 0L, 20, 0L,
                Attributes.FRESH, 0, items, "wojownik");
    }

    @Test
    void savingACharacterThatIsNotThereCreatesNothing() {
        // The world must not be able to invent a character with no owner, even
        // if one is deleted while it is being played.
        characters.save(new ActorSnapshot("widmo", "Widmo", "starter", 1, 1, "DOWN", 1, 0L, 10, 0L,
                Attributes.FRESH, 0, null, "wojownik"));

        assertThat(characters.find("widmo")).isEmpty();
    }

    @Test
    void reportsNothingForANameThatHasNeverPlayed() {
        assertThat(characters.find("nikt")).isEmpty();
    }

    @Test
    void listsOnlyTheCharactersOfOneAccount() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        characters.create(ala, character("Ala Druga", 2, 2, Direction.DOWN));
        characters.create(bogumil, character("Bogumił", 3, 3, Direction.DOWN));

        assertThat(characters.findByAccount(ala))
                .extracting(SavedCharacter::name)
                .containsExactlyInAnyOrder("Ala", "Ala Druga");
        assertThat(characters.findByAccount(bogumil))
                .extracting(SavedCharacter::name)
                .containsExactly("Bogumił");
    }

    @Test
    void namesTheOwnerOfACharacter() {
        // This is the answer the handshake relies on to keep one account out of
        // another account's character.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        assertThat(characters.ownerOf("ala")).contains(ala);
        assertThat(characters.ownerOf("ala"))
                .hasValueSatisfying(owner -> assertThat(owner).isNotEqualTo(bogumil));
        assertThat(characters.ownerOf("nieistniejaca")).isEmpty();
    }

    @Test
    void refusesASecondCharacterWithTheSameName() {
        // Two characters with one name would be indistinguishable on the map,
        // and the constraint - not a prior lookup - is what settles a race.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        assertThatThrownBy(() -> characters.create(bogumil, character("Ala", 1, 1, Direction.DOWN)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void survivesADirectionItDoesNotRecognise() {
        // A row from a future version, or edited by hand. Facing the wrong way
        // is not a reason to refuse to let someone play.
        characters.create(ala, character("Ala", 3, 3, Direction.DOWN));
        jdbc.update("UPDATE game_character SET dir = 'SIDEWAYS' WHERE name_key = 'ala'");

        assertThat(characters.find("ala").orElseThrow().dir()).isEqualTo(Direction.DOWN);
    }

    private static SavedCharacter character(String name, int x, int y, Direction dir) {
        return SavedCharacter.fresh(name.toLowerCase().replace('ł', 'l'), name, "starter", x, y, dir);
    }

    private long account(String loginKey) {
        jdbc.update("INSERT INTO account (login_key, login, password_hash, created_at, failed_logins)"
                        + " VALUES (?, ?, 'irrelevant', ?, 0)",
                loginKey, loginKey, Timestamp.from(Instant.now()));
        return jdbc.queryForObject("SELECT id FROM account WHERE login_key = ?", Long.class, loginKey);
    }
}
