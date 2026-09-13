package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.MobDefLoader;
import com.kowihere.mmo.world.SpawnPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The map with its creatures actually running.
 *
 * <p>Separate from {@link MapRunnerTest} on purpose: those tests run a world
 * with no creatures, because a wandering mob emits movement of its own and
 * would let an assertion about a player's movement pass by accident. Here the
 * creatures are the subject, so they are switched on.
 */
class MobsInTheWorldTest {

    private static final MapDef MAP = new MapDefLoader().loadAll().get("starter");
    private static final Map<String, MobDef> MOBS = new MobDefLoader().loadAll();
    private static final long TIMEOUT_MS = 8_000;
    private static final long ACCOUNT = 1L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        // A two-second grace period rather than the real thirty, so the reaper
        // test below finishes in this decade.
        runner = new MapRunner(MAP, new ObjectMapper(), saved, MOBS, 20);
        thread = new Thread(runner, "test-map-mobs");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void creaturesAreOnTheMapFromTheFirstTick() {
        FakeClient client = join("Ala", MAP.spawnX(), MAP.spawnY());

        String init = client.await("\"type\":\"init\"");

        assertThat(init)
                .as("the map's marked population should already be standing there")
                .contains("\"kind\":\"MOB\"");
        assertThat(countOccurrences(init, "\"kind\":\"MOB\""))
                .isEqualTo(MAP.spawns().size());
    }

    @Test
    void aCreatureCarriesItsTierSoTheClientCanTellThemApart() {
        FakeClient client = join("Ala", MAP.spawnX(), MAP.spawnY());

        assertThat(client.await("\"type\":\"init\"")).contains("\"tier\":\"MOB\"");
    }

    @Test
    void aPlayerCarriesNoTierAtAll() {
        FakeClient client = join("Ala", MAP.spawnX(), MAP.spawnY());
        String init = client.await("\"type\":\"init\"");

        int players = countOccurrences(init, "\"kind\":\"PLAYER\"");
        int tiers = countOccurrences(init, "\"tier\"");

        assertThat(players).isEqualTo(1);
        assertThat(tiers)
                .as("a tier belongs to a creature; a player should not carry an empty one")
                .isEqualTo(MAP.spawns().size());
    }

    @Test
    void creaturesMoveWithoutAnybodyTellingThemTo() {
        FakeClient client = join("Ala", MAP.spawnX(), MAP.spawnY());
        client.frames().clear();

        // No commands are sent at all. Anything that moves, moved by itself.
        assertThat(client.await(f -> f.contains("\"moved\"")))
                .as("the map should be visibly alive on its own")
                .isTrue();
    }

    @Test
    void aCreatureWalksTowardsAPlayerWhoComesClose() throws Exception {
        SpawnPoint post = MAP.spawns().get(0);
        MobDef guard = MOBS.get(post.mobId());
        int[] playerTile = {post.x() + guard.aggroRadius() - 1, post.y()};
        FakeClient client = join("Ala", playerTile[0], playerTile[1]);

        int guardId = creatureAt(client.await("\"type\":\"init\""), post.x(), post.y());
        int[] home = {post.x(), post.y()};
        int startDistance = chebyshev(home, playerTile);

        // Waiting for any "moved" would be satisfied by some other creature
        // wandering elsewhere, so follow this one specifically and require that
        // it actually closed the gap.
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int closest = startDistance;
        while (System.currentTimeMillis() < deadline && closest >= startDistance) {
            sleep(100);
            closest = chebyshev(lastKnownPosition(client, guardId, home), playerTile);
        }

        assertThat(closest)
                .as("'%s' stood %d tiles away and should have closed in", guard.name(), startDistance)
                .isLessThan(startDistance);
    }

