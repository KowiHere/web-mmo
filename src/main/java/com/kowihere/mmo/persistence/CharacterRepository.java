package com.kowihere.mmo.persistence;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.loop.ActorSnapshot;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.loop.StoredItem;
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
            "SELECT name_key, name, map_id, x, y, dir, level, xp, hp, weakened_until,"
                    + " strength, agility, intellect, unspent_points, class_id"
                    + " FROM game_character";

    /**
     * A character and everything it owns. Two queries rather than a join: a join
     * would repeat the character on every item row, and this runs on a network
     * thread before somebody enters the world, never in a tick.
     */
    public Optional<SavedCharacter> find(String nameKey) {
        return jdbc.query(SELECT + " WHERE name_key = ?", CharacterRepository::read, nameKey)
                .stream().findFirst()
                .map(character -> withItems(character, itemsOf(nameKey)));
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
                        + " WHERE character_key = ? ORDER BY created_at, id",
                (rs, row) -> new StoredItem(rs.getString("id"), rs.getString("def_id"),
                        ItemSlot.parse(rs.getString("slot"))),
                nameKey);
    }

    private static SavedCharacter withItems(SavedCharacter character, List<StoredItem> items) {
        return new SavedCharacter(character.nameKey(), character.name(), character.mapId(),
                character.x(), character.y(), character.dir(), character.level(), character.xp(),
                character.hp(), character.weakenedUntil(), character.attributes(),
                character.unspentPoints(), items, character.classId());
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
                        + " hp, class_id, strength, agility, intellect)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                character.nameKey(), character.name(), character.mapId(),
                character.x(), character.y(), character.dir().name(),
                Timestamp.from(Instant.now()), accountId,
                character.level(), character.xp(), character.hp(), character.classId(),
                character.attributes().strength(), character.attributes().agility(),
                character.attributes().intellect());
    }

    private static SavedCharacter read(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp weakened = rs.getTimestamp("weakened_until");
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
                weakened == null ? 0L : weakened.getTime(),
                new Attributes(rs.getInt("strength"), rs.getInt("agility"), rs.getInt("intellect")),
                rs.getInt("unspent_points"),
                List.of(),
                rs.getString("class_id"));
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
                        + " level = ?, xp = ?, hp = ?, weakened_until = ?,"
                        + " strength = ?, agility = ?, intellect = ?, unspent_points = ?,"
                        + " class_id = ?"
                        + " WHERE name_key = ?",
                snapshot.mapId(), snapshot.x(), snapshot.y(), snapshot.dir(),
                Timestamp.from(Instant.now()),
                snapshot.level(), snapshot.xp(), snapshot.hp(),
                snapshot.weakenedUntil() <= 0 ? null : new Timestamp(snapshot.weakenedUntil()),
                snapshot.attributes().strength(), snapshot.attributes().agility(),
                snapshot.attributes().intellect(), snapshot.unspentPoints(), snapshot.classId(),
                snapshot.nameKey());
        if (updated == 0) {
            log.warn("No character row for '{}'; its position was not saved", snapshot.nameKey());
            return;
        }
        if (snapshot.items() != null) {
            replaceItems(snapshot.nameKey(), snapshot.items());
        }
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
        jdbc.update("DELETE FROM item_instance WHERE character_key = ?", nameKey);
        if (items.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.batchUpdate("INSERT INTO item_instance (id, character_key, def_id, slot, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
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
