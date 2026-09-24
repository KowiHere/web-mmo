package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.CurrencyDefLoader;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDefLoader;
import com.kowihere.mmo.world.NpcDefLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walking from one map to another, with both of them running.
 *
 * <p>This is the first test in the project with two map threads in it, and the
 * one place the single-writer rule could be broken: everything crossing between
 * them is an immutable {@link SavedCharacter} and a socket, and if that were not
 * true, the rule would have been a comment rather than a design.
 *
 * <p>The two maps are wired to each other here the way the world wires them,
 * so what is under test is the handover and not a mock of it.
 */
class WalkingThroughDoorsTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final Map<String, MapDef> MAPS = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json", ITEMS),
            new NpcDefLoader("classpath:test-npcs/*.json", ITEMS,
                    new CurrencyDefLoader("classpath:test-currencies/*.json").loadAll()),
            "classpath:test-maps-doors/*.json", ITEMS).loadAll();
    private static final Content CONTENT = new Content(
            new MobDefLoader("classpath:test-mobs/*.json", ITEMS).loadAll(), ITEMS);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 10_000;

    private final Map<String, MapRunner> runners = new HashMap<>();
    private final List<Thread> threads = new ArrayList<>();
    private final Map<Client, MapRunner> whereTheyAre = new HashMap<>();
    private RecordingPersistence saved;

    @BeforeEach
    void startTheWorld() {
        saved = new RecordingPersistence();
        for (MapDef def : MAPS.values()) {
            MapRunner runner = new MapRunner(def, JSON, saved, CONTENT, 20);
            runner.transfersThrough(this::handOver);
            runners.put(def.id(), runner);
        }
        for (MapRunner runner : runners.values()) {
            Thread thread = new Thread(runner, "test-" + runner.mapId());
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
    }

    @AfterEach
    void stopTheWorld() throws InterruptedException {
        runners.values().forEach(MapRunner::stop);
        for (Thread thread : threads) {
            thread.join(2_000);
        }
    }

    /** What WorldService does, in miniature: point the socket, then queue a join. */
    private synchronized void handOver(Client client, String toMapId, long accountId,
                                       SavedCharacter character) {
        MapRunner destination = runners.get(toMapId);
        whereTheyAre.put(client, destination);
        destination.submit(new Command.Join(client, accountId, character, 0L));
    }

    private synchronized MapRunner mapOf(Client client) {
        return whereTheyAre.get(client);
    }

    // ---- the handover --------------------------------------------------

    @Test
    void steppingOnADoorLandsYouOnTheOtherMap() throws Exception {
        FakeClient client = join("Ala", 7, 2, 1);

        walkTo(client, 8, 2);

        assertThat(client.await(f -> f.contains("\"type\":\"init\"") && f.contains("\"id\":\"ogrod\"")))
                .as("arriving somewhere else is a fresh view of the world")
                .isTrue();
        assertThat(selfIn(latest(client, "\"type\":\"init\"")))
                .containsExactly(2, 6);
    }

    @Test
    void everythingWorthKeepingComesWithYou() throws Exception {
        // The whole of the character crosses between two threads, so the whole
        // of it is what this checks: a copy that lost the purse would look
        // exactly like a working transfer until somebody tried to buy something.
        FakeClient client = join(carrying("Ala", 7, 2, 4));

        walkTo(client, 8, 2);
        assertThat(client.await(f -> f.contains("\"id\":\"ogrod\""))).isTrue();

        assertThat(numberIn(latest(client, "\"type\":\"you\""), "level")).isEqualTo(4);
        assertThat(numberIn(latest(client, "\"type\":\"you\""), "hp")).isEqualTo(33);
        assertThat(latest(client, "\"type\":\"bag\"")).contains("probny-miecz");
        assertThat(latest(client, "\"type\":\"purse\"")).contains("\"amount\":77");
        assertThat(latest(client, "\"type\":\"skills\"")).contains("\"rank\":3");
    }

    @Test
    void theNewMapIsTheOneThatListensAfterwards() throws Exception {
        FakeClient client = join("Ala", 7, 2, 1);
        walkTo(client, 8, 2);
        assertThat(client.await(f -> f.contains("\"id\":\"ogrod\""))).isTrue();

        // Sent the way the handler sends it: to whichever map the signpost says.
        mapOf(client).submit(new Command.MoveTo(client, 6, 6));

        assertThat(client.await(f -> f.contains("\"x\":6,\"y\":6")))
                .as("a command after the move has to reach the map they are on")
                .isTrue();
    }

    @Test
    void theOldMapLetsGoOfThem() throws Exception {
        FakeClient watcher = join("Bob", 2, 3, 1);
        FakeClient client = join("Ala", 7, 2, 1);
        assertThat(watcher.await(f -> f.contains("\"name\":\"Ala\""))).isTrue();

        walkTo(client, 8, 2);

        assertThat(watcher.await(f -> f.contains("\"left\":")))
                .as("everyone left behind should see them go")
                .isTrue();
    }

    @Test
    void whereTheyWereGoingIsWrittenDownBeforeTheyGo() throws Exception {
        // Written first on purpose. A crash between the two leaves a row saying
        // where they were headed, which is recoverable; the other order leaves
        // one saying where they are not.
        FakeClient client = join("Ala", 7, 2, 1);

        walkTo(client, 8, 2);
        assertThat(client.await(f -> f.contains("\"id\":\"ogrod\""))).isTrue();

        assertThat(saved.snapshots).anySatisfy(snapshot -> {
            assertThat(snapshot.nameKey()).isEqualTo("ala");
            assertThat(snapshot.mapId()).isEqualTo("ogrod");
            assertThat(snapshot.x()).isEqualTo(2);
            assertThat(snapshot.y()).isEqualTo(6);
        });
    }

    // ---- thresholds ----------------------------------------------------

    @Test
    void aDoorThatOpensLaterSaysSoAndKeepsYouWhereYouAre() throws Exception {
        FakeClient client = join("Ala", 7, 4, 1);

        walkTo(client, 8, 4);
        sleep(600);

        assertThat(client.frames()).anyMatch(f -> f.contains("od 5 poziomu"));
        assertThat(client.frames()).noneMatch(f -> f.contains("\"id\":\"ogrod\""));
        assertThat(selfNow(client)).containsExactly(8, 4);
    }

    @Test
    void aDoorForBeginnersCloseBehindThem() throws Exception {
        FakeClient client = join("Ala", 7, 5, 9);

        walkTo(client, 8, 5);
        sleep(600);

        assertThat(client.frames()).anyMatch(f -> f.contains("najwyżej 2 poziomu"));
        assertThat(client.frames()).noneMatch(f -> f.contains("\"id\":\"ogrod\""));
    }

    @Test
    void aLockedDoorNamesWhatWouldOpenIt() throws Exception {
        FakeClient client = join("Ala", 7, 6, 1);

        walkTo(client, 8, 6);
        sleep(600);

        assertThat(client.frames())
                .anyMatch(f -> f.contains("Potrzebujesz") && f.contains("Próbny miecz"));
        assertThat(client.frames()).noneMatch(f -> f.contains("\"id\":\"ogrod\""));
    }

    @Test
    void theKeyOpensItAndStaysInTheBag() throws Exception {
        // A key, not a ticket. A door that eats what opened it can be walked
        // through exactly once, which is nobody's idea of a key.
        FakeClient client = join(carrying("Ala", 7, 6, 1));

        walkTo(client, 8, 6);

        assertThat(client.await(f -> f.contains("\"id\":\"ogrod\""))).isTrue();
        assertThat(latest(client, "\"type\":\"bag\""))
                .as("the key should still be there on the other side")
                .contains("probny-miecz");
    }

    // ---- the passage that burns a ticket --------------------------------

    @Test
    void aPassageThatTakesSomethingAsksBeforeItOpens() throws Exception {
        FakeClient client = join(carrying("Ala", 7, 7, 1));

        walkTo(client, 8, 7);

        assertThat(client.await(f -> f.contains("\"type\":\"passage\"")
                && f.contains("Próbny miecz"))).isTrue();
        sleep(400);
        assertThat(client.frames())
                .as("and does not move anybody until they say so")
                .noneMatch(f -> f.contains("\"id\":\"ogrod\""));
        assertThat(latest(client, "\"type\":\"bag\"")).contains("probny-miecz");
    }

    @Test
    void sayingYesBurnsItAndOpens() throws Exception {
        FakeClient client = join(carrying("Ala", 7, 7, 1));
        walkTo(client, 8, 7);
        assertThat(client.await(f -> f.contains("\"type\":\"passage\""))).isTrue();

        mapOf(client).submit(new Command.Pass(client, 8, 7));

        assertThat(client.await(f -> f.contains("\"id\":\"ogrod\""))).isTrue();
        assertThat(latest(client, "\"type\":\"bag\""))
                .as("the ticket is gone, unlike the key one tile up")
                .doesNotContain("probny-miecz");
    }

    @Test
    void whatIsWrittenDownIsTheBagWithoutTheTicket() throws Exception {
        // The snapshot is taken as the character leaves, so a ticket burned on
        // the way out must not come back with the next login.
        FakeClient client = join(carrying("Ala", 7, 7, 1));
        walkTo(client, 8, 7);
        assertThat(client.await(f -> f.contains("\"type\":\"passage\""))).isTrue();

        mapOf(client).submit(new Command.Pass(client, 8, 7));
        assertThat(client.await(f -> f.contains("\"id\":\"ogrod\""))).isTrue();

        assertThat(saved.snapshots).anySatisfy(snapshot -> {
            assertThat(snapshot.mapId()).isEqualTo("ogrod");
            assertThat(snapshot.items()).isEmpty();
        });
    }

    @Test
    void sayingYesFromSomewhereElseDoesNothing() throws Exception {
        // The server never remembered asking, so the answer has to carry the
        // tile - and standing anywhere else makes it an answer about a passage
        // this character is not in.
        FakeClient client = join(carrying("Ala", 2, 2, 1));

        mapOf(client).submit(new Command.Pass(client, 8, 7));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("Nie stoisz w tym przejściu"));
        assertThat(client.frames()).noneMatch(f -> f.contains("\"id\":\"ogrod\""));
        assertThat(latest(client, "\"type\":\"bag\"")).contains("probny-miecz");
    }

    @Test
    void anAnswerIsAboutTheTileItNames() throws Exception {
        // Standing in one passage and answering about another. Both burn
        // something, so without the tile in the answer this would pay the
        // wrong toll and open the wrong door.
        FakeClient client = join(new SavedCharacter(PlayerNames.key("Ala"), "Ala", "dom", 7, 3,
                Direction.DOWN, 1, 0L, -1, 0L, Attributes.FRESH, 0,
                List.of(new StoredItem("bilet", "probny-miecz", null),
                        new StoredItem("kaftan", "probny-kaftan", null)),
                "wojownik", 0, List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY));
        walkTo(client, 8, 3);
        assertThat(client.await(f -> f.contains("\"type\":\"passage\"")
                && f.contains("Bród"))).isTrue();
        int after = client.frames().size();

        mapOf(client).submit(new Command.Pass(client, 8, 7));

        assertThat(client.awaitAfter(after, f -> f.contains("Nie stoisz w tym przejściu")))
                .isTrue();
        assertThat(client.frames()).noneMatch(f -> f.contains("\"id\":\"ogrod\""));
        assertThat(carriedIn(latest(client, "\"type\":\"bag\"")))
                .as("and nothing was paid for the answer")
                .containsExactlyInAnyOrder("probny-miecz", "probny-kaftan");
    }

    @Test
    void sayingYesWithoutTheTicketDoesNothing() throws Exception {
        FakeClient client = join("Ala", 7, 7, 1);
        walkTo(client, 8, 7);
        sleep(600);

        mapOf(client).submit(new Command.Pass(client, 8, 7));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("Potrzebujesz"));
        assertThat(client.frames()).noneMatch(f -> f.contains("\"id\":\"ogrod\""));
    }

    @Test
    void oneCrossingCostsOneTicket() throws Exception {
        // Two yeses in a row, from a bag with two tickets in it. The second
        // arrives while the character is already being handed over, which is
        // exactly when a rule that trusted the question would charge twice.
        FakeClient client = join(new SavedCharacter(PlayerNames.key("Ala"), "Ala", "dom", 7, 7,
                Direction.DOWN, 1, 0L, -1, 0L, Attributes.FRESH, 0,
                List.of(new StoredItem("bilet-1", "probny-miecz", null),
                        new StoredItem("bilet-2", "probny-miecz", null)),
                "wojownik", 0, List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY));
        walkTo(client, 8, 7);
        assertThat(client.await(f -> f.contains("\"type\":\"passage\""))).isTrue();

        mapOf(client).submit(new Command.Pass(client, 8, 7));
        mapOf(client).submit(new Command.Pass(client, 8, 7));

        assertThat(client.await(f -> f.contains("\"id\":\"ogrod\""))).isTrue();
        sleep(400);
        assertThat(carriedIn(latest(client, "\"type\":\"bag\"")))
                .as("one crossing, one ticket - the other is still in the bag")
                .containsExactly("probny-miecz");
    }

    @Test
    void walkingOffTheTileWithdrawsTheQuestion() throws Exception {
        FakeClient client = join(carrying("Ala", 7, 7, 1));
        walkTo(client, 8, 7);
        assertThat(client.await(f -> f.contains("\"type\":\"passage\"")
                && f.contains("Próbny miecz"))).isTrue();
        int after = client.frames().size();

        walkTo(client, 4, 4);

        assertThat(client.awaitAfter(after, f -> f.contains("\"type\":\"passage\"")
                && !f.contains("takes"))).isTrue();
    }

    // ---- dying far from home -------------------------------------------

    @Test
    void dyingSomewhereElseWakesYouWhereThatMapSays() throws Exception {
        // The garden wakes its dead at the house. Same handover as a door, with
        // the character still knocked out when it lands - the waking stamp
        // travels with everything else.
        FakeClient client = joinTo("ogrod", "Ala", 5, 2, 1);
        int killer = creatureNamed(client.await("\"type\":\"init\""), "Zabojca");

        runners.get("ogrod").submit(new Command.Attack(client, killer));

        assertThat(client.await(f -> f.contains("\"type\":\"init\"") && f.contains("\"id\":\"dom\"")))
                .as("killed in the garden, woken at the house")
                .isTrue();
        assertThat(selfIn(latest(client, "\"type\":\"init\""))).containsExactly(2, 2);
        assertThat(latest(client, "\"type\":\"you\"")).doesNotContain("\"wakesAt\":0");
    }

    // ------------------------------------------------------------------

    private static List<String> carriedIn(String bagFrame) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : JSON.readTree(bagFrame).path("carried")) {
            ids.add(item.path("defId").asText());
        }
        return ids;
    }

    private void walkTo(FakeClient client, int x, int y) {
        mapOf(client).submit(new Command.MoveTo(client, x, y));
    }

    private static int[] selfIn(String initFrame) throws Exception {
        JsonNode init = JSON.readTree(initFrame);
        int self = init.path("selfId").asInt();
        for (JsonNode actor : init.path("actors")) {
            if (actor.path("id").asInt() == self) {
                return new int[]{actor.path("x").asInt(), actor.path("y").asInt()};
            }
        }
        throw new AssertionError("the init frame did not contain its own character");
    }

    private int[] selfNow(FakeClient client) throws Exception {
        int self = JSON.readTree(client.await("\"type\":\"init\"")).path("selfId").asInt();
        int[] at = selfIn(latest(client, "\"type\":\"init\""));
        for (String frame : client.frames()) {
            for (JsonNode moved : JSON.readTree(frame).path("moved")) {
                if (moved.path("id").asInt() == self) {
                    at = new int[]{moved.path("x").asInt(), moved.path("y").asInt()};
                }
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

    /** Somebody with something in every pocket, so a lost pocket is visible. */
    private static SavedCharacter carrying(String name, int x, int y, int level) {
        return new SavedCharacter(PlayerNames.key(name), name, "dom", x, y, Direction.DOWN,
                level, 0L, 33, 0L, Attributes.FRESH, 0,
                List.of(new StoredItem("a-sword", "probny-miecz", null)),
                "wojownik", 1,
                List.of(new StoredSkill("regeneracja", 3)),
                List.of(new StoredCoin("zloto", 77)), Deposit.EMPTY, Deposit.EMPTY);
    }

    private FakeClient join(String name, int x, int y, int level) {
        return join(new SavedCharacter(PlayerNames.key(name), name, "dom", x, y, Direction.DOWN,
                level, 0L, -1, 0L, Attributes.FRESH, 0, List.of(), "wojownik", 0,
                List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY));
    }

    private FakeClient joinTo(String mapId, String name, int x, int y, int level) {
        SavedCharacter character = new SavedCharacter(PlayerNames.key(name), name, mapId, x, y,
                Direction.DOWN, level, 0L, -1, 0L, Attributes.FRESH, 0, List.of(),
                "wojownik", 0, List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY);
        FakeClient client = new FakeClient();
        whereTheyAre.put(client, runners.get(mapId));
        runners.get(mapId).submit(new Command.Join(client, 1L, character, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    private FakeClient join(SavedCharacter character) {
        FakeClient client = new FakeClient();
        whereTheyAre.put(client, runners.get(character.mapId()));
        runners.get(character.mapId()).submit(new Command.Join(client, 1L, character, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    private String latest(FakeClient client, String needle) {
        List<String> frames = client.frames();
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (frames.get(i).contains(needle)) {
                return frames.get(i);
            }
        }
        throw new AssertionError("no frame containing " + needle + " was ever sent");
    }

    private static int numberIn(String frame, String field) {
        try {
            return JSON.readTree(frame).path(field).asInt();
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
            return awaitAfter(0, match);
        }

        /**
         * The same, counting only frames that arrived after {@code from}. A
         * plain await scans the whole history, so a wait for "a passage frame"
         * matches the one that opened the question when what is wanted is the
         * one that withdrew it.
         */
        boolean awaitAfter(int from, Predicate<String> match) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                List<String> seen = frames();
                if (seen.subList(Math.min(from, seen.size()), seen.size()).stream()
                        .anyMatch(match)) {
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