    @Test
    void creaturesAreNeverHandedToPersistence() throws InterruptedException {
        // A creature is derived from map data, so saving one would mean writing
        // a row whose foreign key points at an account it does not have. The
        // failure would surface as a constraint violation, far from the cause.
        //
        // Shutting the map down is the case that matters and the one easiest to
        // miss: the periodic save only ever looks at actors marked dirty, which
        // a creature never is - but the final flush marks EVERYTHING dirty on
        // its way out. A version of this test that merely detached a player
        // passed with the guard removed.
        FakeClient client = join("Ala", MAP.spawnX(), MAP.spawnY());
        runner.submit(new Command.MoveTo(client, MAP.spawnX() - 2, MAP.spawnY()));
        sleep(1_500);

        runner.stop();
        thread.join(2_000);

        assertThat(saved.snapshots)
                .as("the player's last position should still be kept")
                .isNotEmpty();
        assertThat(saved.snapshots)
                .allSatisfy(snapshot -> assertThat(snapshot.nameKey()).doesNotStartWith("mob:"));
    }

    @Test
    void creaturesAreNotSweptAwayByTheDisconnectReaper() {
        // A creature has no socket, so by the player rules it looks permanently
        // disconnected. Without an explicit exception the map would empty itself
        // one grace period after starting, and only then.
        FakeClient client = join("Ala", MAP.spawnX(), MAP.spawnY());
        assertThat(client.await("\"type\":\"init\"")).contains("\"kind\":\"MOB\"");

        sleep(3_000); // comfortably past the 20-tick grace period used here

        FakeClient later = join("Bogumil", MAP.spawnX(), MAP.spawnY());
        assertThat(countOccurrences(later.await("\"type\":\"init\""), "\"kind\":\"MOB\""))
                .as("every creature should still be there")
                .isEqualTo(MAP.spawns().size());
    }

    @Test
    void theWholeMapCannotSpendMoreThanItsPathBudget() {
        // Measured, not assumed. Creatures sit outside the per-player limit, so
        // without a shared cap a crowd of chasers would run one A* each per tick
        // and delay the tick for every player on the map.
        join("Ala", MAP.spawnX(), MAP.spawnY());
        long before = runner.pathSearches();

        sleep(2_000); // roughly 20 ticks

        long searches = runner.pathSearches() - before;
        assertThat(searches)
                .as("20 ticks at a budget of 4 leaves ample headroom; %d would mean no cap", searches)
                .isLessThanOrEqualTo(20L * 4);
    }

    // ------------------------------------------------------------------

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int from = 0; (from = haystack.indexOf(needle, from)) >= 0; from += needle.length()) {
            count++;
        }
        return count;
    }

    /** The id of the creature standing on a given tile, read out of an init frame. */
    private static int creatureAt(String initFrame, int x, int y) throws Exception {
        for (JsonNode actor : JSON.readTree(initFrame).path("actors")) {
            if ("MOB".equals(actor.path("kind").asText())
                    && actor.path("x").asInt() == x && actor.path("y").asInt() == y) {
                return actor.path("id").asInt();
            }
        }
        throw new AssertionError("no creature at " + x + "," + y);
    }

    /** Where one actor ended up, according to every delta seen so far. */
    private static int[] lastKnownPosition(FakeClient client, int actorId, int[] fallback)
            throws Exception {
        int[] position = fallback;
        for (String frame : client.frames()) {
            for (JsonNode move : JSON.readTree(frame).path("moved")) {
                if (move.path("id").asInt() == actorId) {
                    position = new int[]{move.path("x").asInt(), move.path("y").asInt()};
                }
            }
        }
        return position;
    }

    private static int chebyshev(int[] a, int[] b) {
        return Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
    }

    private FakeClient join(String name, int x, int y) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, ACCOUNT,
                new SavedCharacter(PlayerNames.key(name), name, MAP.id(), x, y, Direction.DOWN), 0));
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

        String await(String needle) {
            assertThat(await(f -> f.contains(needle))).as("frame containing %s", needle).isTrue();
            return frames.stream().filter(f -> f.contains(needle)).findFirst().orElseThrow();
        }
    }
}
