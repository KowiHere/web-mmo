package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.CombatRules;
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
 * What being killed costs now: the character lies where it was put and cannot
 * be played until it comes round.
 *
 * <p>This replaced a minute of halved attack and armour, which had only ever
 * been a stand-in. The tests that matter are the ones about refusal — a penalty
 * that can be walked out of is not a penalty, and the way it would be walked
 * out of is a handler somebody forgot to guard.
 *
 * <p>Characters are started already unconscious rather than killed first. A
 * fight to the death takes a dozen seconds of real time and settles nothing
 * these are asking about, and the stamp is the whole mechanism either way.
 */
class BeingKnockedOutTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    private static final Content CONTENT = new Content(MOBS,
            new ItemDefLoader("classpath:test-items/*.json").loadAll());
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-combat/*.json").loadAll().get("arena-walki");
    /** A hunter that reaches everywhere, and room to lie down far from the spawn. */
    private static final MapDef DEN = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-knocked/*.json").loadAll().get("legowisko");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 10_000;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(ARENA, JSON, saved, CONTENT, 20);
        thread = new Thread(runner, "test-knocked-out");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    // ---- nothing works -------------------------------------------------

    @Test
    void anUnconsciousCharacterCannotWalkAway() throws Exception {
        FakeClient client = joinOut("Ala", 5, 5, 60);
        int[] before = selfPosition(client);

        runner.submit(new Command.MoveTo(client, 7, 2));
        sleep(1_200);

        assertThat(client.frames()).anyMatch(f -> f.contains("ocknęłaś"));
        assertThat(selfPosition(client))
                .as("a penalty you can walk out of is not a penalty")
                .containsExactly(before[0], before[1]);
    }

    @Test
    void everythingElseIsRefusedToo() throws Exception {
        // One gate covers all of these, and the reason it is one gate rather
        // than a check in each handler is exactly this list: nine places to
        // forget, and the tenth handler is always the one somebody finds.
        FakeClient client = joinOut("Ala", 5, 5, 60);
        int creature = creatureNamed(client.await("\"type\":\"init\""), "Slabeusz");

        runner.submit(new Command.Attack(client, creature));
        runner.submit(new Command.Flee(client));
        runner.submit(new Command.Spend(client, Attributes.Attribute.STRENGTH));
        runner.submit(new Command.Learn(client, "regeneracja"));
        runner.submit(new Command.Use(client, "regeneracja"));
        runner.submit(new Command.Talk(client, creature));
        runner.submit(new Command.Choose(client, 0));
        runner.submit(new Command.Buy(client, "probny-miecz"));
        runner.submit(new Command.Sell(client, "whatever"));
        sleep(1_200);

        assertThat(inFight(client)).as("nothing started a fight").isFalse();
        assertThat(client.frames())
                .as("and none of it did anything but say why not")
                .noneMatch(f -> f.contains("\"damage\"") || f.contains("\"type\":\"shop\"")
                        || f.contains("\"type\":\"dialogue\""));
        assertThat(numberIn(latestYou(client), "strength"))
                .isEqualTo(Attributes.STARTING);
    }

    @Test
    void butThePlayerCanStillSpeak() throws Exception {
        // The character is unconscious; the person at the keyboard is not.
        // Three minutes with no way to say "back shortly" punishes the wrong
        // one of the two.
        FakeClient client = joinOut("Ala", 5, 5, 60);

        runner.submit(new Command.Chat(client, "zaraz wracam"));

        assertThat(client.await(f -> f.contains("zaraz wracam"))).isTrue();
    }

    @Test
    void nothingWillPickOnSomebodyWhoCannotFightBack() throws Exception {
        // On a map of its own, because this cannot be asked at a spawn: nothing
        // aggravates within four tiles of one, so a body lying there is spared
        // by a rule that has nothing to do with being unconscious. Here the
        // body lies eight tiles away, next to a hunter whose reach covers the
        // whole map, so the only thing that can save it is the rule under test.
        MapRunner den = new MapRunner(DEN, JSON, WorldPersistence.NONE, CONTENT, 20);
        Thread denThread = new Thread(den, "test-den");
        denThread.setDaemon(true);
        denThread.start();
        try {
            FakeClient client = joinTo(den, DEN, out(DEN, "Ala", 9, 7,
                    System.currentTimeMillis() + 60_000));

            sleep(4_000);

            assertThat(client.frames()).noneMatch(f -> f.contains("\"damage\""));
        } finally {
            den.stop();
            denThread.join(2_000);
        }
    }

    @Test
    void whereasSomebodyAwakeInTheSamePlaceIsSetUpon() throws Exception {
        // The control. Without it the test above would pass just as happily on
        // a map where nothing attacks anybody, and would be proving that the
        // fixture is quiet rather than that the rule works.
        MapRunner den = new MapRunner(DEN, JSON, WorldPersistence.NONE, CONTENT, 20);
        Thread denThread = new Thread(den, "test-den-awake");
        denThread.setDaemon(true);
        denThread.start();
        try {
            FakeClient client = joinTo(den, DEN, out(DEN, "Bob", 9, 7, 0L));

            assertThat(client.await(f -> f.contains("\"damage\"")))
                    .as("the hunter does reach this tile, and does use it")
                    .isTrue();
        } finally {
            den.stop();
            denThread.join(2_000);
        }
    }

    // ---- and then it does ----------------------------------------------

    @Test
    void itComesRoundOnItsOwnAndSaysSo() throws Exception {
        FakeClient client = joinOut("Ala", 5, 5, 1);

        // Nothing is sent to ask. The map notices the moment passing.
        assertThat(client.await(f -> f.contains("\"type\":\"you\"") && f.contains("\"wakesAt\":0")))
                .as("the client has to be told, or the overlay never lifts")
                .isTrue();

        runner.submit(new Command.MoveTo(client, 5, 3));
        assertThat(client.await(f -> f.contains("\"y\":3")))
                .as("and then walking works again")
                .isTrue();
    }

    @Test
    void everybodyElseSeesTheBodyAndSeesItGetUp() throws Exception {
        // Without this on the wire, somebody lying at the spawn looks exactly
        // like somebody whose connection has dropped.
        FakeClient watcher = join("Bob", 7, 2);
        FakeClient client = joinOut("Ala", 5, 5, 1);

        assertThat(watcher.await(f -> f.contains("\"name\":\"Ala\"")
                && f.contains("\"unconscious\":true"))).isTrue();
        assertThat(watcher.await(f -> f.contains("\"name\":\"Ala\"")
                && f.contains("\"unconscious\":false")))
                .as("and sees them get up again")
                .isTrue();
    }

    // ---- and it cannot be dodged ---------------------------------------

    @Test
    void leavingAndComingBackDoesNotShortenIt() throws Exception {
        // A countdown held by the client, or restarted on joining, would make
        // closing the tab the fastest way out of this.
        FakeClient client = joinOut("Ala", 5, 5, 120);
        long told = longIn(latestYou(client), "wakesAt");

        runner.submit(new Command.Detach(client));
        sleep(400);
        FakeClient again = new FakeClient();
        runner.submit(new Command.Join(again, 1L, out("Ala", 5, 5, told), 0));
        assertThat(again.await(f -> f.contains("\"type\":\"init\""))).isTrue();

        assertThat(longIn(latestYou(again), "wakesAt"))
                .as("the same moment, not a fresh count from now")
                .isEqualTo(told);
        runner.submit(new Command.MoveTo(again, 7, 2));
        sleep(800);
        assertThat(again.frames()).anyMatch(f -> f.contains("ocknęłaś"));
    }

    // Whether the stamp reaches the database is asserted where a character
    // actually dies - see CombatInTheWorldTest. Started unconscious, as these
    // are, nothing has changed since the character was loaded, so there would
    // be nothing to save and the test would be about its own fixture.

    // ------------------------------------------------------------------

    private static SavedCharacter out(String name, int x, int y, long wakesAt) {
        return out(ARENA, name, x, y, wakesAt);
    }

    /**
     * @param where which map the position belongs to. Not decoration: a stored
     *              position is only honoured on the map it was stored on, so
     *              naming the wrong one puts the character on the spawn - where
     *              nothing aggravates, which would have quietly made two of
     *              these tests about a tile nobody was standing on.
     */
    private static SavedCharacter out(MapDef where, String name, int x, int y, long wakesAt) {
        return new SavedCharacter(PlayerNames.key(name), name, where.id(), x, y, Direction.DOWN,
                1, 0L, CombatRules.HEALTH_AFTER_DEATH, wakesAt, Attributes.FRESH, 0,
                List.of(), null, 0, List.of(), List.of());
    }

    private FakeClient joinTo(MapRunner map, MapDef where, SavedCharacter character) {
        FakeClient client = new FakeClient();
        map.submit(new Command.Join(client, 1L, character, 0));
        String init = client.await("\"type\":\"init\"");
        assertThat(init)
                .as("%s should have been placed where it fell, not on the spawn", character.name())
                .contains("\"name\":\"" + character.name() + "\",\"x\":" + character.x()
                        + ",\"y\":" + character.y());
        return client;
    }

    private FakeClient joinOut(String name, int x, int y, long seconds) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                out(name, x, y, System.currentTimeMillis() + seconds * 1000L), 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    private FakeClient join(String name, int x, int y) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                SavedCharacter.fresh(PlayerNames.key(name), name, ARENA.id(), x, y, Direction.DOWN),
                0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    private int[] selfPosition(FakeClient client) throws Exception {
        int self = JSON.readTree(client.await("\"type\":\"init\"")).path("selfId").asInt();
        int[] at = null;
        for (String frame : client.frames()) {
            JsonNode node = JSON.readTree(frame);
            for (JsonNode actor : node.path("actors")) {
                if (actor.path("id").asInt() == self) {
                    at = new int[]{actor.path("x").asInt(), actor.path("y").asInt()};
                }
            }
            for (JsonNode moved : node.path("moved")) {
                if (moved.path("id").asInt() == self) {
                    at = new int[]{moved.path("x").asInt(), moved.path("y").asInt()};
                }
            }
        }
        return at;
    }

    private boolean inFight(FakeClient client) throws Exception {
        int self = JSON.readTree(client.await("\"type\":\"init\"")).path("selfId").asInt();
        boolean fighting = false;
        for (String frame : client.frames()) {
            for (JsonNode change : JSON.readTree(frame).path("fights")) {
                if (change.path("id").asInt() == self) {
                    fighting = change.path("inFight").asBoolean();
                }
            }
        }
        return fighting;
    }

    private static int creatureNamed(String initFrame, String name) throws Exception {
        for (JsonNode actor : JSON.readTree(initFrame).path("actors")) {
            if (name.equals(actor.path("name").asText())) {
                return actor.path("id").asInt();
            }
        }
        throw new AssertionError("no creature called " + name);
    }

    private String latestYou(FakeClient client) {
        List<String> frames = client.frames();
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (frames.get(i).contains("\"type\":\"you\"")) {
                return frames.get(i);
            }
        }
        throw new AssertionError("no own-state frame was ever sent");
    }

    private static int numberIn(String frame, String field) {
        return (int) longIn(frame, field);
    }

    private static long longIn(String frame, String field) {
        try {
            return JSON.readTree(frame).path(field).asLong();
        } catch (Exception e) {
            throw new AssertionError("unreadable frame " + frame, e);
        }
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
