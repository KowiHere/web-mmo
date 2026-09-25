package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDefLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a map says about its players to anything that is not a map.
 *
 * <p>One call a tick, going one way. Nothing is read back, because a party can
 * span three maps and a tick that asked another map a question would be a tick
 * waiting on somebody else's writer.
 */
class PublishingVitalsTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs-items/*.json", ITEMS),
            "classpath:test-maps-items/*.json").loadAll().get("skarbiec");
    private static final Content CONTENT = new Content(
            new MobDefLoader("classpath:test-mobs-items/*.json", ITEMS).loadAll(), ITEMS);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, Object[]> published = new ConcurrentHashMap<>();
    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        runner = new MapRunner(ARENA, JSON, WorldPersistence.NONE, CONTENT, 20);
        runner.publishesTo((nameKey, name, level, hp, maxHp, mapId, mapName, x, y, online) ->
                published.put(nameKey, new Object[]{name, level, hp, maxHp, mapId, mapName, online}));
        thread = new Thread(runner, "test-vitals");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void everyPlayerIsPublishedWithTheMapTheyAreOn() {
        join("Ala");

        await(() -> published.containsKey("ala"));

        Object[] vitals = published.get("ala");
        assertThat(vitals[0]).isEqualTo("Ala");
        assertThat(vitals[4]).isEqualTo("skarbiec");
        assertThat(vitals[5]).isEqualTo("Skarbiec");
        assertThat(vitals[6]).as("and whether their socket is still there").isEqualTo(true);
    }

    @Test
    void creaturesAreNobodysTeammates() {
        join("Ala");
        await(() -> published.containsKey("ala"));

        assertThat(published.keySet())
                .as("a party panel with a boar in it would be a party panel about the map")
                .containsExactly("ala");
    }

    @Test
    void aDroppedSocketIsSaidOutLoudRatherThanGoingQuiet() {
        FakeClient client = join("Ala");
        await(() -> published.containsKey("ala"));

        runner.submit(new Command.Detach(client));

        await(() -> Boolean.FALSE.equals(published.get("ala")[6]));
        assertThat(published.get("ala")[6])
                .as("the seat is held while the character stands there, and the panel says why")
                .isEqualTo(false);
    }

    private FakeClient join(String name) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L, new SavedCharacter(PlayerNames.key(name), name,
                ARENA.id(), 5, 5, Direction.DOWN, 3, 0L, -1, 0L, Attributes.FRESH, 0,
                List.of(), null, 1, List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY), 0));
        return client;
    }

    private static void await(java.util.function.BooleanSupplier until) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (until.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("nothing was published in time");
    }

    private static final class FakeClient implements Client {
        @Override public void send(String json) { }
        @Override public void disconnect(String reason) { }
        @Override public String describe() { return "fake"; }
    }
}
