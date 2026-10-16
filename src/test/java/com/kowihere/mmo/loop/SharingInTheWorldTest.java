package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.CombatRules;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.MobDefLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One kill, several people.
 *
 * <p>Until now the last blow took everything, so four people hunting together
 * meant three of them working for nothing. The rule is the one from Tibia:
 * whoever took part shares equally, out of a pot slightly larger than the kill.
 *
 * <p>The party itself lives outside every map, so the runner is handed a board
 * that simply states who is with whom - which is exactly what the real service
 * hands it, an immutable copy that no tick ever waits on.
 */
class SharingInTheWorldTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    private static final Content CONTENT =
            new Content(MOBS, new ItemDefLoader("classpath:test-items/*.json").loadAll());
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-party/*.json").loadAll().get("arena-druzyny");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 30_000;

    /** What the real service publishes for the maps to read, stated by hand. */
    private final Map<String, Set<String>> fellows = new java.util.HashMap<>();

    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        runner = new MapRunner(ARENA, JSON, WorldPersistence.NONE, CONTENT, 20);
        runner.publishesTo(new PartyBoard() {
            @Override
            public void publish(String nameKey, String name, int level, int hp, int maxHp,
                                String mapId, String mapName, int x, int y, boolean online) {
            }

            @Override
            public Set<String> fellowsOf(String nameKey) {
                return fellows.getOrDefault(nameKey, Set.of());
            }
        });
        thread = new Thread(runner, "test-party-share");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    private void together(String... nameKeys) {
        Set<String> all = Set.of(nameKeys);
        for (String key : nameKeys) {
            fellows.put(key, all);
        }
    }

    @Test
    void bothOfThemGetPaidForOneKill() throws Exception {
        together("ala", "bela");
        FakeClient ala = join("Ala", 1L, 9, 3, 1);
        FakeClient bela = join("Bela", 2L, 10, 4, 1);
        int prey = creatureNamed(awaitInit(ala), "Cel druzyny");

        runner.submit(new Command.Attack(ala, prey));
        runner.submit(new Command.Attack(bela, prey));

        assertThat(ala.await(f -> f.contains("\"died\":[" + prey)))
                .as("between the two of them it should not last")
                .isTrue();
        sleep(400);

        long expected = CombatRules.shareOf(CombatRules.xpReward(MOBS.get("cel-druzyny")), 2);
        assertThat(xpOf(ala)).isEqualTo(expected);
        assertThat(xpOf(bela))
                .as("equally, whoever happened to strike last")
                .isEqualTo(expected);
    }

    @Test
    void standingThereEarnsNothing() throws Exception {
        // The whole of "taking part": a third member who never acts is not
        // paid, which is what stops a party being a lift.
        together("ala", "bela", "cela");
        FakeClient ala = join("Ala", 1L, 9, 3, 1);
        FakeClient cela = join("Cela", 3L, 2, 5, 1);
        int prey = creatureNamed(awaitInit(ala), "Cel druzyny");

        runner.submit(new Command.Attack(ala, prey));
        assertThat(ala.await(f -> f.contains("\"died\":[" + prey))).isTrue();
        sleep(400);

        assertThat(xpOf(cela)).as("she never lifted a finger").isZero();
        assertThat(xpOf(ala))
                .as("and the one who did the work is paid as though alone")
                .isEqualTo(CombatRules.xpReward(MOBS.get("cel-druzyny")));
    }

    @Test
    void anUnevenPartySharesNothing() throws Exception {
        // Twenty levels apart, so sharing does not switch on at all: the kill
        // belongs to whoever made it, in full, and the other gets nothing.
        //
        // Deliberately not "the alt gets nothing": whoever lands the blow is
        // whoever lands the blow, and on a busy machine that is sometimes the
        // level-one character - who has then earned their own kill rather than
        // been carried to somebody else's. What the rule promises is that the
        // kill is never split, and that is what is asserted.
        together("ala", "mocarz");
        FakeClient strong = join("Mocarz", 1L, 9, 3, 21);
        FakeClient alt = join("Ala", 2L, 10, 4, 1);
        int prey = creatureNamed(awaitInit(strong), "Cel druzyny");
        // Gains, not totals: a level-twenty-one character starts the test with
        // forty thousand experience behind it.
        long strongBefore = xpOf(strong);
        long altBefore = xpOf(alt);

        runner.submit(new Command.Attack(strong, prey));
        runner.submit(new Command.Attack(alt, prey));
        assertThat(strong.await(f -> f.contains("\"died\":[" + prey))).isTrue();
        sleep(400);

        long whole = CombatRules.xpReward(MOBS.get("cel-druzyny"));
        assertThat(List.of(xpOf(strong) - strongBefore, xpOf(alt) - altBefore))
                .as("one of them killed it and was paid in full; the other was paid nothing")
                .containsExactlyInAnyOrder(whole, 0L);
        assertThat(strong.frames())
                .as("and nobody is left wondering where the experience went")
                .anyMatch(f -> f.contains("zbyt różna poziomami"));
    }

    @Test
    void aloneIsPaidExactlyAsBefore() throws Exception {
        // The pin: this milestone must not move anybody's levelling curve.
        FakeClient ala = join("Ala", 1L, 9, 3, 1);
        int prey = creatureNamed(awaitInit(ala), "Cel druzyny");

        runner.submit(new Command.Attack(ala, prey));
        assertThat(ala.await(f -> f.contains("\"died\":[" + prey))).isTrue();
        sleep(400);

        assertThat(xpOf(ala)).isEqualTo(CombatRules.xpReward(MOBS.get("cel-druzyny")));
    }

    // ------------------------------------------------------------------

    private long xpOf(FakeClient client) {
        List<String> frames = client.frames();
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (frames.get(i).contains("\"type\":\"you\"")) {
                try {
                    return JSON.readTree(frames.get(i)).path("xp").asLong();
                } catch (Exception e) {
                    throw new AssertionError("unreadable frame " + frames.get(i), e);
                }
            }
        }
        throw new AssertionError("no own-state frame was ever sent");
    }

    private String awaitInit(FakeClient client) {
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client.frames().stream().filter(f -> f.contains("\"type\":\"init\""))
                .findFirst().orElseThrow();
    }

    private FakeClient join(String name, long account, int x, int y, int level) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, account,
                new SavedCharacter(PlayerNames.key(name), name, ARENA.id(), x, y, Direction.DOWN,
                        level, CombatRules.xpForLevel(level), -1, 0L, Attributes.FRESH, 0,
                        List.of(), null, 1, List.of(), List.of(),
                        Deposit.EMPTY, Deposit.EMPTY, List.of()), 0));
        assertThat(client.await(f -> f.contains("\"type\":\"you\""))).isTrue();
        return client;
    }

    private static int creatureNamed(String initFrame, String name) throws Exception {
        for (JsonNode actor : JSON.readTree(initFrame).path("actors")) {
            if (name.equals(actor.path("name").asText())) {
                return actor.path("id").asInt();
            }
        }
        throw new AssertionError("no creature called " + name);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeClient implements Client {

        private final List<String> frames = new CopyOnWriteArrayList<>();

        @Override
        public void send(String json) {
            frames.add(json);
        }

        @Override
        public void disconnect(String reason) {
        }

        @Override
        public String describe() {
            return "fake client";
        }

        List<String> frames() {
            return new ArrayList<>(frames);
        }

        boolean await(Predicate<String> match) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (frames.stream().anyMatch(match)) {
                    return true;
                }
                sleep(20);
            }
            return false;
        }
    }
}
