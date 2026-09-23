package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.Direction;
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
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attacking something that will not stand still.
 *
 * <p>The server walks you to whatever you told it to attack. That path is worked
 * out once, when the order is given - and creatures wander. Told to attack
 * something across the map, a character used to arrive at the tile the creature
 * had been standing on and then stop, which from the player's side looks like
 * the attack quietly not working.
 *
 * <p>The quarry is made to run rather than left to wander: with every roll
 * fixed it heads north, and the arena gives it eighteen rows to do it in. It is
 * therefore a long way from wherever it was when the order was given, every
 * single time.
 *
 * <p>Both of those matter. Told to wander at random it often drifted back within
 * reach of the original path; penned into two tiles it never left them. Either
 * way this passed whether the code worked or not, which is what it did for the
 * first two attempts at writing it.
 */
class ChasingATargetTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    private static final Content CONTENT = new Content(MOBS, Map.of());
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-chase/*.json").loadAll().get("arena-pogoni");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 20_000;

    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        runner = new MapRunner(ARENA, JSON, WorldPersistence.NONE, CONTENT, 20, alwaysFirst());
        thread = new Thread(runner, "test-arena-chase");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void aCharacterKeepsWalkingUntilItCatchesWhatItWasToldToAttack() throws Exception {
        FakeClient client = join("Goncza", 2, 18);
        int quarry = creatureNamed(client.await("\"type\":\"init\""), "Uciekinier");

        runner.submit(new Command.Attack(client, quarry));

        assertThat(client.await(f -> f.contains("\"damage\"")))
                .as("being told to attack something should end in attacking it, "
                        + "even when it moves while you walk over")
                .isTrue();
    }

    // ------------------------------------------------------------------

    private int selfId(FakeClient client) throws Exception {
        return JSON.readTree(client.await("\"type\":\"init\"")).path("selfId").asInt();
    }

    private int[] where(FakeClient client, int actorId) throws Exception {
        int[] at = null;
        for (String frame : client.frames()) {
            JsonNode node = JSON.readTree(frame);
            for (JsonNode a : node.path("actors")) {
                if (a.path("id").asInt() == actorId) at = new int[]{a.path("x").asInt(), a.path("y").asInt()};
            }
            for (JsonNode m : node.path("moved")) {
                if (m.path("id").asInt() == actorId) at = new int[]{m.path("x").asInt(), m.path("y").asInt()};
            }
        }
        return at;
    }

    private static int creatureNamed(String initFrame, String name) throws Exception {
        for (JsonNode actor : JSON.readTree(initFrame).path("actors")) {
            if (name.equals(actor.path("name").asText())) {
                return actor.path("id").asInt();
            }
        }
        throw new AssertionError("no creature called " + name);
    }

    private FakeClient join(String name, int x, int y) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                SavedCharacter.fresh(PlayerNames.key(name), name, ARENA.id(), x, y, Direction.DOWN), 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    /** Every choice taken at its lowest, so the quarry always heads one way. */
    private static Random alwaysFirst() {
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return 0;
            }

            @Override
            public double nextDouble() {
                return 0.0;
            }
        };
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
                if (frames().stream().anyMatch(match)) {
                    return true;
                }
                sleep(20);
            }
            return false;
        }

        String await(String needle) {
            assertThat(await(f -> f.contains(needle))).as("frame containing %s", needle).isTrue();
            return frames().stream().filter(f -> f.contains(needle)).findFirst().orElseThrow();
        }
    }
}
