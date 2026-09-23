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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An elite is not placed on the map; it turns up. That is the whole difference
 * between the two tiers this milestone ships, so it needs its own test - and a
 * map whose schedule is one second rather than the shipped map's minute, since
 * a test must not depend on waiting out a real interval.
 */
class RoamingEliteTest {

    private static final MapDef ARENA = new MapDefLoader(new MobDefLoader(),
            "classpath:test-maps-elite/*.json").loadAll().get("arena");
    private static final Map<String, MobDef> MOBS = new MobDefLoader().loadAll();
    private static final Content SHIPPED = new Content(MOBS, new ItemDefLoader().loadAll());
    private static final ObjectMapper JSON = new ObjectMapper();

    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        runner = new MapRunner(ARENA, JSON, WorldPersistence.NONE, SHIPPED);
        thread = new Thread(runner, "test-arena");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void theArenaStartsEmptyOfCreatures() {
        // It has no fixed spawns, so anything that shows up later arrived on its
        // own - which is what makes the next test mean something.
        FakeClient client = joinAt(3, 3);

        assertThat(client.await("\"type\":\"init\"")).doesNotContain("\"kind\":\"MOB\"");
    }

    @Test
    void anEliteTurnsUpOnItsOwnScheduleWithItsEscort() throws Exception {
        FakeClient client = joinAt(3, 3);
        client.await("\"type\":\"init\"");

        List<String> arrivals = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline && !arrivals.contains("ELITE")) {
            sleep(100);
            arrivals = tiersThatJoined(client);
        }

        assertThat(arrivals)
                .as("the elite should appear by itself, with the escort its entry names")
                .contains("ELITE")
                .containsOnlyOnce("ELITE");
        assertThat(arrivals.stream().filter("MOB"::equals).count())
                .as("two wolves escort her")
                .isEqualTo(2);
    }

    private static List<String> tiersThatJoined(FakeClient client) throws Exception {
        List<String> tiers = new ArrayList<>();
        for (String frame : client.frames()) {
            for (JsonNode joined : JSON.readTree(frame).path("joined")) {
                if ("MOB".equals(joined.path("kind").asText())) {
                    tiers.add(joined.path("tier").asText());
                }
            }
        }
        return tiers;
    }

    private FakeClient joinAt(int x, int y) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                SavedCharacter.fresh("ala", "Ala", ARENA.id(), x, y, Direction.DOWN), 0));
        assertThat(client.await("\"type\":\"init\"")).isNotBlank();
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
            return frames;
        }

        String await(String needle) {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                for (String frame : frames) {
                    if (frame.contains(needle)) {
                        return frame;
                    }
                }
                sleep(20);
            }
            throw new AssertionError("no frame containing " + needle);
        }
    }
}
