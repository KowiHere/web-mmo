package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.BlessingLoader;
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
 * What dying leaves behind.
 *
 * <p>Being knocked out was, until now, the whole price of a death: it is paid
 * lying down, and once the character is on its feet again there is nothing to
 * show for it. This is the part that is paid standing up - ten percent off the
 * three attributes and off attack and armour besides, for five minutes of play.
 *
 * <p>It is a blessing with nothing but minuses in it, which is why there is so
 * little code behind it and so much of this file is about the rules around it:
 * that a second death refreshes rather than stacks, and that a bagful of
 * bottles is not a way of not being punished.
 */
class DeathWeaknessTest {

    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs/*.json").loadAll();
    /** The shipped blessings, because the one dying lays is shipped content. */
    private static final Content CONTENT =
            new Content(MOBS, new ItemDefLoader("classpath:test-items/*.json").loadAll());
    private static final MapDef ARENA = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/*.json"),
            "classpath:test-maps-combat/*.json").loadAll().get("arena-walki");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 40_000;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(ARENA, JSON, saved, CONTENT, 20);
        thread = new Thread(runner, "test-death-weakness");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void dyingLeavesTheCharacterWeakerThanItWas() throws Exception {
        FakeClient client = join(character(List.of()));
        int attackBefore = numberIn(latest(client, "\"type\":\"you\""), "attack");

        getKilled(client);

        assertThat(client.await(f -> f.contains("\"type\":\"blessings\"")
                && f.contains("Osłabienie po śmierci")))
                .as("the penalty shows up where every other timed effect does")
                .isTrue();
        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "attack") < attackBefore))
                .as("and the sheet says so at once, on a road, with no fight in sight")
                .isTrue();
        // Armour is not asserted here on purpose: a character wearing nothing
        // has one point of it, and a tenth of one point rounds to nothing. What
        // a share of armour is a share of is counted exactly, with a breastplate
        // on, in SharesOfAStatisticTest.
    }

    @Test
    void aSecondDeathDoesNotDoubleThePrice() throws Exception {
        // Refreshing rather than stacking. The clock going back to five minutes
        // is counted in BlessingsTest, where a tick can be made to pass without
        // waiting for one; what matters here is that dying twice in a row does
        // not leave a character with two penalties on it.
        FakeClient client = join(character(List.of()));
        getKilled(client);
        assertThat(client.await(f -> f.contains("Osłabienie po śmierci"))).isTrue();
        int weakened = numberIn(latest(client, "\"type\":\"you\""), "attack");

        sleep(1_200);
        getKilled(client);
        sleep(600);

        assertThat(count(latest(client, "\"type\":\"blessings\""), "\"name\""))
                .as("still one penalty, not two")
                .isEqualTo(1);
        assertThat(numberIn(latest(client, "\"type\":\"you\""), "attack"))
                .as("dying twice as often does not make you twice as weak")
                .isEqualTo(weakened);
    }

    @Test
    void aBagfulOfBottlesIsNotAWayOfNotBeingPunished() throws Exception {
        // Three at once is the cap, and the penalty is laid over it. Under it,
        // drinking three would be the cheapest insurance in the game.
        FakeClient client = join(character(List.of(
                new StoredBlessing("mestwo-rycerza", 600_000),
                new StoredBlessing("oko-tropicielki", 600_000),
                new StoredBlessing("furia-berserka", 600_000))));
        assertThat(client.await(f -> f.contains("\"type\":\"blessings\"")
                && count(f, "\"name\"") == Blessings.MAX_ACTIVE)).isTrue();

        getKilled(client);

        assertThat(client.await(f -> f.contains("Osłabienie po śmierci"))).isTrue();
        assertThat(count(latest(client, "\"type\":\"blessings\""), "\"name\""))
                .isEqualTo(Blessings.MAX_ACTIVE + 1);
    }

    @Test
    void thePenaltyIsWrittenDownLikeAnythingElseWithAClockOnIt() throws Exception {
        // And as remaining time, not as a moment: five minutes of play, so
        // closing the tab waits nothing out.
        FakeClient client = join(character(List.of()));
        getKilled(client);
        assertThat(client.await(f -> f.contains("Osłabienie po śmierci"))).isTrue();

        runner.submit(new Command.Detach(client));
        sleep(600);

        assertThat(saved.snapshots).anySatisfy(snapshot ->
                assertThat(snapshot.blessings())
                        .anySatisfy(one -> {
                            assertThat(one.defId()).isEqualTo(BlessingLoader.DEATH_WEAKNESS);
                            assertThat(one.remainingMs())
                                    .isGreaterThan(4 * 60_000L)
                                    .isLessThanOrEqualTo(5 * 60_000L);
                        }));
    }

    @Test
    void itComesBackWithTheCharacterAndSoDoTheLowerNumbers() throws Exception {
        // The trip home from a death can be a trip to another map, and a
        // penalty that did not survive the handover would be no penalty at all
        // on any map that buries its dead elsewhere.
        FakeClient fresh = join(character("Bela", List.of()));
        int full = numberIn(latest(fresh, "\"type\":\"you\""), "attack");

        FakeClient carried = join(character("Cela",
                List.of(new StoredBlessing(BlessingLoader.DEATH_WEAKNESS, 120_000))));

        assertThat(carried.await(f -> f.contains("Osłabienie po śmierci"))).isTrue();
        assertThat(numberIn(latest(carried, "\"type\":\"you\""), "attack"))
                .as("the same character, weaker, because the penalty travelled with it")
                .isLessThan(full);
    }

    // ------------------------------------------------------------------

    /** Walks into the one creature on this map that ends a fight by winning it. */
    private void getKilled(FakeClient client) throws Exception {
        runner.submit(new Command.Attack(client,
                creatureNamed(client.await("\"type\":\"init\""), "Zabojca")));
        assertThat(client.await(f -> f.contains("\"wakesAt\"") && !f.contains("\"wakesAt\":0")))
                .as("the killer should have killed")
                .isTrue();
    }

    private SavedCharacter character(List<StoredBlessing> blessings) {
        return character("Ala", blessings);
    }

    /**
     * Levelled up on purpose. A tenth is rounded down, so a character with five
     * of everything loses nothing at all - which is a kindness to beginners and
     * useless for testing that anything happens.
     *
     * <p>And on forty points of health, so that the one creature here that can
     * kill does it in a round or two. Every test below has to get killed first,
     * and a long fight is a long test that fails when the machine is busy
     * rather than when the code is wrong.
     */
    private SavedCharacter character(String name, List<StoredBlessing> blessings) {
        return new SavedCharacter(PlayerNames.key(name), name, ARENA.id(), 5, 5, Direction.DOWN,
                10, 8_100L, 40, 0L, new Attributes(30, 12, 8), 0, List.of(), null, 1,
                List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY, blessings);
    }

    private FakeClient join(SavedCharacter character) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, character.nameKey().hashCode(), character, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
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

    private static int count(String frame, String needle) {
        int seen = 0;
        int at = frame.indexOf(needle);
        while (at >= 0) {
            seen++;
            at = frame.indexOf(needle, at + needle.length());
        }
        return seen;
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
