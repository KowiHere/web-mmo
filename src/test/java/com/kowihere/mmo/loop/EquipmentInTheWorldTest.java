package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.ItemSlot;
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
 * Items inside a running map.
 *
 * <p>On a vault of its own with a creature that hands over a known item every
 * time: what is under test is whether equipment works, not whether a boar
 * happens to be feeling generous today.
 */
class EquipmentInTheWorldTest {

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

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;
    private final Map<String, String> given = new java.util.HashMap<>();

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(VAULT, JSON, saved, CONTENT, 20);
        thread = new Thread(runner, "test-vault");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    @Test
    void killingSomethingPutsItsLootInTheBag() throws Exception {
        FakeClient client = join("Ala");
        int skarbek = creatureNamed(client.await("\"type\":\"init\""), "Skarbek");

        runner.submit(new Command.Attack(client, skarbek));

        assertThat(client.await(f -> f.contains("\"type\":\"bag\"") && f.contains("probny-miecz")))
                .as("what it dropped should be in the bag, addressed to the killer alone")
                .isTrue();
    }

    @Test
    void wearingSomethingChangesWhatTheCharacterIs() throws Exception {
        FakeClient client = join("Ala", "probny-miecz");
        String sword = idOf("probny-miecz");

        int attackBefore = numberIn(latestYou(client), "attack");
        runner.submit(new Command.Equip(client, sword));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "attack") > attackBefore))
                .as("a sword that does not change your attack is a decoration")
                .isTrue();
    }

    @Test
    void anItemGrantingAnAttributeRaisesEverythingThatAttributeFeeds() throws Exception {
        // The jerkin grants strength, and strength is health. Nothing stores
        // maximum health, so this is the whole chain working or not at all.
        FakeClient client = join("Ala", "probny-kaftan");
        String jerkin = idOf("probny-kaftan");

        int maxHpBefore = numberIn(latestYou(client), "maxHp");
        runner.submit(new Command.Equip(client, jerkin));

        assertThat(client.await(f -> f.contains("\"type\":\"you\"")
                && numberIn(f, "maxHp") > maxHpBefore))
                .as("strength from an item has to reach health like any other strength")
                .isTrue();
    }

    @Test
    void aSecondWeaponReplacesTheFirstRatherThanStacking() throws Exception {
        FakeClient client = join("Ala", "probny-miecz", "probny-topor");
        String sword = idOf("probny-miecz");   // +5 attack
        String axe = idOf("probny-topor");     // +9 attack

        runner.submit(new Command.Equip(client, sword));
        assertThat(client.await(f -> f.contains("\"type\":\"bag\"") && wornIds(f).contains(sword)))
                .isTrue();
        runner.submit(new Command.Equip(client, axe));
        assertThat(client.await(f -> f.contains("\"type\":\"bag\"") && wornIds(f).contains(axe)))
                .isTrue();

        String bag = latest(client, "\"type\":\"bag\"");
        assertThat(wornIds(bag))
                .as("one weapon hand, one weapon")
                .containsExactly(axe);
        assertThat(carriedIds(bag))
                .as("and the one it replaced went back to the bag rather than nowhere")
                .contains(sword);
    }

    @Test
    void aLevelRequirementIsTheServersToEnforce() throws Exception {
        // Sent as a raw command, exactly as a client that never draws the item
        // could still send it. An interface that hides the button is a courtesy;
        // it is not the rule.
        FakeClient client = join("Ala", "relikt-mistrza"); // requires level 9
        String relic = idOf("relikt-mistrza");

        runner.submit(new Command.Equip(client, relic));
        sleep(400);

        assertThat(wornIds(latest(client, "\"type\":\"bag\"")))
                .as("a level-one character must not be wearing a level-nine relic")
                .doesNotContain(relic);
        assertThat(client.frames())
                .as("and should be told why, rather than left wondering")
                .anyMatch(f -> f.contains("\"type\":\"error\"") && f.contains("poziomu"));
    }

    @Test
    void changingClothesIsRefusedInTheMiddleOfAFight() throws Exception {
        // The same reasoning that refuses movement: otherwise the best combat
        // tactic in the game would be getting changed between rounds.
        //
        // The opponent is the one with nine hundred health, not the one that
        // drops the sword. Against the latter this used to pass or fail
        // depending on the damage roll: two good blows ended the fight before
        // the equip command was drained, and the refusal it is waiting for
        // never came because by then there was nothing to refuse.
        FakeClient client = join("Ala", "probny-miecz");
        String sword = idOf("probny-miecz");
        int guard = creatureNamed(client.await("\"type\":\"init\""), "Straznik");

        runner.submit(new Command.Attack(client, guard));
        assertThat(client.await(f -> f.contains("\"damage\""))).isTrue();
        runner.submit(new Command.Equip(client, sword));
        sleep(400);

        assertThat(client.frames())
                .anyMatch(f -> f.contains("\"type\":\"error\"") && f.contains("walce"));
    }

    @Test
    void aPointCanBeSpentOnceAndOnlyOnce() throws Exception {
        FakeClient client = join("Ala");
        runner.submit(new Command.Spend(client, Attributes.Attribute.STRENGTH));
        sleep(300);

        // A fresh character has earned nothing yet, so the first attempt is
        // already one too many.
        assertThat(client.frames())
                .as("points that were never earned must not be spendable")
                .anyMatch(f -> f.contains("\"type\":\"error\"") && f.contains("punkt"));
        assertThat(numberIn(latestYou(client), "strength"))
                .isEqualTo(Attributes.STARTING);
    }

    @Test
    void whatWasFoundAndWornOutlivesTheMap() throws Exception {
        FakeClient client = join("Ala", "probny-miecz");
        String sword = idOf("probny-miecz");
        runner.submit(new Command.Equip(client, sword));
        assertThat(client.await(f -> f.contains("\"type\":\"bag\"") && wornIds(f).contains(sword)))
                .isTrue();

        runner.stop();
        thread.join(2_000);

        assertThat(saved.snapshots)
                .as("an item nobody can find again after a restart is an item nobody earned")
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.nameKey()).isEqualTo("ala");
                    assertThat(snapshot.items()).isNotNull();
                    assertThat(snapshot.items())
                            .anySatisfy(item -> {
                                assertThat(item.defId()).isEqualTo("probny-miecz");
                                assertThat(item.slot()).isEqualTo(ItemSlot.WEAPON);
                            });
                });
    }

    @Test
    void walkingAboutDoesNotRewriteTheBag() throws Exception {
        // Items are written by replacing every row a character owns. Doing that
        // on every ordinary position save would be twenty pointless writes a
        // minute for somebody who is only walking.
        //
        // Nothing is equipped here on purpose: putting something on *should*
        // write the rows, and a save carrying that change would look exactly
        // like the bug this is watching for.
        FakeClient client = join("Ala", "probny-miecz");

        runner.submit(new Command.MoveTo(client, 8, 5));
        long deadline = System.currentTimeMillis() + 25_000;
        while (saved.snapshots.isEmpty() && System.currentTimeMillis() < deadline) {
            sleep(100);
        }

        assertThat(saved.snapshots)
                .as("no save happened at all, so this would prove nothing either way")
                .isNotEmpty();
        assertThat(saved.snapshots)
                .as("a save made by walking should leave the item rows alone")
                .allSatisfy(snapshot -> assertThat(snapshot.items()).isNull());
    }

    // ------------------------------------------------------------------

    /**
     * The id this character's copy of an item was given when it joined.
     *
     * <p>Items arrive through the same path a returning player's do - written
     * into the character that is handed to the map - rather than through a
     * back door added for tests. That way this exercises the loading of a bag
     * as well as everything it is really about.
     */
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

    private static List<String> wornIds(String bagFrame) {
        return idsUnder(bagFrame, "worn");
    }

    private static List<String> carriedIds(String bagFrame) {
        return idsUnder(bagFrame, "carried");
    }

    private static List<String> idsUnder(String bagFrame, String field) {
        List<String> ids = new ArrayList<>();
        try {
            for (JsonNode item : JSON.readTree(bagFrame).path(field)) {
                ids.add(item.path("id").asText());
            }
        } catch (Exception e) {
            throw new AssertionError("unreadable frame " + bagFrame, e);
        }
        return ids;
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

    private FakeClient join(String name, String... carrying) {
        List<StoredItem> items = new ArrayList<>();
        for (String defId : carrying) {
            String id = java.util.UUID.randomUUID().toString();
            given.put(defId, id);
            items.add(new StoredItem(id, defId, null));
        }
        SavedCharacter fresh = SavedCharacter.fresh(PlayerNames.key(name), name, VAULT.id(), 5, 5,
                Direction.DOWN);
        SavedCharacter character = new SavedCharacter(fresh.nameKey(), fresh.name(), fresh.mapId(),
                fresh.x(), fresh.y(), fresh.dir(), fresh.level(), fresh.xp(), fresh.hp(),
                fresh.wakesAt(), fresh.attributes(), fresh.unspentPoints(), items,
                fresh.classId(), fresh.skillPoints(), fresh.skills(), fresh.coins(),
                Deposit.EMPTY, Deposit.EMPTY);

        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, ACCOUNT, character, 0));
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
