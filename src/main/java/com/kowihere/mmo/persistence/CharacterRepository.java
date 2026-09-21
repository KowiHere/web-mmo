package com.kowihere.mmo.persistence;

import com.kowihere.mmo.loop.ActorSnapshot;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.world.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
            "SELECT name_key, name, map_id, x, y, dir, level, xp, hp, weakened_until"
                    + " FROM game_character";

    public Optional<SavedCharacter> find(String nameKey) {
        return jdbc.query(SELECT + " WHERE name_key = ?", CharacterRepository::read, nameKey)
                .stream().findFirst();
    }

    /** Every character on one account, for the selection screen. */
    public List<SavedCharacter> findByAccount(long accountId) {
        return jdbc.query(SELECT + " WHERE account_id = ? ORDER BY name", CharacterRepository::read, accountId);
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
                        + " (name_key, name, map_id, x, y, dir, last_seen, account_id, level, xp, hp)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                character.nameKey(), character.name(), character.mapId(),
                character.x(), character.y(), character.dir().name(),
                Timestamp.from(Instant.now()), accountId,
                character.level(), character.xp(), character.hp());
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
                weakened == null ? 0L : weakened.getTime());
    }

    /**
     * Writes a position back. Update only, never insert: a character now has an
     * owner, and the world has no business inventing one. A row that is not
     * there means the character was deleted while it was being played, which is
     * worth a line in the log rather than a resurrection.
     */
    public void save(ActorSnapshot snapshot) {
        int updated = jdbc.update(
                "UPDATE game_character SET map_id = ?, x = ?, y = ?, dir = ?, last_seen = ?,"
                        + " level = ?, xp = ?, hp = ?, weakened_until = ?"
                        + " WHERE name_key = ?",
                snapshot.mapId(), snapshot.x(), snapshot.y(), snapshot.dir(),
                Timestamp.from(Instant.now()),
                snapshot.level(), snapshot.xp(), snapshot.hp(),
                snapshot.weakenedUntil() <= 0 ? null : new Timestamp(snapshot.weakenedUntil()),
                snapshot.nameKey());
        if (updated == 0) {
            log.warn("No character row for '{}'; its position was not saved", snapshot.nameKey());
        }
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
