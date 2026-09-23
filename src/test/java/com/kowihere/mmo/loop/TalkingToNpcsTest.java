package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @BeforeEach
    void startMap() {
        runner = new MapRunner(PEN, JSON, WorldPersistence.NONE, Content.EMPTY, 20);
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
        assertThat(said.path("options")).hasSize(2);
        assertThat(said.path("options").get(0).path("index").asInt())
                .as("the client answers by index, so the index has to be on the wire")
                .isZero();
    }

    @Test
    void choosingAnOptionLeadsWhereTheContentSaidItWould() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");

        runner.submit(new Command.Choose(client, 0));

        assertThat(client.await(f -> f.contains("Krwawnik"))).isTrue();

        // And back again: a tree that only goes downwards is a list.
        runner.submit(new Command.Choose(client, 0));
        assertThat(client.await(f -> f.contains("\"dialogue\"") && f.contains("Witaj.")
                && client.frames().indexOf(f) > 0)).isTrue();
    }

    @Test
    void sayingGoodbyeClosesIt() throws Exception {
        FakeClient client = join("Ala", 5, 5);
        runner.submit(new Command.Talk(client, idOf(client, "Zielarka")));
        client.await("\"Witaj.\"");

        runner.submit(new Command.Choose(client, 1));

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
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                SavedCharacter.fresh(PlayerNames.key(name), name, PEN.id(), x, y, Direction.DOWN), 0));
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
