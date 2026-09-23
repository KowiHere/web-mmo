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
 * An elite's escort must not outlive its usefulness.
 *
 * <p>The escort here is a creature that does not hunt, so the queen can be
 * reached and killed without her pack hijacking the fight first - the point
 * under test is bookkeeping, not who wins.
 *
 * <p>Escorts are spawned with the elite and are not respawning creatures, but
 * they do survive it - so an elite that turns up every so often would leave two
 * more wolves behind each time, for as long as the server is up. Nobody would
 * notice for an hour and then the map would be nothing but wolves.
 */
class EscortPackTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    private static final Content CONTENT =
            new Content(MOBS, new ItemDefLoader("classpath:test-items/*.json").loadAll());
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-escort/*.json").loadAll().get("arena-eskorty");

    private static final ObjectMapper JSON = new ObjectMapper();

    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        runner = new MapRunner(ARENA, JSON, WorldPersistence.NONE, CONTENT, 20);
        thread = new Thread(runner, "test-arena-escort");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void aSecondEliteBringsNoSecondPack() throws Exception {
        FakeClient client = join();

        // First appearance: the queen and her two escorts.
        assertThat(client.await(frame -> tiers(frame).contains("ELITE"))).isTrue();
        assertThat(client.awaitCount(this::livingEscorts, 2))
                .as("the first pack arrives whole")
                .isTrue();

        int queen = idOfTier(client, "ELITE");
        runner.submit(new Command.Attack(client, queen));
        assertThat(client.await(frame -> frame.contains("\"died\":[" + queen)))
                .as("the queen has ten health; she should not last")
                .isTrue();

        // She is scheduled again a second later. Her escorts are still alive, so
        // the new pack should be exactly the escorts that are missing: none.
        assertThat(client.await(frame -> tiers(frame).contains("ELITE"), 2))
                .as("she should turn up again")
                .isTrue();
        sleep(1_500);

        assertThat(livingEscorts(client))
                .as("the pack has a size; a second appearance refills it rather than doubling it")
                .isBetween(1, 2);
    }

    @Test
    void aPackNeverTurnsUpOnTopOfThePlaceCharactersArrive() throws Exception {
        // Otherwise logging in, and coming back from a death, both mean being
        // attacked before you can take a step - and a level-one character has
        // no answer to an elite and two escorts.
        FakeClient client = join();
        assertThat(client.await(frame -> tiers(frame).contains("ELITE"))).isTrue();

        for (String frame : client.frames()) {
            for (JsonNode joined : JSON.readTree(frame).path("joined")) {
                if (!"MOB".equals(joined.path("kind").asText())) {
                    continue;
                }
                int distance = Math.abs(joined.path("x").asInt() - ARENA.spawnX())
                        + Math.abs(joined.path("y").asInt() - ARENA.spawnY());
                // The elite keeps the whole clearance; an escort is placed within
                // two steps of her, so it keeps all but four tiles of it. Either
                // is far outside the aggression range of anything in this game.
                int least = "ELITE".equals(joined.path("tier").asText()) ? 8 : 4;
                assertThat(distance)
                        .as("%s appeared %d tiles from the spawn", joined.path("name").asText(), distance)
                        .isGreaterThanOrEqualTo(least);
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * Escort-tier creatures standing on the map right now.
     *
     * <p>Counting arrivals would not do: an escort can die in the fight over the
     * queen and be legitimately replaced by the next pack. What must stay bounded
     * is how many are there at once.
     */
    private int livingEscorts(FakeClient client) {
        List<Integer> alive = new ArrayList<>();
        try {
            for (String frame : client.frames()) {
                JsonNode node = JSON.readTree(frame);
                for (JsonNode joined : node.path("joined")) {
                    if ("MOB".equals(joined.path("kind").asText())
                            && "MOB".equals(joined.path("tier").asText())) {
                        alive.add(joined.path("id").asInt());
                    }
                }
                for (JsonNode gone : node.path("died")) {
                    alive.remove(Integer.valueOf(gone.asInt()));
                }
                for (JsonNode gone : node.path("left")) {
                    alive.remove(Integer.valueOf(gone.asInt()));
                }
            }
        } catch (Exception e) {
            throw new AssertionError("unreadable frame", e);
        }
        return alive.size();
    }

    private List<String> tiers(String frame) {
        List<String> tiers = new ArrayList<>();
        try {
            for (JsonNode joined : JSON.readTree(frame).path("joined")) {
                if ("MOB".equals(joined.path("kind").asText())) {
                    tiers.add(joined.path("tier").asText());
                }
            }
        } catch (Exception e) {
            throw new AssertionError("unreadable frame " + frame, e);
        }
        return tiers;
    }

    private int idOfTier(FakeClient client, String tier) throws Exception {
        for (String frame : client.frames()) {
            for (JsonNode joined : JSON.readTree(frame).path("joined")) {
                if (tier.equals(joined.path("tier").asText())) {
                    return joined.path("id").asInt();
                }
            }
        }
        throw new AssertionError("nothing of tier " + tier + " ever joined");
    }

    private FakeClient join() {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                SavedCharacter.fresh("ala", "Ala", ARENA.id(), 6, 6, Direction.DOWN), 0));
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

    private final class FakeClient implements Client {

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
            return await(match, 1);
        }

        /** Waits until at least {@code times} frames match. */
        boolean await(Predicate<String> match, int times) {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (frames().stream().filter(match).count() >= times) {
                    return true;
                }
                sleep(20);
            }
            return false;
        }

        boolean awaitCount(java.util.function.ToIntFunction<FakeClient> counter, int wanted) {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (counter.applyAsInt(this) >= wanted) {
                    return true;
                }
                sleep(20);
            }
            return false;
        }
    }
}
