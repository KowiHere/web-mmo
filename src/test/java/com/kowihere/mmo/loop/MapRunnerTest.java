package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real map thread and reads what comes out of it. These are slower
 * than unit tests by design: the bugs worth catching here are the ones that
 * only exist once the loop is actually ticking.
 */
class MapRunnerTest {

    private static final MapDef MAP = new MapDefLoader().loadAll().get("starter");
    private static final long TIMEOUT_MS = 5_000;

    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        runner = new MapRunner(MAP, new ObjectMapper());
        thread = new Thread(runner, "test-map");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void theFirstChatMessageIsDelivered() {
        // Regression: the cooldown used to be measured against a Long.MIN_VALUE
        // sentinel, and `tick - MIN_VALUE` overflows negative — so every actor's
        // very first message looked like it had been sent a moment ago.
        FakeClient client = join("Ala");

        runner.submit(new Command.Chat(client, "halo"));

        assertThat(client.await(frame -> frame.contains("\"chat\"") && frame.contains("halo")))
                .as("first chat message should reach the map's delta")
                .isTrue();
    }

    @Test
    void chatIsRateLimitedAfterTheFirstMessage() {
        FakeClient client = join("Ala");
        runner.submit(new Command.Chat(client, "pierwsza"));
        assertThat(client.await(f -> f.contains("pierwsza"))).isTrue();

        runner.submit(new Command.Chat(client, "natychmiast"));
        sleep(300);

        assertThat(client.frames()).noneMatch(f -> f.contains("natychmiast"));
    }

    @Test
    void aClickWalksTheActorTowardsTheTile() {
        FakeClient client = join("Ala");

        runner.submit(new Command.MoveTo(client, MAP.spawnX() - 3, MAP.spawnY()));

        assertThat(client.await(f -> f.contains("\"moved\"")))
                .as("a reachable destination should produce steps")
                .isTrue();
        assertThat(client.await(f -> f.contains("\"x\":" + (MAP.spawnX() - 3))))
                .as("the actor should arrive at the requested tile")
                .isTrue();
    }

    @Test
    void aTileInsideAWallProducesNoMovementAtAll() {
        FakeClient client = join("Ala");
        client.frames().clear();

        runner.submit(new Command.MoveTo(client, 0, 0)); // the map border
        sleep(600);

        assertThat(client.frames()).noneMatch(f -> f.contains("\"moved\""));
    }

    @Test
    void twoClientsOnOneMapSeeEachOther() {
        FakeClient ala = join("Ala");
        FakeClient bob = join("Bob");

        assertThat(ala.await(f -> f.contains("\"joined\"") && f.contains("Bob")))
                .as("Ala should be told that Bob arrived")
                .isTrue();

        runner.submit(new Command.MoveTo(bob, MAP.spawnX(), MAP.spawnY() - 2));
        assertThat(ala.await(f -> f.contains("\"moved\"")))
                .as("Ala should see Bob move")
                .isTrue();
    }

    @Test
    void aReconnectWithTheRightTokenResumesTheSameActor() {
        FakeClient first = join("Ala");
        String token = extract(first.await("\"type\":\"init\""), "\"token\":\"", "\"");
        String actorId = extract(first.await("\"type\":\"init\""), "\"selfId\":", ",");

        runner.submit(new Command.Detach(first));
        sleep(300);

        FakeClient second = new FakeClient();
        runner.submit(new Command.Join(second, "Ala", token, 0));

        assertThat(second.await(f -> f.contains("\"selfId\":" + actorId)))
                .as("the same actor should be handed back, not a new one")
                .isTrue();
    }

    // ------------------------------------------------------------------

    private FakeClient join(String name) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, name, null, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    private static String extract(String frame, String after, String until) {
        int start = frame.indexOf(after) + after.length();
        return frame.substring(start, frame.indexOf(until, start));
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
            return frames;
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

        /** Returns the first frame containing {@code needle}, or fails the test. */
        String await(String needle) {
            assertThat(await(f -> f.contains(needle))).as("frame containing %s", needle).isTrue();
            return frames.stream().filter(f -> f.contains(needle)).findFirst().orElseThrow();
        }
    }
}
