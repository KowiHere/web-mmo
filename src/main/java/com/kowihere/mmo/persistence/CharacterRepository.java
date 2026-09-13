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

    public Optional<SavedCharacter> find(String nameKey) {
        List<SavedCharacter> found = jdbc.query(
                "SELECT name, map_id, x, y, dir FROM game_character WHERE name_key = ?",
                (rs, row) -> new SavedCharacter(
                        rs.getString("name"),
                        rs.getString("map_id"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        direction(rs.getString("dir"))),
                nameKey);
        return found.stream().findFirst();
    }

    /**
     * Update-then-insert rather than a single upsert statement.
     *
     * <p>Every database spells upsert differently - H2 has MERGE, PostgreSQL has
     * ON CONFLICT - and this project would rather stay portable than save one
     * round trip on a table written a few times a minute.
     */
    public void save(ActorSnapshot snapshot) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update(
                "UPDATE game_character SET name = ?, map_id = ?, x = ?, y = ?, dir = ?, last_seen = ?"
                        + " WHERE name_key = ?",
                snapshot.name(), snapshot.mapId(), snapshot.x(), snapshot.y(), snapshot.dir(),
                now, snapshot.nameKey());
        if (updated > 0) {
            return;
        }
        jdbc.update(
                "INSERT INTO game_character (name_key, name, map_id, x, y, dir, last_seen)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshot.nameKey(), snapshot.name(), snapshot.mapId(),
                snapshot.x(), snapshot.y(), snapshot.dir(), now);
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
