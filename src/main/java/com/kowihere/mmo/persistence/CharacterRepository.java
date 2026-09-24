package com.kowihere.mmo.persistence;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.loop.ActorSnapshot;
import com.kowihere.mmo.loop.Deposit;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.loop.StoredCoin;
import com.kowihere.mmo.loop.StoredDeposit;
import com.kowihere.mmo.loop.StoredItem;
import com.kowihere.mmo.loop.StoredSkill;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The only class that knows SQL. Called from network threads (a lookup before a
 * player enters) and from the persistence writer thread - never from a map
 * thread, which must not wait on a database for any reason.
 */
@Repository
public class CharacterRepository {

    private static final Logger log = LoggerFactory.getLogger(CharacterRepository.class);

    private final JdbcTemplate jdbc;

    public CharacterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT =
            "SELECT name_key, name, map_id, x, y, dir, level, xp, hp, wakes_at,"
                    + " strength, agility, intellect, unspent_points, class_id, skill_points"
                    + " FROM game_character";

    /**
     * A character and everything it owns. Two queries rather than a join: a join
     * would repeat the character on every item row, and this runs on a network
     * thread before somebody enters the world, never in a tick.
     */
    public Optional<SavedCharacter> find(String nameKey) {
        return jdbc.query(SELECT + " WHERE name_key = ?", CharacterRepository::read, nameKey)
                .stream().findFirst()
                .map(character -> withBelongings(character, itemsOf(nameKey), skillsOf(nameKey),
                        coinsOf(nameKey), depositOf(nameKey),
                        accountDepositOf(ownerOf(nameKey).orElse(0L))));
    }

    /**
     * Every character on one account, for the selection screen. Without their
     * items: the screen shows names, and loading five bags to draw a list would
     * be five queries for nothing.
     */
    public List<SavedCharacter> findByAccount(long accountId) {
        return jdbc.query(SELECT + " WHERE account_id = ? ORDER BY name", CharacterRepository::read, accountId);
    }

    public List<StoredItem> itemsOf(String nameKey) {
        return jdbc.query("SELECT id, def_id, slot FROM item_instance"
                        + " WHERE character_key = ? AND tab IS NULL ORDER BY created_at, id",
                (rs, row) -> new StoredItem(rs.getString("id"), rs.getString("def_id"),
                        ItemSlot.parse(rs.getString("slot"))),
                nameKey);
    }

    /**
     * A character's own chest. The same table as the bag, told apart by the one
     * column that says which tab a thing is in: an item in storage is the same
     * instance as an item in a bag, and moving it between two tables would be
     * moving the same truth between two homes.
     */
    public Deposit depositOf(String nameKey) {
        List<StoredDeposit> items = jdbc.query(
                "SELECT id, def_id, tab FROM item_instance"
                        + " WHERE character_key = ? AND tab IS NOT NULL ORDER BY created_at, id",
                (rs, row) -> new StoredDeposit(rs.getString("id"), rs.getString("def_id"),
                        rs.getInt("tab")),
                nameKey);
        List<StoredCoin> coins = jdbc.query(
                "SELECT currency_id, amount FROM storage_currency WHERE character_key = ?",
                (rs, row) -> new StoredCoin(rs.getString("currency_id"), rs.getInt("amount")),
                nameKey);
        Integer tabs = jdbc.query("SELECT storage_tabs FROM game_character WHERE name_key = ?",
                (rs, row) -> rs.getInt("storage_tabs"), nameKey).stream().findFirst().orElse(1);
        return new Deposit(tabs, items, coins);
    }

    /**
     * The chest every character on one account shares.
     *
     * <p>A missing {@code account_storage} row means one tab, the same way a
     * missing currency row means no money: nothing is written until something
     * is bought.
     */
    public Deposit accountDepositOf(long accountId) {
        List<StoredDeposit> items = jdbc.query(
                "SELECT id, def_id, tab FROM account_item"
                        + " WHERE account_id = ? ORDER BY created_at, id",
                (rs, row) -> new StoredDeposit(rs.getString("id"), rs.getString("def_id"),
                        rs.getInt("tab")),
                accountId);
        List<StoredCoin> coins = jdbc.query(
                "SELECT currency_id, amount FROM account_currency WHERE account_id = ?",
                (rs, row) -> new StoredCoin(rs.getString("currency_id"), rs.getInt("amount")),
                accountId);
        Integer tabs = jdbc.query("SELECT tabs FROM account_storage WHERE account_id = ?",
                (rs, row) -> rs.getInt("tabs"), accountId).stream().findFirst().orElse(1);
        return new Deposit(tabs, items, coins);
    }

    public List<StoredCoin> coinsOf(String nameKey) {
        return jdbc.query("SELECT currency_id, amount FROM character_currency"
                        + " WHERE character_key = ?",
                (rs, row) -> new StoredCoin(rs.getString("currency_id"), rs.getInt("amount")),
                nameKey);
    }

