package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDef;
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
 * Drinking, inside a running map.
 *
 * <p>This is the only health in the game that does not come from standing in
 * front of the herbalist, so the tests are mostly about what it refuses: a
 * bottle drunk at the wrong moment, or for nothing, is one somebody paid for.
 */
class PotionsInTheWorldTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs-items/*.json", ITEMS).loadAll();
    private static final Content CONTENT = new Content(MOBS, ITEMS);
    private static final MapDef VAULT = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs-items/*.json", ITEMS),
            "classpath:test-maps-items/*.json").loadAll().get("skarbiec");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 10_000;
    private static final long ACCOUNT = 1L;
    private static final int HURT = 5;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;
    private final Map<String, String> given = new java.util.HashMap<>();

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(VAULT, JSON, saved, CONTENT, 20);
        thread = new Thread(runner, "test-potions");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    // ---- drinking ------------------------------------------------------

    @Test
    void aMouthfulGivesBackExactlyWhatItSays() throws Exception {
        FakeClient client = joinHurt("Ala", HURT, "probna-mikstura");

        runner.submit(new Command.Drink(client, idOf("probna-mikstura")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") == HURT + 30)).isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .as("and a potion with nothing left in it is gone")
                .isEmpty();
    }

    @Test
    void andNeverMoreThanIsMissing() throws Exception {
        // A scratch treated with a thirty-point potion gives back the scratch.
        int max = maxHpOf(joinHurt("Zwiadowca", HURT));
        FakeClient client = joinHurt("Ala", max - 2, "probna-mikstura");

        runner.submit(new Command.Drink(client, idOf("probna-mikstura")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") == max)).isTrue();
        assertThat(client.frames())
                .as("and health never goes past the top")
                .noneMatch(f -> f.contains("\"type\":\"you\"") && numberIn(f, "hp") > max);
    }

    @Test
    void aFlaskLosesOnlyWhatCameBack() throws Exception {
        // Half of this character's health, out of a hundred in the bottle. What
        // leaves the bottle is what reached the character, which is the whole
        // reason a flask is worth more than the potions it replaces.
        FakeClient client = joinHurt("Ala", HURT, "probny-flakon");
        int max = maxHpOf(client);
        int mouthful = max / 2;

        runner.submit(new Command.Drink(client, idOf("probny-flakon")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") == HURT + mouthful)).isTrue();
        assertThat(remainingIn(latest(client, "\"type\":\"bag\""), "probny-flakon"))
                .isEqualTo(100 - mouthful);
    }

    @Test
    void anEmptyFlaskIsGone() throws Exception {
        // A flask holding less than one mouthful gives what it has and goes.
        // Nothing keeps an empty bottle: the bag has twenty places and that is
        // a real cost.
        FakeClient client = joinHurt("Ala", 1, "probny-flakonik");

        runner.submit(new Command.Drink(client, idOf("probny-flakonik")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") == 1 + 30)).isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .as("drunk dry, it leaves nothing behind")
                .isEmpty();
    }

    @Test
    void aFlaskWithSomethingLeftStays() throws Exception {
        FakeClient client = joinHurt("Ala", HURT, "probny-flakon");

        runner.submit(new Command.Drink(client, idOf("probny-flakon")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") > HURT)).isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .containsExactly("probny-flakon");
    }

    // ---- and what it refuses -------------------------------------------

    @Test
    void thereIsNoDrinkingInAFight() throws Exception {
        // Rounds land every second and a half. A bagful of bottles in a fight
        // would simply outlast anything that could hurt you.
        FakeClient client = joinHurt("Ala", HURT, "probna-mikstura");
        runner.submit(new Command.Attack(client,
                creatureNamed(client.await("\"type\":\"init\""), "Straznik")));
        assertThat(client.await(f -> f.contains("\"inFight\":true"))).isTrue();
        int after = client.frames().size();

        runner.submit(new Command.Drink(client, idOf("probna-mikstura")));

        assertThat(client.awaitAfter(after, f -> f.contains("W walce nie ma na to czasu")))
                .isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .as("and the bottle is still corked")
                .containsExactly("probna-mikstura");
    }

    @Test
    void nothingIsPouredAwayOnSomebodyWhoIsWell() throws Exception {
        FakeClient client = joinHurt("Ala", -1, "probna-mikstura");
        int after = client.frames().size();

        runner.submit(new Command.Drink(client, idOf("probna-mikstura")));

        assertThat(client.awaitAfter(after, f -> f.contains("Nic ci nie jest"))).isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .containsExactly("probna-mikstura");
    }

    @Test
    void aBrewTooStrongToDrinkIsRefused() throws Exception {
        // The level on an item used to mean "cannot be worn". A potion is not
        // worn, so it had to start meaning "cannot be used" as well.
        FakeClient client = joinHurt("Ala", HURT, "mikstura-mistrza");
        int after = client.frames().size();

        runner.submit(new Command.Drink(client, idOf("mikstura-mistrza")));

        assertThat(client.awaitAfter(after, f -> f.contains("wymaga poziomu 9"))).isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .containsExactly("mikstura-mistrza");
    }

    @Test
    void whatIsCarriedRatherThanDrunkIsNotDrunk() throws Exception {
        FakeClient client = joinHurt("Ala", HURT, "probna-pochodnia");
        int after = client.frames().size();

        runner.submit(new Command.Drink(client, idOf("probna-pochodnia")));

        assertThat(client.awaitAfter(after, f -> f.contains("się nie pije"))).isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .containsExactly("probna-pochodnia");
    }

    @Test
    void anUnconsciousCharacterDoesNotDrink() throws Exception {
        // Not a rule written here: the gate that refuses everything but joining,
        // leaving and talking while knocked out covers this command for free.
        FakeClient client = join(character("Ala", HURT,
                System.currentTimeMillis() + 60_000, "probna-mikstura"));
        int after = client.frames().size();

        runner.submit(new Command.Drink(client, idOf("probna-mikstura")));

        assertThat(client.awaitAfter(after, f -> f.contains("Jeszcze się nie ocknęłaś")))
                .isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .containsExactly("probna-mikstura");
    }

    // ---- and what stops a bagful going down at once ---------------------

    @Test
    void aBagfulDrunkAtOnceCostsOneFrameNotTen() throws Exception {
        // There is no cooldown, on purpose. What keeps this cheap is that the
        // frames saying what happened are queued per character and flushed once
        // a tick, so ten mouthfuls in one tick are one bag frame, not ten.
        String[] drops = new String[10];
        java.util.Arrays.fill(drops, "probna-kropla");
        FakeClient client = joinHurt("Ala", HURT, drops);
        int after = client.frames().size();

        for (String id : given.values()) {
            runner.submit(new Command.Drink(client, id));
        }

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") == HURT + 10)).isTrue();
        long bagFrames = client.frames().subList(after, client.frames().size()).stream()
                .filter(f -> f.contains("\"type\":\"bag\"")).count();
        assertThat(bagFrames)
                .as("ten drinks, at most two frames - one per tick they landed in")
                .isLessThanOrEqualTo(2);
    }

    // ---- what is written down -------------------------------------------

    @Test
    void whatIsLeftInAFlaskIsWrittenDown() throws Exception {
        FakeClient client = joinHurt("Ala", HURT, "probny-flakon");
        int max = maxHpOf(client);
        runner.submit(new Command.Drink(client, idOf("probny-flakon")));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") > HURT)).isTrue();

        runner.submit(new Command.Detach(client));
        sleep(500);

        assertThat(saved.snapshots).anySatisfy(snapshot ->
                assertThat(snapshot.items()).anySatisfy(item -> {
                    assertThat(item.defId()).isEqualTo("probny-flakon");
                    assertThat(item.remaining())
                            .as("a flask that came back full would be a flask nobody pays for")
                            .isEqualTo(100 - max / 2);
                }));
    }

    // ------------------------------------------------------------------

    private int maxHpOf(FakeClient client) throws Exception {
        return numberIn(latest(client, "\"type\":\"you\""), "maxHp");
    }

    private SavedCharacter character(String name, int hp, long wakesAt, String... carrying) {
        List<StoredItem> items = new ArrayList<>();
        for (String defId : carrying) {
            String id = java.util.UUID.randomUUID().toString();
            given.put(defId + "-" + items.size(), id);
            given.putIfAbsent(defId, id);
            items.add(new StoredItem(id, defId, null));
        }
        return new SavedCharacter(PlayerNames.key(name), name, VAULT.id(), 5, 5, Direction.DOWN,
                1, 0L, hp, wakesAt, Attributes.FRESH, 0, items, null, 1,
                List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY);
    }

    private FakeClient joinHurt(String name, int hp, String... carrying) {
        return join(character(name, hp, 0L, carrying));
    }

    private FakeClient join(SavedCharacter character) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, ACCOUNT, character, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        assertThat(client.await(f -> f.contains("\"type\":\"bag\""))).isTrue();
        return client;
    }

    private String idOf(String defId) {
        String id = given.get(defId);
        assertThat(id).as("this character was not given a %s", defId).isNotNull();
        return id;
    }

    private static int creatureNamed(String initFrame, String name) throws Exception {
        for (JsonNode actor : JSON.readTree(initFrame).path("actors")) {
            if (name.equals(actor.path("name").asText())) {
                return actor.path("id").asInt();
            }
        }
        throw new AssertionError("no creature called " + name);
    }

    private static int numberIn(String frame, String field) {
        try {
            return JSON.readTree(frame).path(field).asInt();
        } catch (Exception e) {
            throw new AssertionError("unreadable frame " + frame, e);
        }
    }

    private static Integer remainingIn(String bagFrame, String defId) throws Exception {
        for (JsonNode item : JSON.readTree(bagFrame).path("carried")) {
            if (defId.equals(item.path("defId").asText())) {
                return item.path("remaining").isMissingNode() ? null
                        : item.path("remaining").asInt();
            }
        }
        throw new AssertionError("no " + defId + " in " + bagFrame);
    }

    private static List<String> carriedIds(String bagFrame) {
        List<String> ids = new ArrayList<>();
        try {
            for (JsonNode item : JSON.readTree(bagFrame).path("carried")) {
                ids.add(item.path("defId").asText());
            }
        } catch (Exception e) {
            throw new AssertionError("unreadable bag " + bagFrame, e);
        }
        return ids;
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

        String await(String needle) {
            assertThat(await(f -> f.contains(needle))).as("frame containing %s", needle).isTrue();
            return frames().stream().filter(f -> f.contains(needle)).findFirst().orElseThrow();
        }

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
    }
}
