package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.CombatRules;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.MobDefLoader;
import com.kowihere.mmo.world.SpawnPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fights in a running map.
 *
 * <p>Runs on its own arena with its own creatures rather than the shipped map:
 * a weakling that dies in a few blows and revives in a second, and a killer that
 * ends a level-one character in two. Tuning a test around the real map's wolves
 * would make it a test of the wolves.
 */
class CombatInTheWorldTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-combat/*.json").loadAll().get("arena-walki");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 12_000;
    private static final long ACCOUNT = 1L;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(ARENA, JSON, saved, MOBS, 20);
        thread = new Thread(runner, "test-arena-combat");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void attackingACreatureWalksThereAndStartsTradingBlows() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        int weakling = creatureNamed(client.await("\"type\":\"init\""), "Slabeusz");

        runner.submit(new Command.Attack(client, weakling));

        assertThat(client.await(f -> f.contains("\"damage\"")))
                .as("the server should walk the character over and start the fight itself")
                .isTrue();
    }

    @Test
    void killingACreatureRemovesItAndPaysExperience() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        int weakling = creatureNamed(client.await("\"type\":\"init\""), "Slabeusz");

        runner.submit(new Command.Attack(client, weakling));

        assertThat(client.await(f -> f.contains("\"died\":[" + weakling)))
                .as("a creature with 12 health should not survive long")
                .isTrue();
        assertThat(client.await(f -> f.contains("\"type\":\"you\"") && !f.contains("\"xp\":0")))
                .as("the killer should be paid for it")
                .isTrue();
    }

    @Test
    void experienceNeverTravelsInADeltaThatEveryoneSees() throws Exception {
        // Deltas are broadcast to the whole map. One player's progress is nobody
        // else's business, and this is exactly the sort of leak nobody would
        // ever file a bug about.
        FakeClient ala = join("Ala", 5, 5);
        FakeClient bob = join("Bogumil", 6, 6);
        int weakling = creatureNamed(ala.await("\"type\":\"init\""), "Slabeusz");

        runner.submit(new Command.Attack(ala, weakling));
        assertThat(ala.await(f -> f.contains("\"died\":[" + weakling))).isTrue();
        sleep(500);

        assertThat(bob.frames())
                .as("Bogumil saw the fight, but must not have been told what it earned")
                .noneMatch(frame -> frame.contains("\"type\":\"you\"") && frame.contains("\"xp\":")
                        && !frame.contains("\"xp\":0"));
    }

    @Test
    void movementIsRefusedWhileFighting() throws Exception {
        // The easiest thing to miss, because it is a seam between two features
        // that were built apart: without it you simply walk away from a losing
        // round and the whole model collapses.
        FakeClient client = join("Ala", 5, 5);
        int killer = creatureNamed(client.await("\"type\":\"init\""), "Zabojca");

        runner.submit(new Command.Attack(client, killer));
        assertThat(client.await(f -> f.contains("\"damage\""))).isTrue();

        int[] before = selfPosition(client);
        runner.submit(new Command.MoveTo(client, 1, 1)); // the far corner
        sleep(1_200);

        assertThat(selfPosition(client))
                .as("a fight holds you where you stand")
                .containsExactly(before[0], before[1]);
    }

    @Test
    void aKilledCreatureComesBackToItsPost() throws Exception {
        // respawnSeconds finally does something. The arena's weakling revives in
        // one second rather than the shipped map's half-minute.
        FakeClient client = join("Ala", 5, 5);
        int weakling = creatureNamed(client.await("\"type\":\"init\""), "Slabeusz");

        runner.submit(new Command.Attack(client, weakling));
        assertThat(client.await(f -> f.contains("\"died\":[" + weakling))).isTrue();

        assertThat(client.await(f -> f.contains("\"joined\"") && f.contains("Slabeusz")))
                .as("it should be standing at its post again")
                .isTrue();
    }

    @Test
    void aPlayerWhoDiesWakesUpAtTheSpawnAndWeakened() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        int killer = creatureNamed(client.await("\"type\":\"init\""), "Zabojca");

        runner.submit(new Command.Attack(client, killer));

        assertThat(client.await(f -> f.contains("\"weakenedUntil\"") && !f.contains("\"weakenedUntil\":0")))
                .as("dying should carry a penalty, not just a walk back")
                .isTrue();
        assertThat(selfPosition(client))
                .as("and put the character back at the spawn")
                .containsExactly(ARENA.spawnX(), ARENA.spawnY());
        assertThat(inFight(client))
                .as("a fight you lost is a fight you are out of")
                .isFalse();
    }

    @Test
    void theNewsOfYourDeathArrivesAfterTheBodyHasMoved() throws Exception {
        // The private frame is written during the tick and the delta at the end
        // of it, so the obvious implementation tells a client it is dead and
        // weakened while its character is still drawn where it fell.
        FakeClient client = join("Ala", 5, 5);
        int killer = creatureNamed(client.await("\"type\":\"init\""), "Zabojca");

        runner.submit(new Command.Attack(client, killer));
        assertThat(client.await(f -> f.contains("\"weakenedUntil\"") && !f.contains("\"weakenedUntil\":0")))
                .isTrue();

        List<String> frames = client.frames();
        int told = -1;
        int moved = -1;
        for (int i = 0; i < frames.size(); i++) {
            String frame = frames.get(i);
            if (told < 0 && frame.contains("\"weakenedUntil\"") && !frame.contains("\"weakenedUntil\":0")) {
                told = i;
            }
            if (moved < 0 && frame.contains("\"joined\"") && frame.contains("* ginie *")) {
                moved = i;
            }
        }

        assertThat(moved).as("the death should have been broadcast at all").isNotNegative();
        assertThat(moved)
                .as("the body moves first, then the character is told why")
                .isLessThan(told);
    }

    @Test
    void fleeingEventuallyEndsAFight() throws Exception {
        // Fought against a creature that cannot win and cannot lose: the killer
        // would end the fight by killing, and the weakling by dying, and either
        // would let this pass without fleeing ever having worked.
        FakeClient client = join("Ala", 5, 5);
        int log = creatureNamed(client.await("\"type\":\"init\""), "Kloda");

        runner.submit(new Command.Attack(client, log));
        assertThat(client.await(f -> f.contains("\"damage\""))).isTrue();

        // Escaping is rolled once per round, so each attempt waits a whole one.
        // Asking ten times in four seconds would be asking twice and calling it
        // ten - and then a failure here would mean nothing.
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline && inFight(client)) {
            runner.submit(new Command.Flee(client));
            sleep(1_700);
        }

        assertThat(inFight(client))
                .as("fleeing at %.0f%% should not fail a dozen rounds running",
                        CombatRules.FLEE_CHANCE * 100)
                .isFalse();
        assertThat(client.frames())
                .as("and it should be an escape, not a death")
                .noneMatch(f -> f.contains("\"weakenedUntil\"") && !f.contains("\"weakenedUntil\":0"));
    }

    @Test
    void afailedEscapeCostsYouTheRound() throws Exception {
        // With the roll fixed against the runner, the round is decided rather
        // than sampled: the creature must swing and the runner must not.
        MapRunner rigged = new MapRunner(ARENA, JSON, WorldPersistence.NONE, MOBS, 20, neverEscapes());
        Thread riggedThread = new Thread(rigged, "test-arena-no-escape");
        riggedThread.setDaemon(true);
        riggedThread.start();
        try {
            FakeClient client = new FakeClient();
            rigged.submit(new Command.Join(client, ACCOUNT,
                    SavedCharacter.fresh("bela", "Bela", ARENA.id(), 5, 5, Direction.DOWN), 0));
            assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
            String init = client.await("\"type\":\"init\"");
            int log = creatureNamed(init, "Kloda");
            int self = JSON.readTree(init).path("selfId").asInt();

            rigged.submit(new Command.Attack(client, log));
            assertThat(client.await(f -> f.contains("\"damage\""))).isTrue();

            rigged.submit(new Command.Flee(client));
            int before = blowsStruckBy(client, self);
            sleep(2_000); // one whole round, spent trying to get away

            assertThat(blowsStruckBy(client, self))
                    .as("someone busy running is not also swinging")
                    .isEqualTo(before);
            assertThat(inFight(client))
                    .as("and the escape failed, so the fight goes on")
                    .isTrue();
        } finally {
            rigged.stop();
            riggedThread.join(2_000);
        }
    }

    @Test
    void combatProgressIsKeptWhenTheMapStops() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        int weakling = creatureNamed(client.await("\"type\":\"init\""), "Slabeusz");
        runner.submit(new Command.Attack(client, weakling));
        assertThat(client.await(f -> f.contains("\"died\":[" + weakling))).isTrue();

        runner.stop();
        thread.join(2_000);

        assertThat(saved.snapshots)
                .as("what was earned has to outlive the process")
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.nameKey()).isEqualTo("ala");
                    assertThat(snapshot.xp()).isPositive();
                    assertThat(snapshot.hp()).isPositive();
                });
    }

    // ------------------------------------------------------------------

    /** A roll that always lands above any chance, so nothing random succeeds. */
    private static Random neverEscapes() {
        return new Random() {
            @Override
            public double nextDouble() {
                return 1.0;
            }
        };
    }

    private int blowsStruckBy(FakeClient client, int actorId) throws Exception {
        int blows = 0;
        for (String frame : client.frames()) {
            for (JsonNode blow : JSON.readTree(frame).path("damage")) {
                if (blow.path("attacker").asInt() == actorId) {
                    blows++;
                }
            }
        }
        return blows;
    }

    private static int creatureNamed(String initFrame, String name) throws Exception {
        for (JsonNode actor : JSON.readTree(initFrame).path("actors")) {
            if (name.equals(actor.path("name").asText())) {
                return actor.path("id").asInt();
            }
        }
        throw new AssertionError("no creature called " + name + " in " + initFrame);
    }

    /** Where this client's own character ended up, according to everything it has seen. */
    private int[] selfPosition(FakeClient client) throws Exception {
        int self = -1;
        int[] position = null;
        for (String frame : client.frames()) {
            JsonNode node = JSON.readTree(frame);
            if ("init".equals(node.path("type").asText())) {
                self = node.path("selfId").asInt();
                for (JsonNode actor : node.path("actors")) {
                    if (actor.path("id").asInt() == self) {
                        position = new int[]{actor.path("x").asInt(), actor.path("y").asInt()};
                    }
                }
            }
            for (JsonNode move : node.path("moved")) {
                if (move.path("id").asInt() == self) {
                    position = new int[]{move.path("x").asInt(), move.path("y").asInt()};
                }
            }
            // A death teleports rather than walks, and arrives as a re-join.
            for (JsonNode rejoined : node.path("joined")) {
                if (rejoined.path("id").asInt() == self) {
                    position = new int[]{rejoined.path("x").asInt(), rejoined.path("y").asInt()};
                }
            }
        }
        assertThat(position).as("the client never learned where it was").isNotNull();
        return position;
    }

    private boolean inFight(FakeClient client) throws Exception {
        int self = -1;
        boolean fighting = false;
        for (String frame : client.frames()) {
            JsonNode node = JSON.readTree(frame);
            if ("init".equals(node.path("type").asText())) {
                self = node.path("selfId").asInt();
            }
            for (JsonNode change : node.path("fights")) {
                if (change.path("id").asInt() == self) {
                    fighting = change.path("inFight").asBoolean();
                }
            }
        }
        return fighting;
    }

    private FakeClient join(String name, int x, int y) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, ACCOUNT,
                SavedCharacter.fresh(PlayerNames.key(name), name, ARENA.id(), x, y, Direction.DOWN), 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RecordingPersistence implements WorldPersistence {

        private final List<ActorSnapshot> snapshots = new CopyOnWriteArrayList<>();

        @Override
        public void save(ActorSnapshot snapshot) {
            snapshots.add(snapshot);
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

        String await(String needle) {
            assertThat(await(f -> f.contains(needle))).as("frame containing %s", needle).isTrue();
            return frames.stream().filter(f -> f.contains(needle)).findFirst().orElseThrow();
        }
    }
}
