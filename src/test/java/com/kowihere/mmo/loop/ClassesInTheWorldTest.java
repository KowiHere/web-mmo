package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.ClassDef;
import com.kowihere.mmo.world.ClassDefLoader;
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
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A class inside a running map.
 *
 * <p>The question these answer is the one that makes classes worth having at
 * all: does the class a character picked actually change how it fights, or is
 * it a label on an otherwise identical character?
 */
class ClassesInTheWorldTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    private static final Map<String, ClassDef> CLASSES = new ClassDefLoader().loadAll();
    private static final Content CONTENT = new Content(MOBS,
            new ItemDefLoader("classpath:test-items/*.json").loadAll(), CLASSES);
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-classes/*.json").loadAll().get("arena-klas");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 10_000;

    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        // Every roll at the bottom of its range, so a blow is arithmetic rather
        // than a sample: two classes hitting the same target differ because of
        // what they are, not because of luck.
        runner = new MapRunner(ARENA, JSON, WorldPersistence.NONE, CONTENT, 20, alwaysLowest());
        thread = new Thread(runner, "test-arena-classes");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void aClassDecidesWhichAttributeYourBlowsAreMadeOf() throws Exception {
        // The same three attributes, arranged the same way, played as two
        // different classes. If the class did not decide where damage comes
        // from, these two would hit for the same amount.
        Attributes lopsided = new Attributes(20, 5, 5); // all strength

        int asWarrior = attackReported(join("Wojna", "wojownik", lopsided));
        int asMage = attackReported(join("Magia", "mag", lopsided));

        assertThat(asWarrior)
                .as("a warrior with twenty strength should hit like one")
                .isGreaterThan(asMage);
    }

    @Test
    void armourStopsLessOfAMagesBlowThanAWarriors() throws Exception {
        // This is what keeps the frailest class worth playing. Both hit the same
        // armoured target with the same attack; the mage's blows pass through
        // more of the armour, so they land for more.
        Attributes even = new Attributes(12, 12, 12);

        FakeClient warrior = join("Zbroja", "wojownik", even);
        FakeClient mage = join("Iskra", "mag", even);
        assertThat(attackReported(warrior))
                .as("this only means something if both hit equally hard to begin with")
                .isEqualTo(attackReported(mage));

        // Forty armour, five hundred health and no interest in fighting back:
        // a target that exists to be measured against rather than beaten.
        int dummy = creatureNamed(warrior.await("\"type\":\"init\""), "Pancerniak");
        runner.submit(new Command.Attack(warrior, dummy));
        int byWarrior = awaitOwnBlow(warrior, dummy);

        runner.submit(new Command.Attack(mage, dummy));
        int byMage = awaitOwnBlow(mage, dummy);

        assertThat(byMage)
                .as("armour should stop less of a mage's blow (warrior %d, mage %d)",
                        byWarrior, byMage)
                .isGreaterThan(byWarrior);
    }

    @Test
    void aClassCarriesItsOwnHealth() throws Exception {
        Attributes even = new Attributes(10, 10, 10);

        int warriorHealth = numberIn(latestYou(join("Twardy", "wojownik", even)), "maxHp");
        int mageHealth = numberIn(latestYou(join("Kruchy", "mag", even)), "maxHp");

        assertThat(warriorHealth)
                .as("the class that stands in front should be the one that can")
                .isGreaterThan(mageHealth);
    }

    @Test
    void theOwnerIsToldWhichClassTheyArePlaying() throws Exception {
        String you = latestYou(join("Znak", "lowca", Attributes.FRESH));

        assertThat(JSON.readTree(you).path("classId").asText()).isEqualTo("lowca");
        assertThat(JSON.readTree(you).path("className").asText()).isNotBlank();
    }

    @Test
    void aCharacterWithNoClassAtAllStillPlays() throws Exception {
        // The column allows it, and every character created before classes
        // existed has it. A sorted map treats a null key as an error rather
        // than a miss, which is how this turned into a crash on joining.
        FakeClient client = join("Bezklasowy", null, Attributes.FRESH);

        assertThat(JSON.readTree(latestYou(client)).path("classId").asText())
                .isEqualTo(ClassDefLoader.FALLBACK_ID);
    }

    @Test
    void aStoredClassThatNoLongerExistsDoesNotStopSomebodyPlaying() throws Exception {
        // Content can be renamed under a live database. Losing the character
        // would be far worse than swinging a sword instead of a staff until
        // somebody notices.
        FakeClient client = join("Duch", "klasa-ktorej-nie-ma", Attributes.FRESH);

        assertThat(JSON.readTree(latestYou(client)).path("classId").asText())
                .isEqualTo(ClassDefLoader.FALLBACK_ID);
    }

    // ------------------------------------------------------------------

    private static int numberIn(String frame, String field) {
        try {
            return JSON.readTree(frame).path(field).asInt();
        } catch (Exception e) {
            throw new AssertionError("unreadable frame " + frame, e);
        }
    }

    private int attackReported(FakeClient client) {
        return numberIn(latestYou(client), "attack");
    }

    /**
     * What this client's own first blow took off that creature.
     *
     * <p>Filtered by who struck it, not merely by what was struck: a delta goes
     * to everyone on the map, so this client's frames also carry the other
     * character's blows against the same target - and reading one of those was
     * exactly how this test first "passed".
     */
    private int firstBlowBy(FakeClient client, int targetId) throws Exception {
        int self = selfId(client);
        for (String frame : client.frames()) {
            for (JsonNode blow : JSON.readTree(frame).path("damage")) {
                if (blow.path("attacker").asInt() == self
                        && blow.path("target").asInt() == targetId
                        && blow.path("amount").asInt() > 0) {
                    return blow.path("amount").asInt();
                }
            }
        }
        throw new AssertionError("this client never hit " + targetId);
    }

    /** Waits for this client's own first blow, rather than for anybody's. */
    private int awaitOwnBlow(FakeClient client, int targetId) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                return firstBlowBy(client, targetId);
            } catch (AssertionError notYet) {
                sleep(50);
            }
        }
        throw new AssertionError("this client never struck " + targetId);
    }

    private int selfId(FakeClient client) throws Exception {
        return JSON.readTree(client.await("\"type\":\"init\"")).path("selfId").asInt();
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
        throw new AssertionError("this client was never told about itself");
    }

    private FakeClient join(String name, String classId, Attributes attributes) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                SavedCharacter.fresh(PlayerNames.key(name), name, ARENA.id(), 5, 5,
                        Direction.DOWN, classId, attributes), 0));
        assertThat(client.await(f -> f.contains("\"type\":\"you\""))).isTrue();
        return client;
    }

    private static Random alwaysLowest() {
        return new Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }
        };
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
