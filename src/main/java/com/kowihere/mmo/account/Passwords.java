package com.kowihere.mmo.account;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Password policy and hashing in one place.
 *
 * <p>Two of the rules here exist because BCrypt fails quietly rather than
 * loudly, and a quiet failure in a password is the worst kind.
 */
@Component
public class Passwords {

    public static final int MIN_LENGTH = 8;

    /**
     * BCrypt hashes at most 72 bytes and silently ignores the rest. A longer
     * password would be accepted, stored truncated, and leave its owner
     * believing it is stronger than it is - so it is refused instead.
     */
    public static final int MAX_BYTES = 72;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * A hash of nothing anyone can log in with, matched against when a login
     * does not exist so that the reply takes about as long either way. Without
     * it, response time alone tells an attacker which logins are real.
     */
    private final String decoyHash = encoder.encode("this matches no account");

    public void requireAcceptable(String raw) {
        if (raw == null || raw.length() < MIN_LENGTH) {
            throw ApiException.badRequest("Hasło musi mieć co najmniej " + MIN_LENGTH + " znaków.");
        }
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.badRequest(
                    "Hasło jest za długie (maksymalnie " + MAX_BYTES + " bajtów). "
                            + "Dłuższego nie da się bezpiecznie zapisać w całości.");
        }
    }

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String hash) {
        return raw != null && encoder.matches(raw, hash);
    }

    /** Spends the time a real check would have taken, for a login that does not exist. */
    public void spendTimeAsIfChecking(String raw) {
        encoder.matches(raw == null ? "" : raw, decoyHash);
    }
}
