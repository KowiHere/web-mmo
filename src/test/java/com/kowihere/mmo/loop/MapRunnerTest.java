package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real map thread and reads what comes out of it. These are slower
 * than unit tests by design: the bugs worth catching here are the ones that
 * only exist once the loop is actually ticking.
 */
class MapRunnerTest {

    private static final MapDef MAP = new MapDefLoader().loadAll().get("starter");
    private static final long TIMEOUT_MS = 5_000;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(MAP, new ObjectMapper(), saved);
        thread = new Thread(runner, "test-map");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void theFirstChatMessageIsDelivered() {
        // Regression: the cooldown used to be measured against a Long.MIN_VALUE
        // sentinel, and `tick - MIN_VALUE` overflows negative — so every actor's
        // very first message looked like it had been sent a moment ago.
        FakeClient client = join("Ala");

        runner.submit(new Command.Chat(client, "halo"));

        assertThat(client.await(frame -> frame.contains("\"chat\"") && frame.contains("halo")))
                .as("first chat message should reach the map's delta")
                .isTrue();
    }

    @Test
    void chatIsRateLimitedAfterTheFirstMessage() {
        FakeClient client = join("Ala");
        runner.submit(new Command.Chat(client, "pierwsza"));
        assertThat(client.await(f -> f.contains("pierwsza"))).isTrue();

        runner.submit(new Command.Chat(client, "natychmiast"));
        sleep(300);

        assertThat(client.frames()).noneMatch(f -> f.contains("natychmiast"));
    }

    @Test
    void aClickWalksTheActorTowardsTheTile() {
        FakeClient client = join("Ala");

        runner.submit(new Command.MoveTo(client, MAP.spawnX() - 3, MAP.spawnY()));

        assertThat(client.await(f -> f.contains("\"moved\"")))
                .as("a reachable destination should produce steps")
                .isTrue();
        assertThat(client.await(f -> f.contains("\"x\":" + (MAP.spawnX() - 3))))
                .as("the actor should arrive at the requested tile")
                .isTrue();
    }

    @Test
    void aTileInsideAWallProducesNoMovementAtAll() {
        FakeClient client = join("Ala");
        client.frames().clear();

        runner.submit(new Command.MoveTo(client, 0, 0)); // the map border
        sleep(600);

        assertThat(client.frames()).noneMatch(f -> f.contains("\"moved\""));
    }

    @Test
    void twoClientsOnOneMapSeeEachOther() {
        FakeClient ala = join("Ala");
        FakeClient bob = join("Bob");

        assertThat(ala.await(f -> f.contains("\"joined\"") && f.contains("Bob")))
                .as("Ala should be told that Bob arrived")
                .isTrue();

        runner.submit(new Command.MoveTo(bob, MAP.spawnX(), MAP.spawnY() - 2));
        assertThat(ala.await(f -> f.contains("\"moved\"")))
                .as("Ala should see Bob move")
                .isTrue();
    }

    @Test
    void aReconnectWithTheRightTokenResumesTheSameActor() {
        FakeClient first = join("Ala");
        String token = extract(first.await("\"type\":\"init\""), "\"token\":\"", "\"");
        String actorId = extract(first.await("\"type\":\"init\""), "\"selfId\":", ",");

        runner.submit(new Command.Detach(first));
        sleep(300);

        FakeClient second = new FakeClient();
        runner.submit(new Command.Join(second, "Ala", token, 0));

        assertThat(second.await(f -> f.contains("\"selfId\":" + actorId)))
                .as("the same actor should be handed back, not a new one")
                .isTrue();
    }

    @Test
    void aSecondHelloOnOneSocketDoesNotCreateASecondCharacter() {
        // Regression: an unguarded second `hello` used to hand the socket a new
        // actor and orphan the first one. The orphan kept a live client, so
        // online() stayed true and the reaper never touched it - it sat on the
        // map until the process restarted, and the socket got every delta twice.
        FakeClient ala = join("Ala");
        runner.submit(new Command.Join(ala, "Ala", null, 0));
        sleep(400);

        FakeClient observer = join("Observer");
        String init = observer.await("\"type\":\"init\"");

        assertThat(countActors(init))
                .as("the world should hold Ala and the observer, nothing else")
                .isEqualTo(2);
    }

    @Test
    void aSecondHelloOnOneSocketDoesNotDisconnectTheClient() {
        // Regression: the "same character opened twice" branch disconnected the
        // existing socket without checking it was not the very socket asking.
        FakeClient ala = join("Ala");
        String token = extract(ala.await("\"type\":\"init\""), "\"token\":\"", "\"");

        runner.submit(new Command.Join(ala, "Ala", token, 0));
        sleep(400);

        assertThat(ala.disconnected)
                .as("a client must not be dropped for re-introducing itself")
                .isFalse();
    }

