package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.Energy;
import com.kowihere.mmo.world.ClassDef;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.Direction;
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
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Energy and skills inside a running map.
 *
 * <p>Fought against a target that cannot win and cannot lose - five hundred
 * health, forty armour and no interest in fighting back - because what is under
 * test is how a resource fills and empties over several rounds, and a creature
 * that dies in two would cut every one of these short.
 */
class SkillsInTheWorldTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    private static final Map<String, ClassDef> CLASSES = new ClassDefLoader().loadAll();
    private static final Content CONTENT = new Content(MOBS,
            new ItemDefLoader("classpath:test-items/*.json").loadAll(), CLASSES);
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-classes/*.json").loadAll().get("arena-klas");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 12_000;
    private static final long ROUND_MS = 1_500;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        // Every roll at the bottom of its range, so what a skill does is
        // arithmetic rather than a sample.
        runner = new MapRunner(ARENA, JSON, saved, CONTENT, 20, alwaysLowest());
        thread = new Thread(runner, "test-arena-skills");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void energyStartsAtNothingAndFillsAsTheFightGoesOn() throws Exception {
        FakeClient client = join("Iskra", "mag");

        assertThat(numberIn(latestYou(client), "energy"))
                .as("nobody walks around charged; a fight is where energy comes from")
                .isZero();

        runner.submit(new Command.Attack(client, dummy(client)));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"") && numberIn(f, "energy") > 0))
                .as("the first round should be worth something")
                .isTrue();

        int afterOne = numberIn(latestYou(client), "energy");
        sleep(ROUND_MS + 400);

        assertThat(numberIn(latestYou(client), "energy"))
                .as("and every round after it as well")
                .isGreaterThan(afterOne);
    }

    @Test
    void energyIsGoneWhenTheFightIs() throws Exception {
        // It belongs to the fight, which is why it is stored nowhere. Carrying
        // it out would make picking a fight you did not want the best way to
        // open the one you did.
        FakeClient client = join("Iskra", "mag");
        runner.submit(new Command.Attack(client, dummy(client)));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"") && numberIn(f, "energy") > 0))
                .isTrue();

        runner.submit(new Command.Flee(client));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "energy") == 0 && f.contains("\"level\"")))
                .as("walking away empties it")
                .isTrue();
    }

    @Test
    void asecondFightBeginsAsEmptyAsTheFirst() throws Exception {
        // Energy is zeroed in three places: when a fight starts, when somebody
        // leaves one, and when one ends. The last two are what a player sees -
        // the bar emptying - and the first is what guarantees this, whatever
        // path the previous fight took to finish.
        FakeClient client = join("Iskra", "mag");
        int target = dummy(client);

        runner.submit(new Command.Attack(client, target));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"") && numberIn(f, "energy") > 0))
                .isTrue();

        // Waited for from here on, not from the beginning: "a frame saying zero
        // energy" matches the one sent before the fight even started, so an
        // ordinary await would sail past the flee without waiting for it at all.
        int fled = client.frames().size();
        runner.submit(new Command.Flee(client));
        assertThat(client.awaitAfter(fled, f -> f.contains("\"inFight\":false")))
                .as("the flee should actually have got them out")
                .isTrue();

        int frameBefore = client.frames().size();
        runner.submit(new Command.Attack(client, target));
        sleep(ROUND_MS + 600);

        assertThat(firstEnergyAfter(client, frameBefore))
                .as("the second fight must not open on what the first one left behind")
                .isLessThanOrEqualTo(Energy.perRound(0));
    }

    @Test
    void aRankOfRegenerationMakesEveryRoundWorthMore() throws Exception {
        // The one lever there is, and the one you asked for: points in the
        // regeneration skill, and nothing else, decide how fast energy arrives.
        FakeClient plain = join("Powolny", "mag");
        FakeClient quick = join("Szybki", "mag");
        runner.submit(new Command.Learn(quick, SkillDefLoader.REGENERATION_ID));
        assertThat(quick.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "energyPerRound") > Energy.BASE_PER_ROUND)).isTrue();

        assertThat(numberIn(latestYou(quick), "energyPerRound"))
                .as("a point spent on charging faster should charge faster")
                .isGreaterThan(numberIn(latestYou(plain), "energyPerRound"));
    }

    @Test
    void usingASkillCostsExactlyWhatItSaysAndHitsHarder() throws Exception {
        FakeClient client = join("Iskra", "mag");
        learn(client, "blyskawica");
        int target = dummy(client);

        runner.submit(new Command.Attack(client, target));
        int ordinary = awaitOwnBlow(client, target);

        // Wait until the lightning is affordable, then throw it.
        assertThat(client.await(f -> f.contains("\"type\":\"you\"") && numberIn(f, "energy") >= 30))
                .isTrue();
        int before = numberIn(latestYou(client), "energy");
        runner.submit(new Command.Use(client, "blyskawica"));

        assertThat(client.await(f -> f.contains("* Błyskawica *")))
                .as("a skill that leaves no trace is one nobody can tell they used")
                .isTrue();
        sleep(300);
        assertThat(numberIn(latestYou(client), "energy"))
                .as("it should cost what it says it costs")
                .isLessThanOrEqualTo(before - 30 + Energy.BASE_PER_ROUND);
        assertThat(biggestBlowBy(client, target))
                .as("and be worth paying for, against the same armour")
                .isGreaterThan(ordinary);
    }

    @Test
    void aSkillThatStrikesSeveralTimesDoesSoInOneRound() throws Exception {
        // The hunter's series is three blows where everything else is one. It
        // reuses the same machinery as agility's second blow, so this is the
        // check that a skill can shape a round rather than only scale it.
        FakeClient client = join("Cien", "lowca");
        learn(client, "seria");
        int target = dummy(client);

        runner.submit(new Command.Attack(client, target));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"") && numberIn(f, "energy") >= 35))
                .isTrue();
        runner.submit(new Command.Use(client, "seria"));
        assertThat(client.await(f -> f.contains("* Seria *"))).isTrue();
        sleep(400);

        assertThat(blowsInOneFrame(client, target))
                .as("three blows, in one round")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void aSkillNobodyCanAffordIsRefusedRatherThanQueued() throws Exception {
        FakeClient client = join("Iskra", "mag");
        learn(client, "blyskawica");

        runner.submit(new Command.Attack(client, dummy(client)));
        assertThat(client.await(f -> f.contains("\"damage\""))).isTrue();
        // Straight away, while the first round's charge is far short of thirty.
        runner.submit(new Command.Use(client, "blyskawica"));
        sleep(300);

        assertThat(client.frames())
                .as("and the player told why, rather than left wondering")
                .anyMatch(f -> f.contains("\"type\":\"error\"") && f.contains("energii"));
    }

    @Test
    void aSkillBelongingToAnotherClassCannotBeLearned() throws Exception {
        // Sent raw, exactly as a client that never draws the button could still
        // send it. The interface is a courtesy; this is the rule.
        FakeClient client = join("Topor", "wojownik");

        runner.submit(new Command.Learn(client, "blyskawica"));
        sleep(300);

        assertThat(client.frames())
                .anyMatch(f -> f.contains("\"type\":\"error\"") && f.contains("klasy"));
        assertThat(rankOf(client, "blyskawica")).isZero();
    }

    @Test
    void aPointGoesInOnceAndTheCeilingHolds() throws Exception {
        FakeClient client = join("Uparty", "wojownik");

        // One point at level one, so the second attempt is already one too many.
        runner.submit(new Command.Learn(client, SkillDefLoader.REGENERATION_ID));
        assertThat(client.await(f -> f.contains("\"type\":\"skills\"")
                && f.contains("\"rank\":1"))).isTrue();
        runner.submit(new Command.Learn(client, SkillDefLoader.REGENERATION_ID));
        sleep(300);

        assertThat(rankOf(client, SkillDefLoader.REGENERATION_ID))
                .as("a point that was never earned must not buy a rank")
                .isEqualTo(1);
        assertThat(client.frames())
                .anyMatch(f -> f.contains("\"type\":\"error\"") && f.contains("punkt"));
    }

    @Test
    void whatWasLearnedOutlivesTheMapAndEnergyDoesNot() throws Exception {
        FakeClient client = join("Pamiec", "mag");
        runner.submit(new Command.Learn(client, SkillDefLoader.REGENERATION_ID));
        assertThat(client.await(f -> f.contains("\"type\":\"skills\"") && f.contains("\"rank\":1")))
                .isTrue();
        runner.submit(new Command.Attack(client, dummy(client)));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"") && numberIn(f, "energy") > 0))
                .isTrue();

        runner.stop();
        thread.join(2_000);

        assertThat(saved.snapshots)
                .as("a rank nobody can find again after a restart is a point thrown away")
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.nameKey()).isEqualTo("pamiec");
                    assertThat(snapshot.skills()).isNotNull();
                    assertThat(snapshot.skills())
                            .anySatisfy(skill -> {
                                assertThat(skill.skillId()).isEqualTo(SkillDefLoader.REGENERATION_ID);
                                assertThat(skill.rank()).isEqualTo(1);
                            });
                });
        // And energy is nowhere in that snapshot at all - there is no field for
        // it, because a value that is always zero when read back is not state.
    }

    // ------------------------------------------------------------------

    /** Spends this character's point on a skill and waits for it to take. */
    private void learn(FakeClient client, String skillId) {
        runner.submit(new Command.Learn(client, skillId));
        assertThat(client.await(f -> f.contains("\"type\":\"skills\"")
                && f.contains("\"" + skillId + "\"") && f.contains("\"rank\":1")))
                .as("the point should have gone into %s", skillId)
                .isTrue();
    }

    /** The first energy this client was told about after the given frame. */
    private int firstEnergyAfter(FakeClient client, int from) {
        List<String> frames = client.frames();
        for (int i = from; i < frames.size(); i++) {
            if (frames.get(i).contains("\"type\":\"you\"")) {
                int energy = numberIn(frames.get(i), "energy");
                if (energy > 0) {
                    return energy;
                }
            }
        }
        throw new AssertionError("the second fight produced no energy at all");
    }

    private int dummy(FakeClient client) throws Exception {
        for (JsonNode actor : JSON.readTree(client.await("\"type\":\"init\"")).path("actors")) {
            if ("Pancerniak".equals(actor.path("name").asText())) {
                return actor.path("id").asInt();
            }
        }
        throw new AssertionError("no target on this map");
    }

    private int rankOf(FakeClient client, String skillId) throws Exception {
        String frame = latest(client, "\"type\":\"skills\"");
        for (JsonNode skill : JSON.readTree(frame).path("skills")) {
            if (skillId.equals(skill.path("id").asText())) {
                return skill.path("rank").asInt();
            }
        }
        return 0;
    }

    private static int numberIn(String frame, String field) {
        try {
            return JSON.readTree(frame).path(field).asInt();
        } catch (Exception e) {
            throw new AssertionError("unreadable frame " + frame, e);
        }
    }

    /** This client's own first blow on that target; a delta carries everyone's. */
    private int awaitOwnBlow(FakeClient client, int targetId) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
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
            sleep(50);
        }
        throw new AssertionError("this client never struck " + targetId);
    }

    private int biggestBlowBy(FakeClient client, int targetId) throws Exception {
        int self = selfId(client);
        int biggest = 0;
        for (String frame : client.frames()) {
            for (JsonNode blow : JSON.readTree(frame).path("damage")) {
                if (blow.path("attacker").asInt() == self && blow.path("target").asInt() == targetId) {
                    biggest = Math.max(biggest, blow.path("amount").asInt());
                }
            }
        }
        return biggest;
    }

    /** The most blows this client struck inside any single delta. */
    private int blowsInOneFrame(FakeClient client, int targetId) throws Exception {
        int self = selfId(client);
        int most = 0;
        for (String frame : client.frames()) {
            int blows = 0;
            for (JsonNode blow : JSON.readTree(frame).path("damage")) {
                if (blow.path("attacker").asInt() == self && blow.path("target").asInt() == targetId) {
                    blows++;
                }
            }
            most = Math.max(most, blows);
        }
        return most;
    }

    private int selfId(FakeClient client) throws Exception {
        return JSON.readTree(client.await("\"type\":\"init\"")).path("selfId").asInt();
    }

    private String latestYou(FakeClient client) {
        return latest(client, "\"type\":\"you\"");
    }

    private String latest(FakeClient client, String needle) {
        List<String> frames = client.frames();
        for (int i = frames.size() - 1; i >= 0; i--) {
            if (frames.get(i).contains(needle)) {
                return frames.get(i);
            }
        }
        throw new AssertionError("no frame containing " + needle);
    }

    private FakeClient join(String name, String classId) {
        ClassDef chosen = CLASSES.get(classId);
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                SavedCharacter.fresh(PlayerNames.key(name), name, ARENA.id(), 5, 5,
                        Direction.DOWN, classId,
                        chosen == null ? Attributes.FRESH : chosen.startingAttributes()), 0));
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

        /** Like {@link #await}, but blind to everything before {@code from}. */
        boolean awaitAfter(int from, Predicate<String> match) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                List<String> seen = frames();
                for (int i = from; i < seen.size(); i++) {
                    if (match.test(seen.get(i))) {
                        return true;
                    }
                }
                sleep(20);
            }
            return false;
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
