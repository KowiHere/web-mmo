package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.MobDefLoader;
import com.kowihere.mmo.world.NpcDefLoader;
import com.kowihere.mmo.world.Respawns;
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
 * Where a body is sent, decided by the map holding it.
 *
 * <p>{@link Respawns} narrows the world down to the waking places fewest
 * passages away and says which door leads to each. When two are equally far
 * off, the choice is the last thing only this map knows: how far the body is
 * from each of those doors.
 *
 * <p>The field here has a chapel one passage west and another one passage east,
 * and a killer standing at each end of it. Whoever dies in the west goes west.
 */
class WakingUpNearestTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    private static final Map<String, MapDef> WORLD = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"), new NpcDefLoader(),
            "classpath:test-maps-respawn-tie/*.json", Map.of()).loadAll();
    private static final Content CONTENT =
            new Content(MOBS, new ItemDefLoader("classpath:test-items/*.json").loadAll());

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 40_000;

    private MapRunner runner;
    private Thread thread;
    private String mapId = "pole";
    private final List<String[]> handovers = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startMap() {
        startMap("pole");
    }

    /** Starts a different map of the same world, for the tests that need one. */
    private void startMap(String id) {
        if (runner != null) {
            runner.stop();
        }
        mapId = id;
        runner = new MapRunner(WORLD.get(id), JSON, WorldPersistence.NONE, CONTENT, 20);
        runner.wakesDeadBy(Respawns.of(WORLD));
        runner.transfersThrough((client, toMapId, accountId, character) ->
                handovers.add(new String[]{character.name(), toMapId,
                        character.x() + "," + character.y()}));
        thread = new Thread(runner, "test-waking-up-" + id);
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void whoeverDiesInTheWestWakesInTheWesternChapel() throws Exception {
        FakeClient west = join("Zosia", 3, 4);
        killedBy(west, nearest(west, 3));

        assertThat(handoverFor("Zosia"))
                .as("one passage either way, and the western door is two steps from the body")
                .isEqualTo("kapliczka-zachod");
    }

    @Test
    void andWhoeverDiesInTheEastWakesInTheEasternOne() throws Exception {
        // The same map, the same two chapels, the other end of the field. If
        // the choice were made once at startup rather than over the body, both
        // of these would give the same answer and one of them would be wrong.
        FakeClient east = join("Hania", 8, 4);
        killedBy(east, nearest(east, 8));

        assertThat(handoverFor("Hania")).isEqualTo("kapliczka-wschod");
    }

    @Test
    void aMapThatWroteItDownIsObeyedEvenWhenSomewhereIsNearer() throws Exception {
        // The waste has a chapel one passage east and says, in its own file,
        // that its dead go west. An author who wrote it down meant it - the
        // search is for maps that said nothing.
        startMap("manowiec");
        FakeClient lost = join("Jaga", 5, 4);
        killedBy(lost, nearest(lost, 5));

        assertThat(handoverFor("Jaga")).isEqualTo("kapliczka-zachod");
    }

    @Test
    void aDoorNothingCanWalkToLosesTheTieRatherThanWinningIt() throws Exception {
        // Two chapels, one passage each, and the western door is behind a wall
        // this body could never have reached. Measured carelessly, "no way
        // there" comes back as no distance at all and wins every tie.
        startMap("wyspa");
        FakeClient stranded = join("Marta", 8, 4);
        killedBy(stranded, nearest(stranded, 8));

        assertThat(handoverFor("Marta")).isEqualTo("kapliczka-wschod");
    }

    @Test
    void theBodyIsSentToTheTileTheChapelNames() throws Exception {
        FakeClient west = join("Ula", 3, 4);
        killedBy(west, nearest(west, 3));
        handoverFor("Ula"); // waits for the body to be handed over at all

        assertThat(handovers).anySatisfy(handover -> {
            assertThat(handover[0]).isEqualTo("Ula");
            assertThat(handover[2])
                    .as("the waking place the chapel wrote down, not its spawn")
                    .isEqualTo("2,2");
        });
    }

    // ------------------------------------------------------------------

    private String handoverFor(String name) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (String[] handover : handovers) {
                if (handover[0].equals(name)) {
                    return handover[1];
                }
            }
            sleep(50);
        }
        throw new AssertionError(name + " was never handed anywhere");
    }

    private void killedBy(FakeClient client, int mobId) {
        runner.submit(new Command.Attack(client, mobId));
    }

    /** The killer nearer this end of the field. */
    private int nearest(FakeClient client, int x) throws Exception {
        JsonNode init = JSON.readTree(client.await("\"type\":\"init\""));
        int best = -1;
        int distance = Integer.MAX_VALUE;
        for (JsonNode actor : init.path("actors")) {
            if (!"MOB".equals(actor.path("kind").asText())) {
                continue;
            }
            int away = Math.abs(actor.path("x").asInt() - x);
            if (away < distance) {
                distance = away;
                best = actor.path("id").asInt();
            }
        }
        assertThat(best).as("no creature to be killed by").isNotNegative();
        return best;
    }

    private FakeClient join(String name, int x, int y) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, name.hashCode(),
                new SavedCharacter(PlayerNames.key(name), name, mapId, x, y, Direction.DOWN,
                        1, 0L, 20, 0L, Attributes.FRESH, 0, List.of(), null, 1,
                        List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY, List.of()), 0));
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