    @Test
    void aBurstOfMoveCommandsCostsAtMostOnePathSearchPerTick() {
        // Regression: every move command used to trigger a full A* immediately,
        // so one socket clicking in a loop could pin the map thread and delay
        // the tick for everyone else on the map.
        FakeClient ala = join("Ala");
        long before = runner.pathSearches();

        // Every target is open ground on row spawnY-1, walked right to left.
        for (int i = 0; i < 50; i++) {
            runner.submit(new Command.MoveTo(ala, MAP.spawnX() - (i % 5), MAP.spawnY() - 1));
        }
        final int lastX = MAP.spawnX() - (49 % 5);
        sleep(400);

        long searches = runner.pathSearches() - before;
        assertThat(searches)
                .as("50 clicks in one burst should not buy 50 searches")
                .isLessThanOrEqualTo(4);
        assertThat(ala.await(f -> f.contains("\"moved\"")))
                .as("the actor should still act on the burst")
                .isTrue();
        assertThat(ala.await(f -> f.contains("\"x\":" + lastX + ",\"y\":" + (MAP.spawnY() - 1))))
                .as("coalescing must honour the most recent click, not the first")
                .isTrue();
    }

    @Test
    void aReturningCharacterStartsWhereItLeftOff() {
        SavedCharacter stored = new SavedCharacter("Ala", MAP.id(), 3, 1, Direction.LEFT);
        FakeClient client = new FakeClient();

        runner.submit(new Command.Join(client, "Ala", null, 0, stored));

        String init = client.await("\"type\":\"init\"");
        assertThat(init)
                .as("the character should be placed at its stored tile, not the spawn")
                .contains("\"x\":3,\"y\":1");
    }

    @Test
    void aStoredPositionInsideAWallFallsBackToTheSpawn() {
        // Maps get edited between sessions. Waking up inside a wall would leave
        // a player permanently stuck, with no way to walk out of it.
        SavedCharacter walledIn = new SavedCharacter("Ala", MAP.id(), 0, 0, Direction.DOWN);
        FakeClient client = new FakeClient();

        runner.submit(new Command.Join(client, "Ala", null, 0, walledIn));

        String init = client.await("\"type\":\"init\"");
        assertThat(init).contains("\"x\":" + MAP.spawnX() + ",\"y\":" + MAP.spawnY());
    }

    @Test
    void aStoredPositionFromAnotherMapIsIgnored() {
        SavedCharacter elsewhere = new SavedCharacter("Ala", "some-other-map", 3, 1, Direction.LEFT);
        FakeClient client = new FakeClient();

        runner.submit(new Command.Join(client, "Ala", null, 0, elsewhere));

        String init = client.await("\"type\":\"init\"");
        assertThat(init).contains("\"x\":" + MAP.spawnX() + ",\"y\":" + MAP.spawnY());
    }

    @Test
    void aNameAlreadyBeingPlayedIsRefused() {
        join("Ala");

        FakeClient impostor = new FakeClient();
        runner.submit(new Command.Join(impostor, "ala", null, 0, null)); // note the case
        sleep(400);

        assertThat(impostor.await(f -> f.contains("\"type\":\"error\"")))
                .as("a name in use should be refused, case-insensitively")
                .isTrue();
        assertThat(impostor.frames()).noneMatch(f -> f.contains("\"type\":\"init\""));
    }

    @Test
    void leavingHandsThePositionToPersistence() {
        FakeClient client = join("Ala");
        runner.submit(new Command.MoveTo(client, MAP.spawnX() - 2, MAP.spawnY()));
        assertThat(client.await(f -> f.contains("\"x\":" + (MAP.spawnX() - 2)))).isTrue();

        runner.submit(new Command.Detach(client));
        sleep(400);

        assertThat(saved.snapshots)
                .as("detaching is exactly when a position is worth keeping")
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.nameKey()).isEqualTo("ala");
                    assertThat(snapshot.mapId()).isEqualTo(MAP.id());
                    assertThat(snapshot.x()).isEqualTo(MAP.spawnX() - 2);
                });
    }

    @Test
    void aCharacterThatNeverMovedIsNotWrittenAgainAndAgain() {
        FakeClient client = join("Ala");
        runner.submit(new Command.Detach(client));
        sleep(400);
        int afterLeaving = saved.snapshots.size();

        sleep(500);

        assertThat(saved.snapshots.size())
                .as("an idle character should not be re-saved on every pass")
                .isEqualTo(afterLeaving);
    }

    // ------------------------------------------------------------------

    private static final class RecordingPersistence implements WorldPersistence {

        private final List<ActorSnapshot> snapshots = new CopyOnWriteArrayList<>();

        @Override
        public void save(ActorSnapshot snapshot) {
            snapshots.add(snapshot);
        }
    }

    private static int countActors(String initFrame) {
        int start = initFrame.indexOf("\"actors\":[");
        return initFrame.substring(start).split("\"name\":", -1).length - 1;
    }

    private FakeClient join(String name) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, name, null, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    private static String extract(String frame, String after, String until) {
        int start = frame.indexOf(after) + after.length();
        return frame.substring(start, frame.indexOf(until, start));
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
        private volatile boolean disconnected;

        @Override
        public void send(String json) {
            frames.add(json);
        }

        @Override
        public void disconnect(String reason) {
            disconnected = true;
        }

        @Override
        public String describe() {
            return "fake client";
        }

        List<String> frames() {
            return frames;
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

        /** Returns the first frame containing {@code needle}, or fails the test. */
        String await(String needle) {
            assertThat(await(f -> f.contains(needle))).as("frame containing %s", needle).isTrue();
            return frames.stream().filter(f -> f.contains(needle)).findFirst().orElseThrow();
        }
    }
}
