package com.kowihere.mmo.persistence;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.loop.ActorSnapshot;
import com.kowihere.mmo.loop.Deposit;
import com.kowihere.mmo.loop.StoredCoin;
import com.kowihere.mmo.loop.StoredDeposit;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.loop.StoredItem;
import com.kowihere.mmo.loop.StoredSkill;
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
                Attributes.FRESH, 6, null, "wojownik", 3, null, null, 0L, null, null));

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
    void learnedSkillsComeBackAtTheRankTheyWereLeftAt() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        characters.save(withSkills("ala", List.of(
                new StoredSkill("regeneracja", 3),
                new StoredSkill("blyskawica", 1))));

        assertThat(characters.find("ala").orElseThrow().skills())
                .containsExactlyInAnyOrder(
                        new StoredSkill("regeneracja", 3),
                        new StoredSkill("blyskawica", 1));
    }

    @Test
    void aSaveThatCarriesNoSkillListLeavesTheSkillsAlone() {
        // Null means "nothing was learned since last time", which is every save
        // made by somebody merely fighting. Reading it as "they know nothing"
        // would unlearn a character one round at a time.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        characters.save(withSkills("ala", List.of(new StoredSkill("regeneracja", 2))));

        characters.save(withSkills("ala", null));

        assertThat(characters.find("ala").orElseThrow().skills()).hasSize(1);
    }

    @Test
    void skillPointsSurviveTheRoundTrip() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        characters.save(new ActorSnapshot("ala", "Ala", "starter", 7, 11, "LEFT", 4, 900L, 40, 0L,
                Attributes.FRESH, 0, null, "mag", 7, null, null, 0L, null, null));

        assertThat(characters.find("ala").orElseThrow().skillPoints()).isEqualTo(7);
    }

    private static ActorSnapshot withSkills(String nameKey, List<StoredSkill> skills) {
        return new ActorSnapshot(nameKey, "Ala", "starter", 7, 11, "LEFT", 1, 0L, 20, 0L,
                Attributes.FRESH, 0, null, "mag", 1, skills, null, 0L, null, null);
    }

    @Test
    void attributesAndUnspentPointsSurviveTheRoundTrip() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        characters.save(new ActorSnapshot("ala", "Ala", "starter", 7, 11, "LEFT", 4, 900L, 40, 0L,
                new Attributes(11, 6, 5), 2, null, "mag", 4, null, null, 0L, null, null));

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

    // ---- the chests --------------------------------------------------

    @Test
    void aChestSurvivesTheRoundTrip() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        characters.save(withDeposit("ala", new Deposit(3,
                List.of(new StoredDeposit("miecz-1", "zardzewialy-miecz", 2)),
                List.of(new StoredCoin("zloto", 640)))));

        Deposit found = characters.find("ala").orElseThrow().deposit();
        assertThat(found.tabs()).isEqualTo(3);
        assertThat(found.items())
                .as("in the tab it was left in, not merely somewhere in the chest")
                .containsExactly(new StoredDeposit("miecz-1", "zardzewialy-miecz", 2));
        assertThat(found.coins()).containsExactly(new StoredCoin("zloto", 640));
    }

    @Test
    void whatIsInTheChestIsNotInTheBag() {
        // The two share a table and are told apart by one column. If the bag
        // query forgot to say so, everything stored would come back worn or
        // carried - and the character would appear to own it twice.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        characters.save(withDeposit("ala", new Deposit(1,
                List.of(new StoredDeposit("miecz-1", "zardzewialy-miecz", 0)), List.of())));

        assertThat(characters.find("ala").orElseThrow().items()).isEmpty();
        assertThat(characters.itemsOf("ala")).isEmpty();
    }

    @Test
    void writingTheBagDoesNotEmptyTheChest() {
        // The delete behind a bag write is narrowed to the rows in the bag. A
        // missing WHERE clause here would empty somebody's storage every time
        // they picked something up.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        characters.save(withDeposit("ala", new Deposit(1,
                List.of(new StoredDeposit("miecz-1", "zardzewialy-miecz", 0)), List.of())));

        characters.save(snapshot("ala", List.of(new StoredItem("kij-1", "kostur-ucznia", null))));

        SavedCharacter found = characters.find("ala").orElseThrow();
        assertThat(found.items()).hasSize(1);
        assertThat(found.deposit().items())
                .containsExactly(new StoredDeposit("miecz-1", "zardzewialy-miecz", 0));
    }

    @Test
    void writingTheChestDoesNotEmptyTheBag() {
        // And the same in the other direction: the delete behind a chest write
        // is narrowed to the stored rows, or putting one thing away would take
        // everything the character was carrying with it.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        characters.save(snapshot("ala", List.of(new StoredItem("kij-1", "kostur-ucznia", null))));

        characters.save(withDeposit("ala", new Deposit(1,
                List.of(new StoredDeposit("miecz-1", "zardzewialy-miecz", 0)), List.of())));

        assertThat(characters.find("ala").orElseThrow().items())
                .containsExactly(new StoredItem("kij-1", "kostur-ucznia", null));
    }

    @Test
    void aChestNobodyHasOpenedIsOneEmptyTab() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));

        Deposit found = characters.find("ala").orElseThrow().deposit();

        assertThat(found.tabs()).isEqualTo(1);
        assertThat(found.items()).isEmpty();
        assertThat(found.coins()).isEmpty();
    }

    @Test
    void theAccountChestIsSharedByEveryCharacterOnIt() {
        // The whole reason it is not keyed by character: what one of them puts
        // away, the next one finds.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        characters.create(ala, character("Ola", 3, 3, Direction.DOWN));

        characters.save(withAccountDeposit("ala", ala, new Deposit(2,
                List.of(new StoredDeposit("skora-1", "niedzwiedzia-skora", 1)),
                List.of(new StoredCoin("zloto", 12)))));

        Deposit fromTheOther = characters.find("ola").orElseThrow().accountDeposit();
        assertThat(fromTheOther.tabs()).isEqualTo(2);
        assertThat(fromTheOther.items())
                .containsExactly(new StoredDeposit("skora-1", "niedzwiedzia-skora", 1));
        assertThat(fromTheOther.coins()).containsExactly(new StoredCoin("zloto", 12));
    }

    @Test
    void andNotByAnotherAccount() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        characters.create(bogumil, character("Bogumil", 4, 4, Direction.DOWN));

        characters.save(withAccountDeposit("ala", ala, new Deposit(1,
                List.of(new StoredDeposit("skora-1", "niedzwiedzia-skora", 0)), List.of())));

        assertThat(characters.find("bogumil").orElseThrow().accountDeposit().items()).isEmpty();
    }

    @Test
    void anAccountChestIsWrittenTheFirstTimeAnythingGoesIntoIt() {
        // There is no row until something is bought or stored, the same way
        // there is no currency row until somebody earns some.
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        assertThat(characters.accountDepositOf(ala).tabs()).isEqualTo(1);

        characters.save(withAccountDeposit("ala", ala, new Deposit(4, List.of(), List.of())));

        assertThat(characters.accountDepositOf(ala).tabs()).isEqualTo(4);
    }

    @Test
    void nullLeavesBothChestsAlone() {
        characters.create(ala, character("Ala", 7, 11, Direction.LEFT));
        characters.save(withDeposit("ala", new Deposit(2,
                List.of(new StoredDeposit("miecz-1", "zardzewialy-miecz", 1)), List.of())));

        characters.save(new ActorSnapshot("ala", "Ala", "starter", 8, 8, "UP", 1, 0L, 20, 0L,
                Attributes.FRESH, 0, null, "wojownik", 1, null, null, ala, null, null));

        assertThat(characters.find("ala").orElseThrow().deposit().items()).hasSize(1);
    }

    private static ActorSnapshot withDeposit(String nameKey, Deposit deposit) {
        return new ActorSnapshot(nameKey, "Ala", "starter", 7, 11, "LEFT", 1, 0L, 20, 0L,
                Attributes.FRESH, 0, null, "wojownik", 1, null, null, 0L, deposit, null);
    }

    private static ActorSnapshot withAccountDeposit(String nameKey, long accountId,
                                                    Deposit deposit) {
        return new ActorSnapshot(nameKey, "Ala", "starter", 7, 11, "LEFT", 1, 0L, 20, 0L,
                Attributes.FRESH, 0, null, "wojownik", 1, null, null, accountId, null, deposit);
    }

    private static ActorSnapshot snapshot(String nameKey, List<StoredItem> items) {
        return new ActorSnapshot(nameKey, "Ala", "starter", 7, 11, "LEFT", 1, 0L, 20, 0L,
                Attributes.FRESH, 0, items, "wojownik", 1, null, null, 0L, null, null);
    }

    @Test
    void savingACharacterThatIsNotThereCreatesNothing() {
        // The world must not be able to invent a character with no owner, even
        // if one is deleted while it is being played.
        characters.save(new ActorSnapshot("widmo", "Widmo", "starter", 1, 1, "DOWN", 1, 0L, 10, 0L,
                Attributes.FRESH, 0, null, "wojownik", 1, null, null, 0L, null, null));

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
