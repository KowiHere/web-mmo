package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDefLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One character from an account may be in the world at a time.
 *
 * <p>It reads like a rule about fairness, and it is one; but it is also what
 * makes an account's own storage safe to hold in memory. Two characters of one
 * account, on two maps, would be two map threads writing the same chest - and
 * this world has no lock to offer them.
 *
 * <p>No maps are started here: claiming an account is a signpost, like the
 * routing table beside it, and is deliberately independent of whether anything
 * is running.
 */
class OneCharacterPerAccountTest {

    private static final long ACCOUNT = 7L;

    private final WorldService world = new WorldService(
            new MapDefLoader(new MobDefLoader()), new MobDefLoader(), new ItemDefLoader(),
            new ClassDefLoader(), new ObjectMapper(), WorldPersistence.NONE, PartyBoard.NONE);

    @Test
    void theFirstSocketGetsTheAccount() {
        assertThat(world.claim(ACCOUNT, new Nobody("pierwsza"))).isTrue();
    }

    @Test
    void andTheSecondDoesNot() {
        world.claim(ACCOUNT, new Nobody("pierwsza"));

        assertThat(world.claim(ACCOUNT, new Nobody("druga"))).isFalse();
    }

    @Test
    void anotherAccountIsNotAffected() {
        world.claim(ACCOUNT, new Nobody("pierwsza"));

        assertThat(world.claim(ACCOUNT + 1, new Nobody("obca"))).isTrue();
    }

    @Test
    void theSameSocketAskingTwiceIsNotItsOwnRival() {
        Client client = new Nobody("pierwsza");
        world.claim(ACCOUNT, client);

        assertThat(world.claim(ACCOUNT, client)).isTrue();
    }

    @Test
    void lettingGoOfTheSocketLetsGoOfTheAccount() {
        Client first = new Nobody("pierwsza");
        world.claim(ACCOUNT, first);

        world.forget(first);

        assertThat(world.claim(ACCOUNT, new Nobody("druga")))
                .as("otherwise one crashed tab locks a player out until the server restarts")
                .isTrue();
    }

    @Test
    void aRefusedSocketClosingDoesNotEvictThePlayerWhoIsActuallyOn() {
        // The refused socket is closed by the handler the moment it is turned
        // away, and closing forgets it. If that gave the account back, every
        // second login attempt would quietly unlock the first.
        Client playing = new Nobody("pierwsza");
        Client turnedAway = new Nobody("druga");
        world.claim(ACCOUNT, playing);
        world.claim(ACCOUNT, turnedAway);

        world.forget(turnedAway);

        assertThat(world.claim(ACCOUNT, new Nobody("trzecia"))).isFalse();
    }

    /**
     * A socket that does nothing. Deliberately a class and not a record: a
     * client is identified by being itself, and two records with the same label
     * would be equal - which is exactly the confusion this rule must not have.
     */
    private static final class Nobody implements Client {
        private final String name;

        Nobody(String name) {
            this.name = name;
        }

        @Override public void send(String json) { }
        @Override public void disconnect(String reason) { }
        @Override public String describe() { return name; }
    }
}
