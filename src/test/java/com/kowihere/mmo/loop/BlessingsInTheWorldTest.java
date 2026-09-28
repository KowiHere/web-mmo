package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.BlessingDef;
import com.kowihere.mmo.world.BlessingLoader;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.CurrencyDefLoader;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.MobDefLoader;
import com.kowihere.mmo.world.SkillDefLoader;
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
 * Blessings inside a running map.
 *
 * <p>The claim that matters is that there is nothing to claim: a blessing is a
 * third layer under the same statistics everything else already reads, so it
 * works on a road and in a fight without either of them knowing it exists.
 * Every test below is really a test that no second place computes anything.
 */
class BlessingsInTheWorldTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs-items/*.json", ITEMS).loadAll();
    private static final Map<String, BlessingDef> BLESSINGS =
            new BlessingLoader("classpath:test-blessings/*.json").loadAll();
    private static final Content CONTENT = new Content(MOBS, ITEMS,
            new ClassDefLoader().loadAll(), new SkillDefLoader().loadAll(),
            Map.of(), new CurrencyDefLoader().loadAll(), BLESSINGS);
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs-items/*.json", ITEMS),
            "classpath:test-maps-items/*.json").loadAll().get("skarbiec");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 10_000;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;
    private final Map<String, String> given = new java.util.HashMap<>();

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(ARENA, JSON, saved, CONTENT, 20);
        thread = new Thread(runner, "test-blessings");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    // ---- what a bottle does ---------------------------------------------

    @Test
    void drinkingOneChangesTheCharacterAtOnce() throws Exception {
        FakeClient client = join("Ala", "probny-eliksir");
        int attackBefore = numberIn(latest(client, "\"type\":\"you\""), "attack");

        runner.submit(new Command.Drink(client, idOf("probny-eliksir")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "attack") > attackBefore))
                .as("ten of strength and five of attack, on a road, with no fight in sight")
                .isTrue();
        // Waited on the panel itself rather than reading the last one to hand:
        // the sheet and the panel go out one after the other inside the same
        // flush, so whichever is asserted second has to be waited for too.
        assertThat(client.await(f -> f.contains("\"type\":\"blessings\"")
                && f.contains("Próbne męstwo"))).isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .as("and the bottle is gone")
                .isEmpty();
    }

    @Test
    void theSameLineThatHelpsCanAlsoTakeAway() throws Exception {
        // Ten strength and minus two armour, out of one bottle. The bargain is
        // the whole point of letting a line be negative.
        FakeClient client = join("Ala", "probny-eliksir");
        int armorBefore = numberIn(latest(client, "\"type\":\"you\""), "armor");

        runner.submit(new Command.Drink(client, idOf("probny-eliksir")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "armor") < armorBefore)).isTrue();
    }

    @Test
    void aCurseWorthMoreThanYouHaveTakesEverythingAndStops() throws Exception {
        // Minus a hundred armour and a hundred strength. Nothing in this game
        // may go negative: every formula below would obey it.
        FakeClient client = join("Ala", "probny-eliksir-klatwy");

        runner.submit(new Command.Drink(client, idOf("probny-eliksir-klatwy")));

        // Waited on the sheet rather than on the chat line: the line arrives in
        // the delta and the sheet a breath later in the same flush, so a test
        // that waits for the line reads the sheet from before the drink.
        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "strength") == 0)).isTrue();

        String you = latest(client, "\"type\":\"you\"");
        assertThat(numberIn(you, "armor")).isZero();
        assertThat(numberIn(you, "attack")).isGreaterThanOrEqualTo(0);
        assertThat(numberIn(you, "hp")).isPositive();
        assertThat(numberIn(you, "maxHp")).isPositive();
    }

    @Test
    void aFallingCeilingTakesHealthDownWithIt() throws Exception {
        // Minus twenty maximum health while standing at full: current health
        // has to come down with it, or the bar reads more than the maximum.
        FakeClient client = join("Ala", "probny-eliksir-klatwy");
        int maxBefore = numberIn(latest(client, "\"type\":\"you\""), "maxHp");

        runner.submit(new Command.Drink(client, idOf("probny-eliksir-klatwy")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "maxHp") < maxBefore)).isTrue();
        String you = latest(client, "\"type\":\"you\"");
        assertThat(numberIn(you, "hp")).isLessThanOrEqualTo(numberIn(you, "maxHp"));
    }

    @Test
    void aCeilingStaysACeiling() throws Exception {
        // Fifty points of dodge and fifty of second blow out of one bottle. The
        // caps exist so that nobody becomes impossible to hit, and one bottle
        // must not be allowed to undo that.
        FakeClient client = join("Ala", "probny-eliksir-zwinnosci");

        runner.submit(new Command.Drink(client, idOf("probny-eliksir-zwinnosci")));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "dodgePercent") > 5)).isTrue();

        String you = latest(client, "\"type\":\"you\"");
        assertThat(numberIn(you, "dodgePercent")).isLessThanOrEqualTo(35);
        assertThat(numberIn(you, "secondBlowPercent")).isLessThanOrEqualTo(40);
    }

    // ---- and what it refuses ---------------------------------------------

    @Test
    void aBlessingTooHighToBearIsRefused() throws Exception {
        FakeClient client = join("Ala", "probny-eliksir-wyzyn");
        int after = client.frames().size();

        runner.submit(new Command.Drink(client, idOf("probny-eliksir-wyzyn")));

        assertThat(client.awaitAfter(after, f -> f.contains("wymaga poziomu 9"))).isTrue();
        assertThat(carriedIds(latest(client, "\"type\":\"bag\"")))
                .containsExactly("probny-eliksir-wyzyn");
    }

    @Test
    void thereIsNoDrinkingOneInAFight() throws Exception {
        FakeClient client = join("Ala", "probny-eliksir");
        runner.submit(new Command.Attack(client,
                creatureNamed(client.await("\"type\":\"init\""), "Straznik")));
        assertThat(client.await(f -> f.contains("\"inFight\":true"))).isTrue();
        int after = client.frames().size();

        runner.submit(new Command.Drink(client, idOf("probny-eliksir")));

        assertThat(client.awaitAfter(after, f -> f.contains("W walce nie ma na to czasu")))
                .isTrue();
    }

    @Test
    void aFourthIsRefusedAndTheThirdCanStillBeRefreshed() throws Exception {
        FakeClient client = join("Ala", "probny-eliksir", "probny-eliksir-zwinnosci",
                "probny-eliksir-hartu", "probny-eliksir-czujnosci", "probny-eliksir");
        drink(client, "probny-eliksir");
        drink(client, "probny-eliksir-zwinnosci");
        drink(client, "probny-eliksir-hartu");
        assertThat(client.await(f -> f.contains("\"type\":\"blessings\"")
                && count(f, "\"name\"") == Blessings.MAX_ACTIVE)).isTrue();
        int after = client.frames().size();

        runner.submit(new Command.Drink(client, idOf("probny-eliksir-czujnosci")));

        assertThat(client.awaitAfter(after, f -> f.contains("Więcej niż"))).isTrue();
        assertThat(count(latest(client, "\"type\":\"blessings\""), "\"name\""))
                .isEqualTo(Blessings.MAX_ACTIVE);

        // The same one again is not a fourth, so it goes through.
        int refreshing = client.frames().size();
        runner.submit(new Command.Drink(client, idOf("probny-eliksir", 1)));
        assertThat(client.awaitAfter(refreshing, f -> f.contains("Próbne męstwo"))).isTrue();
        assertThat(count(latest(client, "\"type\":\"blessings\""), "\"name\""))
                .as("refreshing is not a fourth")
                .isEqualTo(Blessings.MAX_ACTIVE);
    }

    // ---- the clock --------------------------------------------------------

    @Test
    void itRunsOutAndTakesItsNumbersWithIt() throws Exception {
        // A blessing with a minute on it, handed to a character that has most
        // of it already spent: nothing here waits a real minute.
        FakeClient client = join(character("Ala", List.of(), List.of(
                new StoredBlessing("probna-zwinnosc", 400))));
        assertThat(client.await(f -> f.contains("Próbna zwinność"))).isTrue();
        String withIt = latest(client, "\"type\":\"you\"");

        assertThat(client.await(f -> f.contains("mija: Próbna zwinność"))).isTrue();

        assertThat(latest(client, "\"type\":\"blessings\""))
                .as("and the panel empties")
                .contains("\"active\":[]");
        String without = latest(client, "\"type\":\"you\"");
        assertThat(numberIn(without, "dodgePercent"))
                .as("statistics go back exactly where they were")
                .isLessThan(numberIn(withIt, "dodgePercent"));
    }

    @Test
    void whatIsWrittenDownIsHowMuchIsLeft() throws Exception {
        // Not when it ends: the clock stops while nobody is playing, so a
        // bottle is worth the same whenever it is drunk.
        FakeClient client = join("Ala", "probny-eliksir");
        drink(client, "probny-eliksir");
        assertThat(client.await(f -> f.contains("Próbne męstwo"))).isTrue();

        runner.submit(new Command.Detach(client));
        sleep(500);

        assertThat(saved.snapshots).anySatisfy(snapshot -> {
            assertThat(snapshot.blessings()).hasSize(1);
            StoredBlessing one = snapshot.blessings().get(0);
            assertThat(one.defId()).isEqualTo("probne-mestwo");
            assertThat(one.remainingMs())
                    .isLessThanOrEqualTo(10 * 60_000L)
                    .isGreaterThan(10 * 60_000L - 30_000L);
        });
    }

    @Test
    void oneComesBackWithWhateverWasLeftOfIt() throws Exception {
        FakeClient client = join(character("Ala", List.of(), List.of(
                new StoredBlessing("probne-mestwo", 90_000))));

        assertThat(client.await(f -> f.contains("Próbne męstwo"))).isTrue();

        JsonNode panel = JSON.readTree(latest(client, "\"type\":\"blessings\""));
        long left = panel.path("active").get(0).path("remainingMs").asLong();
        assertThat(left).isBetween(60_000L, 90_000L);
    }

    @Test
    void aBlessingThatContentNoLongerHasIsSimplyGone() throws Exception {
        // Content is edited under a live database; refusing to let somebody
        // play would be the wrong answer, as everywhere else.
        FakeClient client = join(character("Ala", List.of(), List.of(
                new StoredBlessing("bylo-minelo", 60_000))));

        assertThat(client.await(f -> f.contains("\"type\":\"blessings\""))).isTrue();
        assertThat(latest(client, "\"type\":\"blessings\"")).contains("\"active\":[]");
    }

    // ---- in a fight -------------------------------------------------------

    @Test
    void theOneLineThatHappensRatherThanCounts() throws Exception {
        // Seven health a round, and only in a fight. The guard hits for one, so
        // a wounded character mends while it is being hit.
        FakeClient client = join(character("Ala",
                List.of(new StoredItem("e-1", "probny-eliksir-zwinnosci", null)), List.of(), 20));
        runner.submit(new Command.Drink(client, "e-1"));
        assertThat(client.await(f -> f.contains("Próbna zwinność"))).isTrue();
        int hurt = numberIn(latest(client, "\"type\":\"you\""), "hp");

        runner.submit(new Command.Attack(client,
                creatureNamed(client.await("\"type\":\"init\""), "Straznik")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") > hurt))
                .as("health goes up during a fight it is losing rounds in")
                .isTrue();
    }

    // ------------------------------------------------------------------

    /**
     * Ids are handed out here rather than read back out of a frame, so that a
     * test can ask for the second bottle of the same brew - which is exactly
     * what refreshing a blessing needs.
     */
    private final List<String> carried = new ArrayList<>();

    private FakeClient join(String name, String... carrying) {
        List<StoredItem> items = new ArrayList<>();
        carried.clear();
        for (int i = 0; i < carrying.length; i++) {
            String id = carrying[i] + "#" + i;
            carried.add(id);
            items.add(new StoredItem(id, carrying[i], null));
        }
        return join(character(name, items, List.of()));
    }

    private void drink(FakeClient client, String defId) {
        runner.submit(new Command.Drink(client, idOf(defId)));
    }

    /** The first bottle of that brew the character was given. */
    private String idOf(String defId) {
        return idOf(defId, 0);
    }

    private String idOf(String defId, int which) {
        int seen = 0;
        for (String id : carried) {
            if (id.startsWith(defId + "#")) {
                if (seen++ == which) {
                    return id;
                }
            }
        }
        throw new AssertionError("this character was not given " + (which + 1)
                + " of '" + defId + "'");
    }

    private SavedCharacter character(String name, List<StoredItem> items,
                                     List<StoredBlessing> blessings) {
        return character(name, items, blessings, -1);
    }

    private SavedCharacter character(String name, List<StoredItem> items,
                                     List<StoredBlessing> blessings, int hp) {
        return new SavedCharacter(PlayerNames.key(name), name, ARENA.id(), 5, 5, Direction.DOWN,
                3, 0L, hp, 0L, Attributes.FRESH, 0, items, null, 1,
                List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY, blessings);
    }

    private FakeClient join(SavedCharacter character) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L, character, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        assertThat(client.await(f -> f.contains("\"type\":\"bag\""))).isTrue();
        return client;
    }

    private static int count(String frame, String needle) {
        int seen = 0;
        int at = frame.indexOf(needle);
        while (at >= 0) {
            seen++;
            at = frame.indexOf(needle, at + needle.length());
        }
        return seen;
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

        @Override public void send(String json) {
            frames.add(json);
        }

        @Override public void disconnect(String reason) { }

        @Override public String describe() { return "fake client"; }

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
