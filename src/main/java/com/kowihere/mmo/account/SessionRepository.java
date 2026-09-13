package com.kowihere.mmo.account;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SessionRepository {

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void create(String tokenHash, long accountId, Instant expiresAt) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO account_session (token_hash, account_id, created_at, expires_at)"
                        + " VALUES (?, ?, ?, ?)",
                tokenHash, accountId, Timestamp.from(now), Timestamp.from(expiresAt));
    }

    /** @return the account this session belongs to, or empty if it is unknown or expired. */
    public Optional<Long> findLiveAccount(String tokenHash, Instant now) {
        List<Long> found = jdbc.query(
                "SELECT account_id FROM account_session WHERE token_hash = ? AND expires_at > ?",
                (rs, row) -> rs.getLong("account_id"),
                tokenHash, Timestamp.from(now));
        return found.stream().findFirst();
    }

    public void delete(String tokenHash) {
        jdbc.update("DELETE FROM account_session WHERE token_hash = ?", tokenHash);
    }

    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM account_session WHERE expires_at <= ?", Timestamp.from(now));
    }
}
