package com.kowihere.mmo.party;

import com.kowihere.mmo.loop.Client;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The register of parties, with no world anywhere near it.
 *
 * <p>Time is handed in rather than waited for, exactly as combat takes its
 * randomness as an argument: an invitation expiring after a minute and a seat
 * being given up after half of one are rules worth testing, and neither is
 * worth ninety seconds of a test run.
 */
class PartyServiceTest {

    private long now = 1_000L;
    private final PartyService parties = new PartyService(() -> now);

    // ---- making one ----------------------------------------------------

    @Test
    void invitingSomebodyMakesAPartyOfOneAndAnOffer() {
        inTheWorld("Ala", "Bob");

        assertThat(parties.invite("ala", "Bob")).isNull();

        assertThat(parties.partyOf("ala").members()).hasSize(1);
        assertThat(parties.partyOf("ala").isLedBy("ala"))
                .as("whoever started it leads it; there is nothing else to press")
                .isTrue();
        assertThat(parties.partyOf("bob")).as("an offer is not a seat").isNull();
        assertThat(parties.invitationTo("bob").fromName()).isEqualTo("Ala");
    }

    @Test
    void acceptingTakesTheSeat() {
        inTheWorld("Ala", "Bob");
        parties.invite("ala", "Bob");

        assertThat(parties.accept("bob")).isNull();

        assertThat(parties.partyOf("bob").members()).hasSize(2);
        assertThat(parties.partyOf("ala").id())
                .as("one party, not two")
                .isEqualTo(parties.partyOf("bob").id());
        assertThat(parties.partyOf("bob").version())
                .as("and every change moves the version, so a reader can tell")
                .isGreaterThan(1);
    }

    @Test
    void decliningLeavesNoTrace() {
        inTheWorld("Ala", "Bob");
        parties.invite("ala", "Bob");

        parties.decline("bob");

        assertThat(parties.invitationTo("bob")).isNull();
        assertThat(parties.partyOf("bob")).isNull();
    }

    // ---- and every way it is refused ------------------------------------

    @Test
    void thereIsNoInvitingYourself() {
        inTheWorld("Ala");

        assertThat(parties.invite("ala", "Ala")).contains("Sam ze sobą");
    }

    @Test
    void norSomebodyWhoIsNotHere() {
        inTheWorld("Ala");

        assertThat(parties.invite("ala", "Duch")).contains("nikogo takiego");
    }

    @Test
    void norSomebodyAlreadyInAParty() {
        inTheWorld("Ala", "Bob", "Cela");
        parties.invite("ala", "Bob");
        parties.accept("bob");

        assertThat(parties.invite("cela", "Bob")).contains("już w drużynie");
    }

    @Test
    void norTwiceOver() {
        inTheWorld("Ala", "Bob", "Cela");
        parties.invite("ala", "Bob");

        assertThat(parties.invite("cela", "Bob")).contains("ma już zaproszenie");
    }

    @Test
    void andOnlyTheLeaderInvites() {
        inTheWorld("Ala", "Bob", "Cela");
        parties.invite("ala", "Bob");
        parties.accept("bob");

        assertThat(parties.invite("bob", "Cela")).contains("tylko przywódca");
    }

    @Test
    void aSixthIsTurnedAway() {
        inTheWorld("Ala", "Bob", "Cela", "Dana", "Edek", "Fela");
        for (String name : new String[]{"Bob", "Cela", "Dana", "Edek"}) {
            parties.invite("ala", name);
            parties.accept(name.toLowerCase(java.util.Locale.ROOT));
        }

        assertThat(parties.partyOf("ala").members()).hasSize(Party.MAX_MEMBERS);
        assertThat(parties.invite("ala", "Fela")).contains("pełna");
    }

    @Test
    void anOfferGoesStale() {
        inTheWorld("Ala", "Bob");
        parties.invite("ala", "Bob");

        now += PartyService.INVITE_MS + 1;

        assertThat(parties.accept("bob")).contains("nie jest aktualne");
        assertThat(parties.partyOf("bob")).isNull();
    }

    // ---- leaving, removing, handing over ---------------------------------

    @Test
    void leavingIsNobodysBusinessButYours() {
        inTheWorld("Ala", "Bob", "Cela");
        gather();

        parties.leave("bob");

        assertThat(parties.partyOf("bob")).isNull();
        assertThat(parties.partyOf("ala").members()).hasSize(2);
    }

    @Test
    void theLeaderLeavingHandsItOn() {
        inTheWorld("Ala", "Bob", "Cela");
        gather();

        parties.leave("ala");

        assertThat(parties.partyOf("bob").isLedBy("bob"))
                .as("a party with nobody in charge could only ever shrink")
                .isTrue();
    }

    @Test
    void theLastOneOutTurnsOffTheLight() {
        inTheWorld("Ala", "Bob");
        parties.invite("ala", "Bob");
        parties.accept("bob");

        parties.leave("bob");

        assertThat(parties.partyOf("ala"))
                .as("one person is not a party")
                .isNull();
    }

