package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing attacks you where you appear.
 *
 * <p>Every character starts on the spawn tile and returns to it after dying,
 * already weakened. A creature free to attack there turns one death into a
 * loop that a new character cannot break, and the arena here is the worst
 * case: a hunter with an aggression range that covers the whole map, standing
 * two tiles from the spawn.
 */
class SafeSpawnTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    private static final Content CONTENT =
            new Content(MOBS, new ItemDefLoader("classpath:test-items/*.json").loadAll());
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-safe/*.json").loadAll().get("arena-bezpieczna");

    private static final ObjectMapper JSON = new ObjectMapper();

    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        runner = new MapRunner(ARENA, JSON, WorldPersistence.NONE, CONTENT, 20);
        thread = new Thread(runner, "test-arena-safe");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void aCharacterStandingOnTheSpawnIsLeftAlone() {
        FakeClient client = join(ARENA.spawnX(), ARENA.spawnY());

        sleep(3_000); // many rounds' worth of chances to be attacked

        assertThat(client.frames())
                .as("the hunter can see the whole map, and must still keep its distance")
                .noneMatch(frame -> frame.contains("\"damage\""));
    }

    @Test
    void stepAwayAndTheTruceIsOver() {
        // The other half of the rule: the spawn is a doorstep, not a shelter to
        // fight from. Without this the first test would also pass on a creature
        // that never attacks anybody at all.
        FakeClient client = join(ARENA.spawnX(), ARENA.spawnY());
        runner.submit(new Command.MoveTo(client, 11, 4)); // the far end

        assertThat(client.await(frame -> frame.contains("\"damage\"")))
                .as("a character out in the open is fair game")
                .isTrue();
    }

    private FakeClient join(int x, int y) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                SavedCharacter.fresh("ala", "Ala", ARENA.id(), x, y, Direction.DOWN), 0));
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
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (frames().stream().anyMatch(match)) {
                    return true;
                }
                sleep(20);
            }
            return false;
        }
    }
}
