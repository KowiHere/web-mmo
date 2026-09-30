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
 * A pack stands where the map put it.
 *
 * <p>Elites used to turn up somewhere unpredictable, every so often, with an
 * escort in tow - and the bookkeeping that kept the map from filling with
 * wolves was the hardest part of it. All of that is gone. A pack is a creature
 * with a group written beside it, worked out into ordinary spawn points while
 * the map is read, so by the time anything runs there is no such thing as a
 * pack: only creatures standing where they were put, each with a tile of its
 * own to come back to.
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
    void thePackIsStandingThereFromTheFirstTick() throws Exception {
        // Not "turns up within a minute": it is part of the map, like a tree.
        FakeClient client = join();

        String init = awaitInit(client);
        assertThat(standing(init, "Krolowa"))
                .as("the queen, on the tile the map names")
                .containsExactly("11,2");
        assertThat(standing(init, "Ciura"))
                .as("and both of her own, on tiles beside her")
                .hasSize(2);
        for (String escort : standing(init, "Ciura")) {
            int x = Integer.parseInt(escort.split(",")[0]);
            int y = Integer.parseInt(escort.split(",")[1]);
            assertThat(Math.max(Math.abs(x - 11), Math.abs(y - 2)))
                    .as("an escort at %s is not beside her", escort)
                    .isLessThanOrEqualTo(2);
        }
    }

    @Test
    void eachOfThemComesBackToItsOwnTile() throws Exception {
        // The whole of respawning, now that there is nothing else: kill one,
        // and after its own count it is standing where it stood. No pack, no
        // schedule, no refilling of anything.
        FakeClient client = join();
        String init = awaitInit(client);
        int queen = idOfTier(client, "ELITE");
        String where = standing(init, "Krolowa").get(0);

        runner.submit(new Command.Attack(client, queen));
        assertThat(client.await(frame -> frame.contains("\"died\":[" + queen)))
                .as("she has ten health; she should not last")
                .isTrue();

        assertThat(client.await(frame -> joinedAt(frame, "Krolowa", where)))
                .as("and comes back to the same tile, a second later")
                .isTrue();
    }

    @Test
    void aPackIsPlacedNowhereNearWhereCharactersArrive() throws Exception {
        // Otherwise logging in, and coming back from a death, both mean waking
        // up inside a pack. It is content's job now rather than the runner's -
        // which is the point: a map is checked by looking at it.
        FakeClient client = join();
        String init = awaitInit(client);

        for (JsonNode actor : JSON.readTree(init).path("actors")) {
            if (!"MOB".equals(actor.path("kind").asText())) {
                continue;
            }
            int distance = Math.max(Math.abs(actor.path("x").asInt() - ARENA.spawnX()),
                    Math.abs(actor.path("y").asInt() - ARENA.spawnY()));
            assertThat(distance)
                    .as("%s stands %d tiles from the spawn", actor.path("name").asText(), distance)
                    .isGreaterThan(MobBehaviour.SAFE_RADIUS);
        }
    }

    /** The init frame, once it has actually arrived. */
    private String awaitInit(FakeClient client) {
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client.frames().stream().filter(f -> f.contains("\"type\":\"init\""))
                .findFirst().orElseThrow();
    }

    /** Where every creature of that name is standing, as "x,y". */
    private List<String> standing(String initFrame, String name) throws Exception {
        List<String> where = new ArrayList<>();
        for (JsonNode actor : JSON.readTree(initFrame).path("actors")) {
            if (name.equals(actor.path("name").asText())) {
                where.add(actor.path("x").asInt() + "," + actor.path("y").asInt());
            }
        }
        return where;
    }

    private boolean joinedAt(String frame, String name, String tile) {
        try {
            for (JsonNode actor : JSON.readTree(frame).path("joined")) {
                if (name.equals(actor.path("name").asText())
                        && tile.equals(actor.path("x").asInt() + "," + actor.path("y").asInt())) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
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