    @Test
    void onlyTheLeaderRemovesAnybody() {
        inTheWorld("Ala", "Bob", "Cela");
        gather();

        assertThat(parties.remove("bob", "Cela")).contains("tylko przywódca");
        assertThat(parties.remove("ala", "Cela")).isNull();
        assertThat(parties.partyOf("cela")).isNull();
    }

    @Test
    void andNotThemselves() {
        inTheWorld("Ala", "Bob", "Cela");
        gather();

        assertThat(parties.remove("ala", "Ala")).contains("można wyjść");
        assertThat(parties.partyOf("ala")).isNotNull();
    }

    @Test
    void leadershipGoesToSomebodyWhoIsInIt() {
        inTheWorld("Ala", "Bob", "Cela", "Dana");
        gather();

        assertThat(parties.handOver("ala", "Dana")).contains("Nie ma takiego");
        assertThat(parties.handOver("ala", "Cela")).isNull();
        assertThat(parties.partyOf("ala").isLedBy("cela")).isTrue();
        assertThat(parties.handOver("ala", "Bob"))
                .as("and the one who gave it away cannot give it again")
                .contains("tylko przywódca");
    }

    // ---- going quiet -----------------------------------------------------

    @Test
    void aSeatIsHeldForSomebodyWhoDropped() {
        inTheWorld("Ala", "Bob");
        parties.invite("ala", "Bob");
        parties.accept("bob");

        parties.disconnected("bob");
        now += PartyService.SILENCE_MS - 1;
        parties.forgetTheSilent();

        assertThat(parties.partyOf("bob"))
                .as("half a minute of wifi trouble must not break up a hunt")
                .isNotNull();
    }

    @Test
    void andGivenUpWhenTheWorldStopsTalkingAboutThem() {
        inTheWorld("Ala", "Bob", "Cela");
        gather();

        // Bob's map stops publishing: he has left the world for good.
        now += PartyService.SILENCE_MS + 1;
        parties.publish("ala", "Ala", 1, 10, 10, "starter", "Polana", 1, 1, true);
        parties.publish("cela", "Cela", 1, 10, 10, "starter", "Polana", 1, 1, true);
        parties.forgetTheSilent();

        assertThat(parties.partyOf("bob")).isNull();
        assertThat(parties.partyOf("ala").members()).hasSize(2);
    }

    @Test
    void anOfferNobodyAnsweredIsForgottenToo() {
        inTheWorld("Ala", "Bob");
        parties.invite("ala", "Bob");

        now += PartyService.INVITE_MS + 1;
        parties.publish("ala", "Ala", 1, 10, 10, "starter", "Polana", 1, 1, true);
        parties.publish("bob", "Bob", 1, 10, 10, "starter", "Polana", 1, 1, true);
        parties.forgetTheSilent();

        assertThat(parties.invitationTo("bob")).isNull();
    }

    // ---- talking ----------------------------------------------------------

    @Test
    void sayingSomethingNeedsAParty() {
        inTheWorld("Ala");

        assertThat(parties.say("ala", "jest tu kto?")).contains("Nie jesteś w drużynie");
    }

    @Test
    void andIsNotSaidTwiceInAHeartbeat() {
        inTheWorld("Ala", "Bob");
        parties.invite("ala", "Bob");
        parties.accept("bob");

        assertThat(parties.say("ala", "idziemy")).isNull();
        assertThat(parties.say("ala", "idziemy")).contains("Nie tak szybko");
        now += PartyService.CHAT_COOLDOWN_MS;
        assertThat(parties.say("ala", "no idziemy")).isNull();
    }

    @Test
    void andIsCutToLength() {
        inTheWorld("Ala", "Bob");
        parties.invite("ala", "Bob");
        parties.accept("bob");

        parties.say("ala", "x".repeat(PartyService.MAX_CHAT_LENGTH + 50));

        assertThat(parties.drainSaid().get(0).text())
                .hasSize(PartyService.MAX_CHAT_LENGTH);
    }

    @Test
    void whatWasSaidIsHandedOverOnceAndOnce() {
        inTheWorld("Ala", "Bob");
        parties.invite("ala", "Bob");
        parties.accept("bob");
        parties.say("ala", "idziemy");

        assertThat(parties.drainSaid()).hasSize(1);
        assertThat(parties.drainSaid())
                .as("a line said once must not be heard twice")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    /** Everybody named, standing on the glade, as their map would say. */
    private void inTheWorld(String... names) {
        for (String name : names) {
            String key = name.toLowerCase(java.util.Locale.ROOT);
            parties.publish(key, name, 1, 10, 10, "starter", "Polana", 1, 1, true);
            parties.joined(key, new Nobody());
        }
    }

    /** Ala, Bob and Cela in one party, with Ala leading. */
    private void gather() {
        parties.invite("ala", "Bob");
        parties.accept("bob");
        parties.invite("ala", "Cela");
        parties.accept("cela");
    }

    private static final class Nobody implements Client {
        @Override public void send(String json) { }
        @Override public void disconnect(String reason) { }
        @Override public String describe() { return "nobody"; }
    }
}
