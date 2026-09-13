package com.kowihere.mmo.account;

import com.kowihere.mmo.loop.SavedCharacter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Credential handling, against the real database and the real BCrypt.
 *
 * <p>Most of what is checked here is about what the system must <em>not</em>
 * reveal or quietly accept. Those are the failures that never show up as a bug
 * report, because from the outside they look like everything working.
 */
@SpringBootTest
@Transactional
class AccountServiceTest {

    private static final String PASSWORD = "wystarczajaco-dlugie";

    @Autowired
    private AccountService accounts;

    @Test
    void registrationCreatesAnAccountAndItsFirstCharacter() {
        String token = accounts.register("kowi", PASSWORD, "Ala");

        long accountId = accounts.accountFor(token).orElseThrow();
        List<SavedCharacter> characters = accounts.charactersOf(accountId);

        assertThat(characters).extracting(SavedCharacter::name).containsExactly("Ala");
        assertThat(characters.get(0).mapId()).isEqualTo("starter");
    }

    @Test
    void aTakenLoginIsRefusedWhateverTheCase() {
        accounts.register("kowi", PASSWORD, "Ala");

        assertThatThrownBy(() -> accounts.register("KOWI", PASSWORD, "Inna"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("zajęty");
    }

    @Test
    void aTakenCharacterNameIsRefused() {
        accounts.register("kowi", PASSWORD, "Ala");
        String token = accounts.register("ktos", PASSWORD, "Bob");
        long other = accounts.accountFor(token).orElseThrow();

        assertThatThrownBy(() -> accounts.createCharacter(other, "ala"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("już istnieje");
    }

    @Test
    void aShortPasswordIsRefused() {
        assertThatThrownBy(() -> accounts.register("kowi", "krotkie", "Ala"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("8 znaków");
    }

    @Test
    void aPasswordLongerThanBcryptCanHashIsRefused() {
        // BCrypt hashes at most 72 bytes and ignores the rest in silence. The
        // account would work, the owner would believe the tail mattered, and
        // nothing would ever say otherwise - so it has to be refused loudly.
        String tooLong = "a".repeat(Passwords.MAX_BYTES + 1);

        assertThatThrownBy(() -> accounts.register("kowi", tooLong, "Ala"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("za długie");
    }

    @Test
    void wrongPasswordAndUnknownLoginCannotBeToldApart() {
        // If these differ, the login form becomes a way of discovering which
        // accounts exist - which is half of what an attacker needs.
        accounts.register("kowi", PASSWORD, "Ala");

        String wrongPassword = messageFrom(() -> accounts.login("kowi", "zupelnie-inne-haslo"));
        String unknownLogin = messageFrom(() -> accounts.login("nikt-taki", "zupelnie-inne-haslo"));

        assertThat(wrongPassword).isEqualTo(unknownLogin);
    }

    @Test
    void theRightPasswordOpensASession() {
        accounts.register("kowi", PASSWORD, "Ala");

        String token = accounts.login("KOWI", PASSWORD); // login is case-folded

        assertThat(accounts.accountFor(token)).isPresent();
    }

    @Test
    void enoughWrongGuessesLockTheAccount() {
        accounts.register("kowi", PASSWORD, "Ala");
        for (int attempt = 0; attempt < 10; attempt++) {
            messageFrom(() -> accounts.login("kowi", "zle-haslo-numer"));
        }

        // Even the correct password is refused while the lock holds.
        assertThatThrownBy(() -> accounts.login("kowi", PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("zablokowane");
    }

    @Test
    void loggingOutInvalidatesTheSession() {
        String token = accounts.register("kowi", PASSWORD, "Ala");
        assertThat(accounts.accountFor(token)).isPresent();

        accounts.logout(token);

        assertThat(accounts.accountFor(token)).isEmpty();
    }

    @Test
    void anInventedTokenIsWorthNothing() {
        assertThat(accounts.accountFor("nie-ma-takiego-tokenu")).isEmpty();
        assertThat(accounts.accountFor("")).isEmpty();
        assertThat(accounts.accountFor(null)).isEmpty();
    }

    @Test
    void anAccountOwnsOnlyItsOwnCharacters() {
        // The check the WebSocket handshake leans on. Being logged in is not the
        // same as being entitled to a particular character, and conflating the
        // two is how one player ends up walking around as another.
        long mine = accounts.accountFor(accounts.register("kowi", PASSWORD, "Ala")).orElseThrow();
        long theirs = accounts.accountFor(accounts.register("ktos", PASSWORD, "Bob")).orElseThrow();

        assertThat(accounts.owns(mine, "ala")).isTrue();
        assertThat(accounts.owns(mine, "bob")).isFalse();
        assertThat(accounts.owns(theirs, "ala")).isFalse();
        assertThat(accounts.owns(mine, "nie-ma-takiej")).isFalse();
        assertThat(accounts.owns(mine, null)).isFalse();
    }

    @Test
    void aSecondCharacterJoinsTheFirstOnTheSameAccount() {
        long accountId = accounts.accountFor(accounts.register("kowi", PASSWORD, "Ala")).orElseThrow();

        accounts.createCharacter(accountId, "Bogumił");

        assertThat(accounts.charactersOf(accountId))
                .extracting(SavedCharacter::name)
                .containsExactlyInAnyOrder("Ala", "Bogumił");
    }

    @Test
    void aCharacterNameFullOfPunctuationIsRefusedRatherThanCleanedUp() {
        // PlayerNames.sanitise exists to keep the world safe from whatever
        // arrives. At registration the player should be told, not corrected
        // into owning a character they did not ask for.
        assertThatThrownBy(() -> accounts.register("kowi", PASSWORD, "!!!"))
                .isInstanceOf(ApiException.class);
    }

    private static String messageFrom(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected the call to be refused");
        } catch (ApiException e) {
            return e.getMessage();
        }
    }
}
