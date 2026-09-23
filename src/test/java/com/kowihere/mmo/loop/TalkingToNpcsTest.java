package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDefLoader;
import com.kowihere.mmo.world.NpcDefLoader;
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
 * Conversations inside a running map, driven with raw commands rather than
 * through the interface - because the interface is exactly what a client is
 * free not to use.
 *
 * <p>The map has no creatures at all. Nothing here is about fighting, and a
 * wandering boar could otherwise make an assertion about standing next to
 * somebody pass or fail on its own schedule.
 */
class TalkingToNpcsTest {

    private static final MapDef PEN = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/no-such-mob-*.json"), // the folder is real, the pattern matches nothing

            new NpcDefLoader("classpath:test-npcs/*.json"),
            "classpath:test-maps-npcs/*.json").loadAll().get("zagroda");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 10_000;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(PEN, JSON, saved, Content.EMPTY, 20);
        thread = new Thread(runner, "test-pen");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void peopleAndThingsAreInTheWorldFromTheFirstFrame() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        JsonNode init = JSON.readTree(client.await("\"type\":\"init\""));

        assertThat(namesIn(init)).contains("Zielarka", "Tablica ogloszen");
        assertThat(actorNamed(init, "Zielarka").path("kind").asText()).isEqualTo("NPC");
        assertThat(actorNamed(init, "Zielarka").path("maxHp").asInt())
                .as("nothing can hurt them, so a health bar would say something untrue")
                .isZero();
    }

    @Test
    void aConversationOpensOnWhatWasWrittenFirst() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        int herbalist = idOf(client, "Zielarka");

        runner.submit(new Command.Talk(client, herbalist));

        JsonNode said = JSON.readTree(client.await("\"type\":\"dialogue\""));
        assertThat(said.path("text").asText()).isEqualTo("Witaj.");
        assertThat(said.path("options")).hasSize(3);
        assertThat(said.path("options").get(0).path("index").asInt())
                .as("the client answers by index, so the index has to be on the wire")
                .isZero();
    }

    @Test
    void choosingAnOptionLeadsWhereTheContentSaidItWould() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");

        runner.submit(new Command.Choose(client, optionSaying(client, "Co tu rosnie?")));

        assertThat(client.await(f -> f.contains("Krwawnik"))).isTrue();

        // And back again: a tree that only goes downwards is a list.
        runner.submit(new Command.Choose(client, optionSaying(client, "Wroce.")));
        assertThat(client.await(f -> f.contains("\"dialogue\"") && f.contains("Witaj.")
                && client.frames().indexOf(f) > 0)).isTrue();
    }

    @Test
    void sayingGoodbyeClosesIt() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");

        runner.submit(new Command.Choose(client, optionSaying(client, "Bywaj.")));

        assertThat(client.await(f -> f.contains("\"dialogue\"") && !f.contains("\"text\"")))
                .as("a frame with nothing said is how the window is told to close")
                .isTrue();
    }

    @Test
    void thereIsNoTalkingToSomebodyAcrossTheMap() throws Exception {
        // The one check that matters for everything built on this afterwards:
        // healing, a shop and a quest would all inherit a way of using an NPC
        // without ever walking up to them.
        FakeClient client = join("Ala", 1, 1);
        int herbalist = idOf(client, "Zielarka");

        runner.submit(new Command.Talk(client, herbalist));

        assertThat(client.await(f -> f.contains("\"type\":\"error\"") && f.contains("bliżej")))
                .isTrue();
        assertThat(client.frames()).noneMatch(f -> f.contains("Witaj."));
    }

    @Test
    void anAnswerToAQuestionNobodyAskedIsRefused() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");

        // The greeting offers two. A client that sends a third is either out of
        // step or making them up, and both get the same answer.
        runner.submit(new Command.Choose(client, 7));

        assertThat(client.await(f -> f.contains("\"type\":\"error\"") && f.contains("odpowiedzi")))
                .isTrue();
    }

    @Test
    void walkingAwayEndsTheConversation() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");

        runner.submit(new Command.MoveTo(client, 10, 1));

        assertThat(client.await(f -> f.contains("\"dialogue\"") && !f.contains("\"text\"")))
                .as("nobody sends anything when they walk off, so only the server"
                        + " can notice - otherwise they talk to an empty screen")
                .isTrue();
    }

    @Test
    void theHerbalistPutsACharacterBackTogether() throws Exception {
        // The one cure in the game: nothing regenerates on its own, and dying
        // no longer heals either.
        FakeClient client = joinHurt("Ala", 5, 5, 4);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");

        runner.submit(new Command.Choose(client, optionSaying(client, "Opatrz mnie.")));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") == numberIn(f, "maxHp")))
                .as("being patched up should fill the bar, and say so to its owner")
                .isTrue();
    }

    @Test
    void beingHealedAlsoGetsAnAnswer() throws Exception {
        // A deed and a reply in one click. If healing had to end the
        // conversation, asking twice would mean walking away and back.
        FakeClient client = joinHurt("Ala", 5, 5, 4);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");

        runner.submit(new Command.Choose(client, optionSaying(client, "Opatrz mnie.")));

        assertThat(client.await(f -> f.contains("\"dialogue\"") && f.contains("Juz po wszystkim")))
                .isTrue();
    }

    @Test
    void healingDoesNotWipeThePriceOfHavingDied() throws Exception {
        // Weakness is what dying costs, not a wound. A healer who cleared it
        // would make dying free - and dying is already a free trip home.
        long weakened = System.currentTimeMillis() + 60_000;
        FakeClient client = join(hurtAndWeakened("Ala", 5, 5, 4, weakened));
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");

        runner.submit(new Command.Choose(client, optionSaying(client, "Opatrz mnie.")));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "hp") == numberIn(f, "maxHp"))).isTrue();

        assertThat(latestYou(client))
                .as("the wound is mended; the penalty is not")
                .doesNotContain("\"weakenedUntil\":0");
    }

    @Test
    void beingMendedIsWrittenDown() throws Exception {
        // Health is the one thing a healer changes, so if healing did not mark
        // the character as worth saving, the mending would last until the next
        // login and no further - and nothing else would report that.
        FakeClient client = joinHurt("Ala", 5, 5, 4);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");
        runner.submit(new Command.Choose(client, optionSaying(client, "Opatrz mnie.")));
        client.await(f -> f.contains("Juz po wszystkim"));

        // Logging out, not stopping the map: shutting a map down flushes
        // everybody whether or not anything changed, so it would have saved
        // this character just as happily with the mending thrown away.
        runner.submit(new Command.Detach(client));
        sleep(500);

        assertThat(saved.snapshots)
                .as("healing and logging straight out has to keep the healing")
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.nameKey()).isEqualTo("ala");
                    assertThat(snapshot.hp()).isGreaterThan(4);
                });
    }

    @Test
    void thereIsNoBeingHealedFromAcrossTheMap() throws Exception {
        // The range check on talking is what stops this, and it has to keep
        // stopping it: every function added later inherits the same door.
        FakeClient client = joinHurt("Ala", 1, 1, 4);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await(f -> f.contains("bliżej"));

        // Answering a question that was never asked, from the far end of the
        // map - so the number is written out here, because no frame offered one.
        runner.submit(new Command.Choose(client, 1));
        sleep(400);

        assertThat(client.frames())
                .noneMatch(f -> f.contains("\"type\":\"you\"")
                        && numberIn(f, "hp") == numberIn(f, "maxHp"));
    }

    @Test
    void anNpcCannotBeAttacked() throws Exception {
        // True today by accident, because nothing that is not a creature may be
        // attacked. Pinned here so that it stays true on purpose.
        FakeClient client = join("Ala", 5, 5);
        int herbalist = idOf(client, "Zielarka");

        runner.submit(new Command.Attack(client, herbalist));

        assertThat(client.await(f -> f.contains("\"type\":\"error\"") && f.contains("się bić")))
                .isTrue();
        assertThat(client.frames()).noneMatch(f -> f.contains("\"damage\""));
    }

    // ------------------------------------------------------------------

    private static List<String> namesIn(JsonNode frame) {
        List<String> names = new ArrayList<>();
        for (JsonNode actor : frame.path("actors")) {
            names.add(actor.path("name").asText());
        }
        return names;
    }

    private static JsonNode actorNamed(JsonNode frame, String name) {
        for (JsonNode actor : frame.path("actors")) {
            if (name.equals(actor.path("name").asText())) {
                return actor;
            }
        }
        throw new AssertionError("nobody called " + name);
    }

    private int idOf(FakeClient client, String name) throws Exception {
        return actorNamed(JSON.readTree(client.await("\"type\":\"init\"")), name).path("id").asInt();
    }

    private FakeClient join(String name, int x, int y) {
        return join(SavedCharacter.fresh(PlayerNames.key(name), name, PEN.id(), x, y, Direction.DOWN));
    }

    /** Somebody who has been in a fight and come out of it badly. */
    private FakeClient joinHurt(String name, int x, int y, int hp) {
        return join(hurtAndWeakened(name, x, y, hp, 0L));
    }

    private static SavedCharacter hurtAndWeakened(String name, int x, int y, int hp, long until) {
        return new SavedCharacter(PlayerNames.key(name), name, PEN.id(), x, y, Direction.DOWN,
                1, 0L, hp, until, Attributes.FRESH, 0, List.of(), null, 1, List.of());
    }

    private FakeClient join(SavedCharacter character) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L, character, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    /**
     * The number the server offered for that answer.
     *
     * <p>Written out by hand these were 0, 1 and 2 - until an option was added
     * in the middle of the greeting and three tests started answering a
     * different question from the one they meant. The index belongs on the
     * wire; it does not belong in a test's head.
     */
    private int optionSaying(FakeClient client, String text) throws Exception {
        JsonNode said = JSON.readTree(latest(client, "\"type\":\"dialogue\""));
        for (JsonNode option : said.path("options")) {
            if (text.equals(option.path("text").asText())) {
                return option.path("index").asInt();
            }
        }
        throw new AssertionError("nothing on offer says " + text + ": " + said);
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
