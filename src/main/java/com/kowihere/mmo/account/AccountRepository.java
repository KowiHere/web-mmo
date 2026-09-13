package com.kowihere.mmo.account;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByLoginKey(String loginKey) {
        List<Account> found = jdbc.query(
                "SELECT id, login_key, login, password_hash, failed_logins, locked_until"
                        + " FROM account WHERE login_key = ?",
                (rs, row) -> {
                    Timestamp locked = rs.getTimestamp("locked_until");
                    return new Account(
                            rs.getLong("id"),
                            rs.getString("login_key"),
                            rs.getString("login"),
                            rs.getString("password_hash"),
                            rs.getInt("failed_logins"),
                            locked == null ? null : locked.toInstant());
                },
                loginKey);
        return found.stream().findFirst();
    }

    /**
     * @throws ApiException when the login is taken. The unique constraint is the
     *         authority here rather than a prior SELECT: two registrations
     *         racing would both pass the check and only the database would
     *         notice.
     */
    public long create(String loginKey, String login, String passwordHash) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO account (login_key, login, password_hash, created_at, failed_logins)"
                                + " VALUES (?, ?, ?, ?, 0)",
                        Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, loginKey);
                statement.setString(2, login);
                statement.setString(3, passwordHash);
                statement.setTimestamp(4, Timestamp.from(Instant.now()));
                return statement;
            }, keys);
        } catch (DuplicateKeyException e) {
            throw ApiException.badRequest("Ten login jest już zajęty.");
        }
        Number id = (Number) keys.getKeys().get("ID");
        return id == null ? keys.getKey().longValue() : id.longValue();
    }

    public void recordFailure(long accountId, int failedLogins, Instant lockedUntil) {
        jdbc.update("UPDATE account SET failed_logins = ?, locked_until = ? WHERE id = ?",
                failedLogins, lockedUntil == null ? null : Timestamp.from(lockedUntil), accountId);
    }

    public void clearFailures(long accountId) {
        jdbc.update("UPDATE account SET failed_logins = 0, locked_until = NULL WHERE id = ?", accountId);
    }
}
