package com.kowihere.mmo.party;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.loop.Client;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the party panel is drawn from.
 *
 * <p>The claim being tested is the one the whole design exists for: two
 * characters on two different maps appear in one panel, with their own health,
 * and nothing in either map's tick built the frame.
 */
class PartyHeartbeatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private long now = 1_000L;
    private final PartyService parties = new PartyService(() -> now);
    private final PartyHeartbeat heartbeat = new PartyHeartbeat(parties, JSON);

    @Test
    void onePanelShowsTwoMaps() throws Exception {
        Spy ala = enters("ala", "Ala", "las", "Las Wilczy", 30, 55);
        Spy bob = enters("bob", "Bob", "starter", "Polana", 12, 60);
        parties.invite("ala", "Bob");
        parties.accept("bob");

        heartbeat.beat();

        JsonNode panel = JSON.readTree(ala.latest("\"type\":\"party\""));
        assertThat(panel.path("members")).hasSize(2);
        assertThat(mapsIn(panel)).containsExactlyInAnyOrder("Las Wilczy", "Polana");
        assertThat(panel.path("members").get(0).path("hp").asInt()).isEqualTo(30);
        assertThat(bob.latest("\"type\":\"party\"")).contains("Las Wilczy");
    }

    @Test
    void eachOfThemIsToldWhichRowIsTheirs() throws Exception {
        Spy ala = enters("ala", "Ala", "starter", "Polana", 10, 10);
        Spy bob = enters("bob", "Bob", "starter", "Polana", 10, 10);
        parties.invite("ala", "Bob");
        parties.accept("bob");

        heartbeat.beat();

        assertThat(youIn(JSON.readTree(ala.latest("\"type\":\"party\"")))).isEqualTo("Ala");
        assertThat(youIn(JSON.readTree(bob.latest("\"type\":\"party\""))))
                .as("otherwise the client would have to work out who it is, which is a second"
                        + " place deciding that")
                .isEqualTo("Bob");
    }

    @Test
    void anOfferIsCarriedToWhoeverWasOffered() throws Exception {
        Spy ala = enters("ala", "Ala", "starter", "Polana", 10, 10);
        Spy bob = enters("bob", "Bob", "starter", "Polana", 10, 10);
        parties.invite("ala", "Bob");

        heartbeat.beat();

        assertThat(bob.latest("\"type\":\"partyInvite\"")).contains("\"from\":\"Ala\"");
        assertThat(ala.frames()).noneMatch(f -> f.contains("\"type\":\"partyInvite\""));
    }

    @Test
    void andWithdrawnWhenItGoesStale() throws Exception {
        enters("ala", "Ala", "starter", "Polana", 10, 10);
        Spy bob = enters("bob", "Bob", "starter", "Polana", 10, 10);
        parties.invite("ala", "Bob");
        heartbeat.beat();
        int after = bob.frames().size();

        now += PartyService.INVITE_MS + 1;
        heartbeat.beat();

        assertThat(bob.frames().subList(after, bob.frames().size()))
                .anyMatch(f -> f.contains("\"type\":\"partyInvite\"") && !f.contains("\"from\""));
    }

    @Test
    void breakingUpIsSaidOnceToEverybody() throws Exception {
        Spy ala = enters("ala", "Ala", "starter", "Polana", 10, 10);
        Spy bob = enters("bob", "Bob", "starter", "Polana", 10, 10);
        parties.invite("ala", "Bob");
        parties.accept("bob");
        heartbeat.beat();
        int after = ala.frames().size();

        parties.leave("bob");
        heartbeat.beat();

        assertThat(ala.frames().subList(after, ala.frames().size()))
                .as("a panel left over from a party that no longer exists is a lie on screen")
                .anyMatch(f -> f.contains("\"type\":\"party\"") && f.contains("\"members\":[]"));
        heartbeat.beat();
        assertThat(ala.frames().stream()
                .filter(f -> f.contains("\"members\":[]")).count())
                .as("and said once, not every half second for ever")
                .isEqualTo(1);
    }

    @Test
    void whatIsSaidReachesTheOtherMapAndNobodyElse() throws Exception {
        Spy ala = enters("ala", "Ala", "las", "Las Wilczy", 10, 10);
        Spy bob = enters("bob", "Bob", "starter", "Polana", 10, 10);
        Spy cela = enters("cela", "Cela", "starter", "Polana", 10, 10);
        parties.invite("ala", "Bob");
        parties.accept("bob");

        parties.say("ala", "idę do jamy");
        heartbeat.beat();

        assertThat(bob.latest("\"type\":\"partyChat\"")).contains("idę do jamy");
        assertThat(cela.frames())
                .as("a party channel that anybody else hears is not a party channel")
                .noneMatch(f -> f.contains("partyChat"));
    }

    // ------------------------------------------------------------------

    private Spy enters(String key, String name, String mapId, String mapName, int hp, int maxHp) {
        Spy spy = new Spy();
        parties.publish(key, name, 3, hp, maxHp, mapId, mapName, 1, 1, true);
        parties.joined(key, spy);
        return spy;
    }

    private static List<String> mapsIn(JsonNode panel) {
        List<String> maps = new ArrayList<>();
        for (JsonNode member : panel.path("members")) {
            maps.add(member.path("mapName").asText());
        }
        return maps;
    }

    private static String youIn(JsonNode panel) {
        for (JsonNode member : panel.path("members")) {
            if (member.path("you").asBoolean()) {
                return member.path("name").asText();
            }
        }
        throw new AssertionError("no row in this panel belongs to its owner: " + panel);
    }

    private static final class Spy implements Client {

        private final List<String> frames = new CopyOnWriteArrayList<>();

        @Override public void send(String json) {
            frames.add(json);
        }

        @Override public void disconnect(String reason) { }

        @Override public String describe() { return "spy"; }

        List<String> frames() {
            return new ArrayList<>(frames);
        }

        String latest(String needle) {
            List<String> seen = frames();
            for (int i = seen.size() - 1; i >= 0; i--) {
                if (seen.get(i).contains(needle)) {
                    return seen.get(i);
                }
            }
            throw new AssertionError("no frame containing " + needle + ": " + seen);
        }
    }
}
