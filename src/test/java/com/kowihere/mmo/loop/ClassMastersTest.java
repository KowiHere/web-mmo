package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.SkillPrices;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.CurrencyDef;
import com.kowihere.mmo.world.CurrencyDefLoader;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDefLoader;
import com.kowihere.mmo.world.NpcDefLoader;
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
 * The class masters, driven with raw commands rather than through the panel.
 *
 * <p>They sell nothing. A player spends their own points from their own
 * interface wherever they are standing; what a master offers is that same point
 * for half the price, and the only way to take points back. Which means the two
 * tests that matter are {@link #theSamePointCostsHalfAtYourOwnMaster} and
 * {@link #somebodyElsesMasterIsJustAConversation} — without the second, three
 * masters would be one NPC wearing three names.
 */
class ClassMastersTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final Map<String, CurrencyDef> MONEY =
            new CurrencyDefLoader("classpath:test-currencies/*.json").loadAll();
    private static final Map<String, com.kowihere.mmo.world.ClassDef> CLASSES =
            new ClassDefLoader().loadAll();
    private static final MapDef SCHOOL = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/no-such-mob-*.json", ITEMS, MONEY),
            new NpcDefLoader("classpath:test-npcs-master/*.json", ITEMS, MONEY, CLASSES),
            "classpath:test-maps-master/*.json").loadAll().get("szkola");
    private static final Content CONTENT = new Content(
            Map.of(), ITEMS, CLASSES, new SkillDefLoader().loadAll(), Map.of(), MONEY);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 10_000;
    private static final String SKILL = SkillDefLoader.REGENERATION_ID;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(SCHOOL, JSON, saved, CONTENT, 20);
        thread = new Thread(runner, "test-school");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    // ---- the price -----------------------------------------------------

    @Test
    void theFirstRankIsFreeSoANewCharacterCanTouchTheSystem() throws Exception {
        FakeClient client = join("Ala", 5, 5, 1, 0);

        runner.submit(new Command.Learn(client, SKILL));

        assertThat(client.await(f -> f.contains("\"type\":\"skills\"") && rankOf(f, SKILL) == 1))
                .as("with no money at all")
                .isTrue();
    }

    @Test
    void aFurtherRankCostsMoneyFromTheCharacterPanel() throws Exception {
        FakeClient client = join("Ala", 5, 5, 4, 1_000);
        learnOnce(client);
        int price = SkillPrices.toRaise(1, 4);

        runner.submit(new Command.Learn(client, SKILL));

        assertThat(client.await(f -> f.contains("\"type\":\"skills\"") && rankOf(f, SKILL) == 2))
                .isTrue();
        assertThat(goldOf(client)).isEqualTo(1_000 - price);
    }

    @Test
    void aPointNobodyCanPayForStaysUnspent() throws Exception {
        FakeClient client = join("Ala", 5, 5, 4, 0);
        learnOnce(client);

        runner.submit(new Command.Learn(client, SKILL));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("Za mało"));
        assertThat(rankOf(latest(client, "\"type\":\"skills\""), SKILL)).isEqualTo(1);
        assertThat(numberIn(latestYou(client), "skillPoints"))
                .as("a refused purchase costs neither money nor the point")
                .isEqualTo(3);
    }

    @Test
    void theSamePointCostsHalfAtYourOwnMaster() throws Exception {
        // The whole of what a master is for, besides undoing.
        FakeClient client = join("Ala", 5, 5, 4, 1_000);
        learnOnce(client);
        talkTo(client, "Mistrz Miecza");
        int half = SkillPrices.atMaster(SkillPrices.toRaise(1, 4));

        runner.submit(new Command.Learn(client, SKILL));

        assertThat(client.await(f -> f.contains("\"type\":\"skills\"") && rankOf(f, SKILL) == 2))
                .isTrue();
        assertThat(goldOf(client)).isEqualTo(1_000 - half);
        assertThat(half).isLessThan(SkillPrices.toRaise(1, 4));
    }

    @Test
    void somebodyElsesMasterIsJustAConversation() throws Exception {
        // Without this, three masters are one NPC wearing three names.
        FakeClient client = join("Ala", 1, 6, 4, 1_000);
        learnOnce(client);
        talkTo(client, "Magister"); // Ala is a warrior

        runner.submit(new Command.Learn(client, SKILL));

        assertThat(client.await(f -> f.contains("\"type\":\"skills\"") && rankOf(f, SKILL) == 2))
                .isTrue();
        assertThat(goldOf(client))
                .as("the mage master charges a warrior the full price, being nobody to them")
                .isEqualTo(1_000 - SkillPrices.toRaise(1, 4));
    }

    @Test
    void thePriceOnTheWireFollowsWhereYouStand() throws Exception {
        FakeClient client = join("Ala", 5, 5, 4, 1_000);
        int full = numberIn(latestYou(client), "skillPointPrice");

        talkTo(client, "Mistrz Miecza");
        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "skillPointPrice") == SkillPrices.atMaster(full)))
                .as("walking up should halve the price the panel shows, unasked")
                .isTrue();

        runner.submit(new Command.StopTalking(client));
        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "skillPointPrice") == full))
                .as("and walking off should put it back")
                .isTrue();
    }

    // ---- taking it back ------------------------------------------------

    @Test
    void aMasterGivesEveryPointBack() throws Exception {
        FakeClient client = join("Ala", 5, 5, 4, 1_000);
        learnOnce(client);
        talkTo(client, "Mistrz Miecza");
        runner.submit(new Command.Learn(client, SKILL));
        assertThat(client.await(f -> f.contains("\"type\":\"skills\"") && rankOf(f, SKILL) == 2))
                .isTrue();
        int before = goldOf(client);
        int price = numberIn(latestYou(client), "skillResetPrice");

        // Counted from here on. A plain await for rank zero matched the frame
        // sent on arrival, when the rank was zero because nothing had happened
        // yet - so every assertion below it read the state from before the
        // reset, and the reset itself was never checked at all.
        int asked = client.frames().size();
        runner.submit(new Command.Choose(client, optionSaying(client, "Zwroc mi punkty.")));

        assertThat(client.awaitAfter(asked, f -> f.contains("\"type\":\"skills\"")
                && rankOf(f, SKILL) == 0))
                .as("every rank should be gone")
                .isTrue();
        assertThat(numberIn(latestYou(client), "skillPoints"))
                .as("and every point should be back to spend again")
                .isEqualTo(4);
        assertThat(goldOf(client)).isEqualTo(before - price);
        assertThat(price)
                .as("undoing two ranks, one of which was free, costs one point at the master's rate")
                .isEqualTo(SkillPrices.atMaster(SkillPrices.toRaise(1, 4)));
    }

    @Test
    void thereIsNothingToUndoForSomebodyWhoNeverSpent() throws Exception {
        FakeClient client = join("Ala", 5, 5, 4, 1_000);
        talkTo(client, "Mistrz Miecza");

        runner.submit(new Command.Choose(client, optionSaying(client, "Zwroc mi punkty.")));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("Nie masz czego cofać"));
        assertThat(goldOf(client)).isEqualTo(1_000);
    }

    @Test
    void aResetNobodyCanPayForChangesNothing() throws Exception {
        FakeClient client = join("Ala", 5, 5, 4, 0);
        learnOnce(client);
        talkTo(client, "Mistrz Miecza");
        // Rank two would cost money, so get there the only free way: it cannot
        // be done. Instead put the character at a rank it was given for free
        // and hand it a bill it cannot pay by levelling the price up.
        runner.submit(new Command.Learn(client, SKILL));
        sleep(300);

        runner.submit(new Command.Choose(client, optionSaying(client, "Zwroc mi punkty.")));
        sleep(400);

        // Only the free first rank was ever taken, so there is nothing to pay
        // and nothing to refuse - which is itself the rule working.
        assertThat(rankOf(latest(client, "\"type\":\"skills\""), SKILL)).isZero();
        assertThat(goldOf(client)).isZero();
    }

    @Test
    void anotherClassesMasterWillNotUndoYourChoices() throws Exception {
        FakeClient client = join("Ala", 1, 6, 4, 1_000);
        learnOnce(client);
        talkTo(client, "Magister");

        runner.submit(new Command.Choose(client, optionSaying(client, "Zwroc mi punkty.")));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("uczy innej klasy"));
        assertThat(rankOf(latest(client, "\"type\":\"skills\""), SKILL)).isEqualTo(1);
    }

    // ---- what is written down ------------------------------------------

    @Test
    void goldSpentOnARankIsWrittenDownOnLoggingStraightOut() throws Exception {
        FakeClient client = join("Ala", 5, 5, 4, 1_000);
        learnOnce(client);
        runner.submit(new Command.Learn(client, SKILL));
        assertThat(client.await(f -> f.contains("\"type\":\"skills\"") && rankOf(f, SKILL) == 2))
                .isTrue();

        runner.submit(new Command.Detach(client));
        sleep(500);

        assertThat(saved.snapshots).anySatisfy(snapshot -> {
            assertThat(snapshot.nameKey()).isEqualTo("ala");
            assertThat(snapshot.coins())
                    .containsExactly(new StoredCoin("zloto", 1_000 - SkillPrices.toRaise(1, 4)));
        });
    }

    // ------------------------------------------------------------------

    private void learnOnce(FakeClient client) throws Exception {
        runner.submit(new Command.Learn(client, SKILL));
        assertThat(client.await(f -> f.contains("\"type\":\"skills\"") && rankOf(f, SKILL) == 1))
                .isTrue();
    }

    private void talkTo(FakeClient client, String who) throws Exception {
        int before = client.frames().size();
        runner.submit(new Command.Talk(client, idOf(client, who)));
        assertThat(client.awaitAfter(before, f -> f.contains("\"type\":\"dialogue\"")
                && f.contains("\"options\""))).as("%s should say something", who).isTrue();
    }

    private static int rankOf(String skillsFrame, String skillId) {
        try {
            for (JsonNode skill : JSON.readTree(skillsFrame).path("skills")) {
                if (skillId.equals(skill.path("id").asText())) {
                    return skill.path("rank").asInt();
                }
            }
            return -1;
        } catch (Exception e) {
            throw new AssertionError("unreadable skills frame " + skillsFrame, e);
        }
    }

    private int goldOf(FakeClient client) throws Exception {
        for (JsonNode coin : JSON.readTree(latest(client, "\"type\":\"purse\"")).path("coins")) {
            if (coin.path("primary").asBoolean()) {
                return coin.path("amount").asInt();
            }
        }
        throw new AssertionError("the purse never named a primary currency");
    }

    private int optionSaying(FakeClient client, String text) throws Exception {
        JsonNode said = JSON.readTree(latest(client, "\"options\""));
        for (JsonNode option : said.path("options")) {
            if (text.equals(option.path("text").asText())) {
                return option.path("index").asInt();
            }
        }
        throw new AssertionError("nothing on offer says " + text + ": " + said);
    }

    private int idOf(FakeClient client, String name) throws Exception {
        for (JsonNode actor : JSON.readTree(client.await("\"type\":\"init\"")).path("actors")) {
            if (name.equals(actor.path("name").asText())) {
                return actor.path("id").asInt();
            }
        }
        throw new AssertionError("nobody called " + name);
    }

    private FakeClient join(String name, int x, int y, int level, int gold) {
        SavedCharacter character = new SavedCharacter(PlayerNames.key(name), name, SCHOOL.id(),
                x, y, Direction.DOWN, level, 0L, -1, 0L, Attributes.FRESH, 0, List.of(),
                "wojownik", level, List.of(), List.of(new StoredCoin("zloto", gold)),
                Deposit.EMPTY, Deposit.EMPTY);
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L, character, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
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

        boolean awaitAfter(int from, Predicate<String> match) {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                List<String> seen = frames();
                if (seen.subList(Math.min(from, seen.size()), seen.size()).stream().anyMatch(match)) {
                    return true;
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
