package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.BlessingLoader;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.CurrencyDefLoader;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.ItemSlot;
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
 * Elements inside a running fight.
 *
 * <p>Every weapon and creature here takes hold every single time, so these are
 * about what an element <em>does</em> rather than about how often it manages
 * it - how often is arithmetic, and is counted where the arithmetic lives.
 *
 * <p>The claim under all of them is that the five elements are five different
 * things. If cold merely hurt less than fire, there would be no reason for it
 * to exist.
 */
class ElementsInTheWorldTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final Map<String, MobDef> MOBS =
            new MobDefLoader("classpath:test-mobs-items/*.json", ITEMS).loadAll();
    private static final Content CONTENT = new Content(MOBS, ITEMS,
            new ClassDefLoader().loadAll(), new SkillDefLoader().loadAll(),
            Map.of(), new CurrencyDefLoader().loadAll(),
            new BlessingLoader("classpath:test-blessings/*.json").loadAll());
    /**
     * A map of its own, peopled by one creature per element. They are not on
     * the vault with everything else on purpose: four more creatures in a fight
     * over somebody's chest is four more things that can go wrong in a test
     * about chests.
     */
    private static final MapDef FORGE = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs-items/*.json", ITEMS),
            "classpath:test-maps-elements/*.json").loadAll().get("kuznia");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 30_000;

    private MapRunner runner;
    private Thread thread;

    @BeforeEach
    void startMap() {
        runner = new MapRunner(FORGE, JSON, WorldPersistence.NONE, CONTENT, 20);
        thread = new Thread(runner, "test-elements");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    // ---- what a brand leaves behind --------------------------------------

    @Test
    void aBrandSetsWhatItHitsAlight() throws Exception {
        FakeClient client = wielding("Ala", "probne-zarzewie");
        int guard = creatureNamed(client.await("\"type\":\"init\""), "Straznik");

        runner.submit(new Command.Attack(client, guard));

        assertThat(client.await(f -> f.contains("płonie"))).isTrue();
        assertThat(client.await(f -> hurtItself(f, guard)))
                .as("and it goes on burning in rounds nobody struck a blow in")
                .isTrue();
    }

    @Test
    void poisonFeedsOnTheBodyRatherThanOnTheBlow() throws Exception {
        // The guard has nine hundred health and a character of level one hits
        // for single figures. Poison that fed on the blow could never take
        // eighteen a round off it, and eighteen is what two per cent of a big
        // creature is.
        FakeClient client = wielding("Ala", "probny-kolec");
        int guard = creatureNamed(client.await("\"type\":\"init\""), "Straznik");

        runner.submit(new Command.Attack(client, guard));

        assertThat(client.await(f -> selfDamage(f, guard) >= 15))
                .as("poison is the one thing that troubles a big creature most")
                .isTrue();
    }

    // ---- and what it takes away ------------------------------------------

    @Test
    void theColdTakesTheQuicknessOutOfYou() throws Exception {
        FakeClient client = wielding("Ala", "probny-miecz");
        assertThat(numberIn(latest(client, "\"type\":\"you\""), "dodgePercent")).isPositive();
        int cold = creatureNamed(client.await("\"type\":\"init\""), "Zimnica");

        runner.submit(new Command.Attack(client, cold));

        assertThat(client.await(f -> f.contains("wychłodzony"))).isTrue();
        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "dodgePercent") == 0
                && numberIn(f, "secondBlowPercent") == 0))
                .as("no slipping away and no second blow while it lasts")
                .isTrue();
    }

    @Test
    void theSheetSaysWhatIsBurningYou() throws Exception {
        FakeClient client = wielding("Ala", "probny-miecz");
        int fire = creatureNamed(client.await("\"type\":\"init\""), "Ognik");

        runner.submit(new Command.Attack(client, fire));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && f.contains("\"ailments\"") && f.contains("\"element\":\"fire\"")))
                .as("the panel names it, with the rounds left and what it costs")
                .isTrue();
    }

    @Test
    void beingImmuneMeansNothingAtAll() throws Exception {
        // A charm against fire is a charm against burning too: the same
        // resistance answers the damage and the taking hold.
        FakeClient client = wielding("Ala", "probny-miecz", "probny-amulet-ognia");
        int fire = creatureNamed(client.await("\"type\":\"init\""), "Ognik");
        int full = numberIn(latest(client, "\"type\":\"you\""), "maxHp");

        runner.submit(new Command.Attack(client, fire));
        assertThat(client.await(f -> f.contains("\"damage\""))).isTrue();
        sleep(4_000); // two whole rounds of being hit by something made of fire

        assertThat(numberIn(latest(client, "\"type\":\"you\""), "hp"))
                .as("immune is immune: no damage at all")
                .isEqualTo(full);
        assertThat(client.frames()).noneMatch(f -> f.contains("płonie"));
    }

    @Test
    void everythingLetsGoWhenTheFightDoes() throws Exception {
        FakeClient client = wielding("Ala", "probny-miecz");
        int cold = creatureNamed(client.await("\"type\":\"init\""), "Zimnica");
        runner.submit(new Command.Attack(client, cold));
        assertThat(client.await(f -> f.contains("wychłodzony"))).isTrue();

        // Out of the fight, by whatever means: an affliction belongs to the
        // fight, like the energy a skill is paid for with.
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline && inFight(client)) {
            runner.submit(new Command.Flee(client));
            sleep(1_700);
        }

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && f.contains("\"ailments\":[]")
                && numberIn(f, "dodgePercent") > 0))
                .as("the cold goes with the fight, and the quickness comes back")
                .isTrue();
    }

    @Test
    void leavingAFightThatGoesOnStillPutsYourFireOut() throws Exception {
        // Two against one thing made of fire: when one of them runs, the fight
        // is not over, so nothing else ends it for them - and a burn that
        // outlived the fight it belongs to would go on burning on an empty road.
        //
        // Every roll comes off, so the escape is the round after the burn and
        // never later: this proves the fire was put out rather than that it had
        // three rounds to go out by itself. The chat would have said so if it
        // had, which is the other half of the claim below.
        rigged("test-elements-flee", (rig, alaId) -> {
            FakeClient ala = joinTo(rig, "Ala", 2L);
            FakeClient bela = joinTo(rig, "Bela", 3L);
            int fire = creatureNamed(ala.await("\"type\":\"init\""), "Ognik");
            rig.submit(new Command.Attack(ala, fire));
            rig.submit(new Command.Attack(bela, fire));
            assertThat(ala.await(f -> f.contains("płonie"))).isTrue();

            rig.submit(new Command.Flee(ala));
            assertThat(ala.await(f -> f.contains("ucieka z walki"))).isTrue();
            sleep(300);

            assertThat(latest(ala, "\"type\":\"you\"")).contains("\"ailments\":[]");
            assertThat(ala.frames())
                    .as("put out, not burnt out - it never said the fire had passed")
                    .noneMatch(f -> f.contains("przestaje płonąć"));
        });
    }

    @Test
    void killingWhatSetYouAlightPutsItOutToo() throws Exception {
        // The other way a fight ends: nobody leaves it, the last creature in it
        // dies. Everything it inflicted has to go with it.
        rigged("test-elements-kill", (rig, unused) -> {
            FakeClient ala = joinTo(rig, "Ala", 4L);
            int spark = creatureNamed(ala.await("\"type\":\"init\""), "Iskra");

            rig.submit(new Command.Attack(ala, spark));
            assertThat(ala.await(f -> f.contains("płonie"))).isTrue();
            assertThat(ala.await(f -> f.contains("\"died\":[" + spark))).isTrue();
            sleep(300);

            assertThat(latest(ala, "\"type\":\"you\"")).contains("\"ailments\":[]");
            assertThat(ala.frames())
                    .noneMatch(f -> f.contains("przestaje płonąć"));
        });
    }

    @Test
    void beingStunnedCostsTheRoundItself() throws Exception {
        // The one affliction that takes turns rather than health, which is
        // worse than health in a fight that is otherwise even. Rolled with
        // every chance coming off, so this counts rather than samples - and
        // with no agility, because a character that dodges everything is never
        // hit by the thing that stuns it.
        MapRunner rigged = new MapRunner(FORGE, JSON, WorldPersistence.NONE, CONTENT, 20,
                alwaysLucky());
        Thread riggedThread = new Thread(rigged, "test-elements-shock");
        riggedThread.setDaemon(true);
        riggedThread.start();
        try {
            FakeClient client = new FakeClient();
            rigged.submit(new Command.Join(client, 2L,
                    new SavedCharacter(PlayerNames.key("Bela"), "Bela", FORGE.id(), 5, 5,
                            Direction.DOWN, 3, 0L, -1, 0L, new Attributes(5, 0, 5), 0,
                            List.of(new StoredItem("m-1", "probny-miecz", ItemSlot.WEAPON, null)),
                            null, 1, List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY,
                            List.of()), 0));
            String init = client.await("\"type\":\"init\"");
            int self = JSON.readTree(init).path("selfId").asInt();
            int storm = creatureNamed(init, "Piorun");

            rigged.submit(new Command.Attack(client, storm));
            assertThat(client.await(f -> f.contains("drętwieje"))).isTrue();

            int struckBefore = blowsBy(client, self);
            sleep(1_700); // one whole round, spent numb

            assertThat(blowsBy(client, self))
                    .as("somebody who cannot move is not also swinging")
                    .isEqualTo(struckBefore);
        } finally {
            rigged.stop();
            riggedThread.join(2_000);
        }
    }

    // ------------------------------------------------------------------

    /** What a test does with a map whose every roll comes off. */
    private interface OnARiggedMap {
        void run(MapRunner rigged, int unused) throws Exception;
    }

    /**
     * Runs a test on a map where nothing is left to chance: every element takes
     * hold, every escape succeeds, every blow lands at the bottom of its swing.
     */
    private void rigged(String named, OnARiggedMap what) throws Exception {
        MapRunner rig = new MapRunner(FORGE, JSON, WorldPersistence.NONE, CONTENT, 20,
                alwaysLucky());
        Thread riggedThread = new Thread(rig, named);
        riggedThread.setDaemon(true);
        riggedThread.start();
        try {
            what.run(rig, 0);
        } finally {
            rig.stop();
            riggedThread.join(2_000);
        }
    }

    /**
     * Somebody with no agility at all, because on a map where every roll comes
     * off a character who can dodge dodges everything - including the blow that
     * is supposed to set them alight.
     */
    private FakeClient joinTo(MapRunner rig, String name, long account) {
        FakeClient client = new FakeClient();
        rig.submit(new Command.Join(client, account,
                new SavedCharacter(PlayerNames.key(name), name, FORGE.id(), 5, 5, Direction.DOWN,
                        3, 0L, -1, 0L, new Attributes(5, 0, 5), 0,
                        List.of(new StoredItem(name + "-m", "probny-miecz", ItemSlot.WEAPON, null)),
                        null, 1, List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY, List.of()), 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    /** Every roll lands at the bottom of its range, so every chance comes off. */
    private static java.util.Random alwaysLucky() {
        return new java.util.Random() {
            @Override
            public double nextDouble() {
                return 0.0;
            }
        };
    }

    private int blowsBy(FakeClient client, int actorId) throws Exception {
        int blows = 0;
        for (String frame : client.frames()) {
            for (JsonNode blow : JSON.readTree(frame).path("damage")) {
                if (blow.path("attacker").asInt() == actorId
                        && blow.path("target").asInt() != actorId) {
                    blows++;
                }
            }
        }
        return blows;
    }

    /** A blow struck by nobody: an affliction costing its bearer health. */
    private static boolean hurtItself(String frame, int actorId) {
        return selfDamage(frame, actorId) > 0;
    }

    private static int selfDamage(String frame, int actorId) {
        try {
            int most = 0;
            for (JsonNode blow : JSON.readTree(frame).path("damage")) {
                if (blow.path("attacker").asInt() == actorId
                        && blow.path("target").asInt() == actorId) {
                    most = Math.max(most, blow.path("amount").asInt());
                }
            }
            return most;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean inFight(FakeClient client) throws Exception {
        int self = -1;
        boolean fighting = false;
        for (String frame : client.frames()) {
            JsonNode node = JSON.readTree(frame);
            if ("init".equals(node.path("type").asText())) {
                self = node.path("selfId").asInt();
            }
            for (JsonNode change : node.path("fights")) {
                if (change.path("id").asInt() == self) {
                    fighting = change.path("inFight").asBoolean();
                }
            }
        }
        return fighting;
    }

    /** Joins with those things already on, which is the only state these tests need. */
    private FakeClient wielding(String name, String... worn) {
        List<StoredItem> items = new ArrayList<>();
        for (int i = 0; i < worn.length; i++) {
            ItemDef def = ITEMS.get(worn[i]);
            assertThat(def).as("no test item called %s", worn[i]).isNotNull();
            items.add(new StoredItem(worn[i] + "#" + i, worn[i], def.slot(), null));
        }
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L,
                new SavedCharacter(PlayerNames.key(name), name, FORGE.id(), 5, 5, Direction.DOWN,
                        3, 0L, -1, 0L, Attributes.FRESH, 0, items, null, 1,
                        List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY, List.of()), 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        assertThat(client.await(f -> f.contains("\"type\":\"you\""))).isTrue();
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