    public List<StoredSkill> skillsOf(String nameKey) {
        return jdbc.query("SELECT skill_id, rank FROM character_skill"
                        + " WHERE character_key = ? ORDER BY skill_id",
                (rs, row) -> new StoredSkill(rs.getString("skill_id"), rs.getInt("rank")),
                nameKey);
    }

    private static SavedCharacter withBelongings(SavedCharacter character, List<StoredItem> items,
                                                 List<StoredSkill> skills, List<StoredCoin> coins,
                                                 Deposit deposit, Deposit accountDeposit) {
        return new SavedCharacter(character.nameKey(), character.name(), character.mapId(),
                character.x(), character.y(), character.dir(), character.level(), character.xp(),
                character.hp(), character.wakesAt(), character.attributes(),
                character.unspentPoints(), items, character.classId(),
                character.skillPoints(), skills, coins, deposit, accountDeposit);
    }

    /**
     * Who may play this character. The answer comes from the database rather
     * than from anything the client said - this is the check that stops one
     * account entering the world as another account's character.
     */
    public Optional<Long> ownerOf(String nameKey) {
        return jdbc.query("SELECT account_id FROM game_character WHERE name_key = ?",
                (rs, row) -> rs.getLong("account_id"), nameKey).stream().findFirst();
    }

    /** @throws org.springframework.dao.DuplicateKeyException if the name is taken */
    public void create(long accountId, SavedCharacter character) {
        jdbc.update("INSERT INTO game_character"
                        + " (name_key, name, map_id, x, y, dir, last_seen, account_id, level, xp,"
                        + " hp, class_id, strength, agility, intellect, skill_points)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                character.nameKey(), character.name(), character.mapId(),
                character.x(), character.y(), character.dir().name(),
                Timestamp.from(Instant.now()), accountId,
                character.level(), character.xp(), character.hp(), character.classId(),
                character.attributes().strength(), character.attributes().agility(),
                character.attributes().intellect(), character.skillPoints());
    }

    private static SavedCharacter read(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp wakesAt = rs.getTimestamp("wakes_at");
        return new SavedCharacter(
                rs.getString("name_key"),
                rs.getString("name"),
                rs.getString("map_id"),
                rs.getInt("x"),
                rs.getInt("y"),
                direction(rs.getString("dir")),
                rs.getInt("level"),
                rs.getLong("xp"),
                rs.getInt("hp"),
                wakesAt == null ? 0L : wakesAt.getTime(),
                new Attributes(rs.getInt("strength"), rs.getInt("agility"), rs.getInt("intellect")),
                rs.getInt("unspent_points"),
                List.of(),
                rs.getString("class_id"),
                rs.getInt("skill_points"),
                List.of(),
                List.of(),
                // Both chests are read separately, by the two queries beside
                // this one. A row on the selection screen carries neither.
                null,
                null);
    }

    /**
     * Writes a position back. Update only, never insert: a character now has an
     * owner, and the world has no business inventing one. A row that is not
     * there means the character was deleted while it was being played, which is
     * worth a line in the log rather than a resurrection.
     */
    @Transactional
    public void save(ActorSnapshot snapshot) {
        int updated = jdbc.update(
                "UPDATE game_character SET map_id = ?, x = ?, y = ?, dir = ?, last_seen = ?,"
                        + " level = ?, xp = ?, hp = ?, wakes_at = ?,"
                        + " strength = ?, agility = ?, intellect = ?, unspent_points = ?,"
                        + " class_id = ?, skill_points = ?"
                        + " WHERE name_key = ?",
                snapshot.mapId(), snapshot.x(), snapshot.y(), snapshot.dir(),
                Timestamp.from(Instant.now()),
                snapshot.level(), snapshot.xp(), snapshot.hp(),
                snapshot.wakesAt() <= 0 ? null : new Timestamp(snapshot.wakesAt()),
                snapshot.attributes().strength(), snapshot.attributes().agility(),
                snapshot.attributes().intellect(), snapshot.unspentPoints(), snapshot.classId(),
                snapshot.skillPoints(),
                snapshot.nameKey());
        if (updated == 0) {
            log.warn("No character row for '{}'; its position was not saved", snapshot.nameKey());
            return;
        }
        if (snapshot.items() != null) {
            replaceItems(snapshot.nameKey(), snapshot.items());
        }
        if (snapshot.skills() != null) {
            replaceSkills(snapshot.nameKey(), snapshot.skills());
        }
        if (snapshot.coins() != null) {
            replaceCoins(snapshot.nameKey(), snapshot.coins());
        }
        if (snapshot.deposit() != null) {
            replaceDeposit(snapshot.nameKey(), snapshot.deposit());
        }
        if (snapshot.accountDeposit() != null) {
            replaceAccountDeposit(snapshot.accountId(), snapshot.accountDeposit());
        }
    }

