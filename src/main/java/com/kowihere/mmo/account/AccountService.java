package com.kowihere.mmo.account;

import com.kowihere.mmo.loop.MapRunner;
import com.kowihere.mmo.loop.PlayerNames;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.loop.WorldService;
import com.kowihere.mmo.persistence.CharacterRepository;
import com.kowihere.mmo.world.ClassDef;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything that decides who someone is. Deliberately outside the game loop:
 * none of this runs on a map thread, and none of it is allowed to.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9._-]{3,32}");
    private static final int MAX_CHARACTERS = 5;
    private static final int MAX_FAILURES = 10;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final Duration SESSION_LIFETIME = Duration.ofDays(30);

    /**
     * One message for a login that does not exist and for a wrong password.
     * Telling them apart turns the login form into a way of discovering which
     * accounts are real.
     */
    private static final String REFUSED = "Nieprawidłowy login lub hasło.";

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final CharacterRepository characters;
    private final Passwords passwords;
    private final WorldService world;
    private final Map<String, ClassDef> classes;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accounts, SessionRepository sessions,
                          CharacterRepository characters, Passwords passwords, WorldService world,
                          ClassDefLoader classLoader) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.characters = characters;
        this.passwords = passwords;
        this.world = world;
        this.classes = classLoader.loadAll();
    }

    // ------------------------------------------------------------------ register

    /**
     * Creates the account and its first character together: an account with no
     * character has nothing to do, and leaving the pair half-made would be a
     * state nothing else in the code expects.
     */
    /** Registering without naming a class gets the one everybody falls back to. */
    public String register(String login, String password, String characterName) {
        return register(login, password, characterName, null);
    }

    @Transactional
    public String register(String login, String password, String characterName, String classId) {
        String loginKey = requireValidLogin(login);
        passwords.requireAcceptable(password);
        String name = requireValidCharacterName(characterName);

        long accountId = accounts.create(loginKey, login.strip(), passwords.hash(password));
        createCharacter(accountId, name, classId);
        log.info("Registered account '{}' with character '{}'", loginKey, name);
        return issueSession(accountId);
    }

    // --------------------------------------------------------------------- login

    public String login(String login, String password) {
        if (login == null) {
            throw ApiException.unauthorized(REFUSED);
        }
        Optional<Account> found = accounts.findByLoginKey(login.strip().toLowerCase());
        if (found.isEmpty()) {
            // Spend the time a real check would have taken, so the reply does
            // not reveal whether this login exists.
            passwords.spendTimeAsIfChecking(password);
            throw ApiException.unauthorized(REFUSED);
        }

        Account account = found.get();
        Instant now = Instant.now();
        if (account.isLockedAt(now)) {
            throw ApiException.unauthorized(
                    "Konto jest tymczasowo zablokowane po serii nieudanych prób. Spróbuj za chwilę.");
        }

        if (!passwords.matches(password, account.passwordHash())) {
            int failures = account.failedLogins() + 1;
            Instant lockedUntil = failures >= MAX_FAILURES ? now.plus(LOCKOUT) : null;
            accounts.recordFailure(account.id(), failures, lockedUntil);
            if (lockedUntil != null) {
                log.warn("Locking account '{}' after {} failed attempts", account.loginKey(), failures);
            }
            throw ApiException.unauthorized(REFUSED);
        }

        if (account.failedLogins() > 0) {
            accounts.clearFailures(account.id());
        }
        return issueSession(account.id());
    }

    public void logout(String token) {
        if (token != null) {
            sessions.delete(hash(token));
        }
    }

    /** @return the account behind a session token, or empty if it is unknown or expired. */
    public Optional<Long> accountFor(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return sessions.findLiveAccount(hash(token), Instant.now());
    }

    // ---------------------------------------------------------------- characters

    public List<SavedCharacter> charactersOf(long accountId) {
        return characters.findByAccount(accountId);
    }

    public SavedCharacter createCharacter(long accountId, String rawName) {
        return createCharacter(accountId, rawName, null);
    }

    /**
     * @param rawClassId what the player picked. An unknown one is refused
     *                   rather than quietly replaced: somebody who asked for a
     *                   mage and was handed a warrior would not find out until
     *                   the character was already several levels old.
     */
    public SavedCharacter createCharacter(long accountId, String rawName, String rawClassId) {
        String name = requireValidCharacterName(rawName);
        if (characters.findByAccount(accountId).size() >= MAX_CHARACTERS) {
            throw ApiException.badRequest("Masz już maksymalną liczbę postaci (" + MAX_CHARACTERS + ").");
        }
        ClassDef chosen = requireValidClass(rawClassId);

        MapRunner map = world.startingMap();
        SavedCharacter character = SavedCharacter.fresh(PlayerNames.key(name), name,
                map.mapId(), map.spawnX(), map.spawnY(), Direction.DOWN,
                chosen.id(), chosen.startingAttributes());
        try {
            characters.create(accountId, character);
        } catch (DuplicateKeyException e) {
            // The unique constraint decides, not a prior lookup: two players
            // registering the same name at once would both pass a check.
            throw ApiException.badRequest("Postać o tej nazwie już istnieje.");
        }
        return character;
    }

    /** The classes a player may choose between, in a fixed order. */
    public List<ClassDef> classes() {
        return List.copyOf(classes.values());
    }

    private ClassDef requireValidClass(String rawClassId) {
        if (rawClassId == null || rawClassId.isBlank()) {
            return classes.get(ClassDefLoader.FALLBACK_ID);
        }
        ClassDef chosen = classes.get(rawClassId.strip());
        if (chosen == null) {
            throw ApiException.badRequest("Nie ma takiej klasy postaci.");
        }
        return chosen;
    }

    public boolean owns(long accountId, String characterKey) {
        return characterKey != null && characters.ownerOf(characterKey)
                .filter(owner -> owner == accountId)
                .isPresent();
    }

    // ------------------------------------------------------------------ internals

    private String issueSession(long accountId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.create(hash(token), accountId, Instant.now().plus(SESSION_LIFETIME));
        return token;
    }

    /**
     * Sessions are stored hashed. The token itself only ever exists in the
     * player's cookie, so a copy of the database is not a pile of live logins.
     */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String requireValidLogin(String login) {
        if (login == null || !LOGIN.matcher(login.strip()).matches()) {
            throw ApiException.badRequest(
                    "Login musi mieć 3-32 znaki: litery, cyfry, kropka, myślnik lub podkreślenie.");
        }
        return login.strip().toLowerCase();
    }

    /**
     * Rejects a bad character name rather than quietly cleaning it up.
     * {@link PlayerNames#sanitise} exists to keep the world safe from whatever
     * arrives; at registration the player should be told, not corrected.
     */
    private static String requireValidCharacterName(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.length() < 3 || trimmed.length() > PlayerNames.MAX_LENGTH) {
            throw ApiException.badRequest(
                    "Nazwa postaci musi mieć od 3 do " + PlayerNames.MAX_LENGTH + " znaków.");
        }
        if (!trimmed.equals(PlayerNames.sanitise(trimmed))) {
            throw ApiException.badRequest(
                    "Nazwa postaci może zawierać tylko litery, cyfry, spacje, myślniki i podkreślenia.");
        }
        return trimmed;
    }
}