    /**
     * A character's chest, replaced whole like everything else that is written
     * rarely. The delete is narrowed to the stored rows so that it cannot take
     * the bag with it - the two live in one table and are told apart by a
     * column, which makes this WHERE clause load-bearing.
     */
    private void replaceDeposit(String nameKey, Deposit deposit) {
        jdbc.update("UPDATE game_character SET storage_tabs = ? WHERE name_key = ?",
                deposit.tabs(), nameKey);
        jdbc.update("DELETE FROM item_instance WHERE character_key = ? AND tab IS NOT NULL",
                nameKey);
        Timestamp now = Timestamp.from(Instant.now());
        if (!deposit.items().isEmpty()) {
            jdbc.batchUpdate("INSERT INTO item_instance"
                            + " (id, character_key, def_id, slot, tab, created_at)"
                            + " VALUES (?, ?, ?, NULL, ?, ?)",
                    deposit.items().stream()
                            .map(item -> new Object[]{item.id(), nameKey, item.defId(),
                                    item.tab(), now})
                            .toList());
        }
        jdbc.update("DELETE FROM storage_currency WHERE character_key = ?", nameKey);
        if (!deposit.coins().isEmpty()) {
            jdbc.batchUpdate("INSERT INTO storage_currency (character_key, currency_id, amount)"
                            + " VALUES (?, ?, ?)",
                    deposit.coins().stream()
                            .map(coin -> new Object[]{nameKey, coin.currencyId(), coin.amount()})
                            .toList());
        }
    }

    /** And the account's, which has a table to itself because it outlives them all. */
    private void replaceAccountDeposit(long accountId, Deposit deposit) {
        int updated = jdbc.update("UPDATE account_storage SET tabs = ? WHERE account_id = ?",
                deposit.tabs(), accountId);
        if (updated == 0) {
            jdbc.update("INSERT INTO account_storage (account_id, tabs) VALUES (?, ?)",
                    accountId, deposit.tabs());
        }
        jdbc.update("DELETE FROM account_item WHERE account_id = ?", accountId);
        Timestamp now = Timestamp.from(Instant.now());
        if (!deposit.items().isEmpty()) {
            jdbc.batchUpdate("INSERT INTO account_item (id, account_id, def_id, tab, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    deposit.items().stream()
                            .map(item -> new Object[]{item.id(), accountId, item.defId(),
                                    item.tab(), now})
                            .toList());
        }
        jdbc.update("DELETE FROM account_currency WHERE account_id = ?", accountId);
        if (!deposit.coins().isEmpty()) {
            jdbc.batchUpdate("INSERT INTO account_currency (account_id, currency_id, amount)"
                            + " VALUES (?, ?, ?)",
                    deposit.coins().stream()
                            .map(coin -> new Object[]{accountId, coin.currencyId(), coin.amount()})
                            .toList());
        }
    }

    /** And again for money, which changes on every kill and every purchase. */
    private void replaceCoins(String nameKey, List<StoredCoin> coins) {
        jdbc.update("DELETE FROM character_currency WHERE character_key = ?", nameKey);
        if (coins.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO character_currency (character_key, currency_id, amount)"
                        + " VALUES (?, ?, ?)",
                coins.stream()
                        .map(coin -> new Object[]{nameKey, coin.currencyId(), coin.amount()})
                        .toList());
    }

    /** The same replace-the-lot as items, for the same reasons and just as rarely. */
    private void replaceSkills(String nameKey, List<StoredSkill> skills) {
        jdbc.update("DELETE FROM character_skill WHERE character_key = ?", nameKey);
        if (skills.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO character_skill (character_key, skill_id, rank)"
                        + " VALUES (?, ?, ?)",
                skills.stream()
                        .map(skill -> new Object[]{nameKey, skill.skillId(), skill.rank()})
                        .toList());
    }

    /**
     * Writes a character's items out by replacing the lot.
     *
     * <p>Only when something actually moved: a snapshot carries null for items
     * that have not changed, which is every save made by somebody merely walking
     * about. When they have changed, twenty rows deleted and reinserted inside
     * one transaction is both cheaper and far easier to be sure of than working
     * out the difference - and an item can never be in two places at once, not
     * even for the instant between two statements.
     */
    private void replaceItems(String nameKey, List<StoredItem> items) {
        // The bag only. The same table holds the chest, and a delete that
        // forgot to say so would empty somebody's storage every time they
        // picked a coin purse up off the ground.
        jdbc.update("DELETE FROM item_instance WHERE character_key = ? AND tab IS NULL", nameKey);
        if (items.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.batchUpdate("INSERT INTO item_instance"
                        + " (id, character_key, def_id, slot, tab, created_at)"
                        + " VALUES (?, ?, ?, ?, NULL, ?)",
                items.stream()
                        .map(item -> new Object[]{item.id(), nameKey, item.defId(),
                                item.slot() == null ? null : item.slot().name(), now})
                        .toList());
    }

    private static Direction direction(String stored) {
        try {
            return Direction.valueOf(stored);
        } catch (IllegalArgumentException e) {
            // A row written by a future version, or edited by hand. Facing the
            // wrong way is not worth refusing to let someone play.
            log.warn("Unknown stored direction '{}', defaulting to DOWN", stored);
            return Direction.DOWN;
        }
    }
}
