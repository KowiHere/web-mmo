package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
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
 * Putting things away, taking them back, and paying for room to do it — inside
 * a running map, driven with raw commands.
 *
 * <p>The chest is the first thing in this game that belongs to an account
 * rather than to a character, and {@link #whatGoesOnTheSharedShelfIsWrittenAgainstTheAccount}
 * is what that claim comes down to.
 */
class UsingTheChestTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final Map<String, CurrencyDef> MONEY =
            new CurrencyDefLoader("classpath:test-currencies/*.json").loadAll();
    private static final MapDef VAULT = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/no-such-mob-*.json", ITEMS, MONEY),
            new NpcDefLoader("classpath:test-npcs-vault/*.json", ITEMS, MONEY),
            "classpath:test-maps-vault/*.json").loadAll().get("skarbiec");
    private static final Content CONTENT = new Content(
            Map.of(), ITEMS, new ClassDefLoader().loadAll(), new SkillDefLoader().loadAll(),
            Map.of(), MONEY);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 10_000;
    private static final long ACCOUNT = 42L;
    private static final int FIRST_TAB_PRICE = 100;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

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

    // ---- putting things away -----------------------------------------

    @Test
    void whatIsPutAwayLeavesTheBagAndLandsInTheTab() throws Exception {
        FakeClient client = joinCarrying("Ala", "probny-miecz");
        openChest(client);
        String sword = firstCarriedId(latest(client, "\"type\":\"bag\""));
        int after = client.frames().size();

        runner.submit(new Command.Deposit(client, sword, 0, false));

        assertThat(client.awaitAfter(after, f -> f.contains("\"type\":\"bag\"")
                && carriedIn(f).isEmpty())).isTrue();
        assertThat(keptIn(latest(client, "\"type\":\"storage\""), "character"))
                .containsExactly("probny-miecz");
    }

    @Test
    void andComesBackOutAgain() throws Exception {
        FakeClient client = joinCarrying("Ala", "probny-miecz");
        openChest(client);
        String sword = firstCarriedId(latest(client, "\"type\":\"bag\""));
        runner.submit(new Command.Deposit(client, sword, 0, false));
        assertThat(client.await(f -> f.contains("\"type\":\"storage\"")
                && f.contains("probny-miecz"))).isTrue();
        int after = client.frames().size();

        runner.submit(new Command.Withdraw(client, sword, false));

        assertThat(client.awaitAfter(after, f -> f.contains("\"type\":\"bag\"")
                && carriedIn(f).contains("probny-miecz"))).isTrue();
        assertThat(keptIn(latest(client, "\"type\":\"storage\""), "character")).isEmpty();
    }

    @Test
    void whatIsBeingWornCannotBePutAway() throws Exception {
        // The same rule as selling, and for the same reason: taking somebody's
        // sword off for them is how they walk into the next fight unarmed.
        FakeClient client = joinCarrying("Ala", "probny-miecz");
        String sword = firstCarriedId(client.await("\"type\":\"bag\""));
        runner.submit(new Command.Equip(client, sword));
        assertThat(client.await(f -> f.contains("\"worn\":[{"))).isTrue();
        openChest(client);
        int after = client.frames().size();

        runner.submit(new Command.Deposit(client, sword, 0, false));

        assertThat(client.awaitAfter(after, f -> f.contains("Zdejmij, zanim odłożysz"))).isTrue();
        assertThat(latest(client, "\"type\":\"bag\"")).contains("\"worn\":[{");
    }

    @Test
    void thereIsNoReachingIntoAChestFromAcrossTheMap() throws Exception {
        // The chest opens from a conversation, so it inherits the conversation's
        // door - the same one the stall got for free.
        FakeClient client = joinCarrying("Ala", "probny-miecz");
        String sword = firstCarriedId(client.await("\"type\":\"bag\""));

        runner.submit(new Command.Deposit(client, sword, 0, false));

        assertThat(client.await(f -> f.contains("Nie ma tu gdzie niczego odłożyć"))).isTrue();
    }

    @Test
    void takingSomethingOutWithNowhereToPutItIsRefusedBeforeItMoves() throws Exception {
        // An item taken out of a chest into a full bag is an item that has
        // stopped existing. The character arrives with a full bag and one thing
        // already put away, which is the only way to reach this state on a map
        // with nobody on it to buy from.
        FakeClient client = join(withAFullBagAndSomethingStored());
        openChest(client);
        int after = client.frames().size();

        runner.submit(new Command.Withdraw(client, "odlozony", false));

        assertThat(client.awaitAfter(after, f -> f.contains("Plecak jest pełny"))).isTrue();
        assertThat(keptIn(latest(client, "\"type\":\"storage\""), "character"))
                .as("and the thing stays where it was")
                .containsExactly("probny-miecz");
    }

    @Test
    void aTabNobodyHasBoughtDoesNotAcceptAnything() throws Exception {
        FakeClient client = joinCarrying("Ala", "probny-miecz");
        openChest(client);
        String sword = firstCarriedId(latest(client, "\"type\":\"bag\""));
        int after = client.frames().size();

        runner.submit(new Command.Deposit(client, sword, 1, false));

        assertThat(client.awaitAfter(after, f -> f.contains("Nie masz takiej zakładki"))).isTrue();
        assertThat(carriedIn(latest(client, "\"type\":\"bag\"")))
                .containsExactly("probny-miecz");
    }

    @Test
    void aFullTabTakesNothingMore() throws Exception {
        // The character arrives with a tab already full and one thing in hand,
        // which is the only way to reach this on a map with nothing to kill.
        FakeClient client = join(withAFullTabAndOneInHand());
        openChest(client);
        int after = client.frames().size();

        runner.submit(new Command.Deposit(client, "w-reku", 0, false));

        assertThat(client.awaitAfter(after, f -> f.contains("Ta zakładka jest pełna"))).isTrue();
        assertThat(carriedIn(latest(client, "\"type\":\"bag\"")))
                .as("and what was in hand stays in hand")
                .containsExactly("probny-miecz");
    }

    // ---- paying for room ---------------------------------------------

    @Test
    void buyingATabTakesExactlyThePriceAndOpensOne() throws Exception {
        FakeClient client = joinRich("Ala", "zloto", 500);
        openChest(client);
        assertThat(tabsIn(latest(client, "\"type\":\"storage\""), "character")).isEqualTo(1);
        int after = client.frames().size();

        runner.submit(new Command.BuyTab(client, false));

        assertThat(client.awaitAfter(after, f -> f.contains("\"type\":\"storage\"")
                && tabsIn(f, "character") == 2)).isTrue();
        assertThat(amountOf(client, "zloto")).isEqualTo(500 - FIRST_TAB_PRICE);
    }

    @Test
    void andTheNextOneCostsMore() throws Exception {
        FakeClient client = joinRich("Ala", "zloto", 500);
        openChest(client);
        runner.submit(new Command.BuyTab(client, false));
        assertThat(client.await(f -> f.contains("\"type\":\"storage\"")
                && tabsIn(f, "character") == 2)).isTrue();
        int after = client.frames().size();

        runner.submit(new Command.BuyTab(client, false));

        assertThat(client.awaitAfter(after, f -> f.contains("\"type\":\"storage\"")
                && tabsIn(f, "character") == 3)).isTrue();
        assertThat(amountOf(client, "zloto")).isEqualTo(500 - 100 - 300);
    }

    @Test
    void whenThePriceListRunsOutSoDoTheTabs() throws Exception {
        FakeClient client = joinRich("Ala", "zloto", 10_000);
        openChest(client);
        runner.submit(new Command.BuyTab(client, false));
        runner.submit(new Command.BuyTab(client, false));
        assertThat(client.await(f -> f.contains("\"type\":\"storage\"")
                && tabsIn(f, "character") == 3)).isTrue();
        int before = amountOf(client, "zloto");
        int after = client.frames().size();

        runner.submit(new Command.BuyTab(client, false));

        assertThat(client.awaitAfter(after, f -> f.contains("Więcej zakładek już nie będzie")))
                .isTrue();
        assertThat(amountOf(client, "zloto"))
                .as("a refused purchase costs nothing")
                .isEqualTo(before);
    }

    @Test
    void anEmptyPurseBuysNoRoom() throws Exception {
        FakeClient client = joinRich("Ala", "zloto", 10);
        openChest(client);
        int after = client.frames().size();

        runner.submit(new Command.BuyTab(client, false));

        assertThat(client.awaitAfter(after, f -> f.contains("Za mało"))).isTrue();
        assertThat(tabsIn(latest(client, "\"type\":\"storage\""), "character")).isEqualTo(1);
    }

    @Test
    void theSharedShelfIsPaidForInSomethingElseEntirely() throws Exception {
        // Gold buys room in your own chest and nothing at all in the account's.
        // The same claim as a purse full of the wrong money at the trader.
        FakeClient client = joinRich("Ala", "zloto", 10_000);
        openChest(client);
        int after = client.frames().size();

        runner.submit(new Command.BuyTab(client, true));

        assertThat(client.awaitAfter(after, f -> f.contains("Za mało"))).isTrue();
        assertThat(tabsIn(latest(client, "\"type\":\"storage\""), "account")).isEqualTo(1);
        assertThat(amountOf(client, "zloto")).isEqualTo(10_000);
    }

    // ---- money in the chest -------------------------------------------

    @Test
    void moneyCanBeLeftAndFetchedBack() throws Exception {
        FakeClient client = joinRich("Ala", "zloto", 500);
        openChest(client);

        runner.submit(new Command.DepositCoins(client, "zloto", 200, false));
        assertThat(client.await(f -> f.contains("\"type\":\"purse\"") && f.contains("\"amount\":300")))
                .isTrue();
        assertThat(chestCoinsIn(latest(client, "\"type\":\"storage\""), "character")).isEqualTo(200);

        int after = client.frames().size();
        runner.submit(new Command.WithdrawCoins(client, "zloto", 200, false));

        assertThat(client.awaitAfter(after, f -> f.contains("\"type\":\"purse\"")
                && f.contains("\"amount\":500"))).isTrue();
        assertThat(chestCoinsIn(latest(client, "\"type\":\"storage\""), "character")).isZero();
    }

    @Test
    void moneyThatIsNotThereCannotBeLeft() throws Exception {
        FakeClient client = joinRich("Ala", "zloto", 50);
        openChest(client);
        int after = client.frames().size();

        runner.submit(new Command.DepositCoins(client, "zloto", 200, false));

        assertThat(client.awaitAfter(after, f -> f.contains("Nie masz tyle"))).isTrue();
        assertThat(amountOf(client, "zloto")).isEqualTo(50);
    }

    @Test
    void norFetchedFromAChestThatHasNone() throws Exception {
        FakeClient client = joinRich("Ala", "zloto", 500);
        openChest(client);
        int after = client.frames().size();

        runner.submit(new Command.WithdrawCoins(client, "zloto", 100, false));

        assertThat(client.awaitAfter(after, f -> f.contains("Tyle tu nie leży"))).isTrue();
        assertThat(amountOf(client, "zloto"))
                .as("and a refused withdrawal must not quietly mint any")
                .isEqualTo(500);
    }

    @Test
    void payingForRoomCannotBeDoneOutOfTheChestItself() throws Exception {
        // Money put away is put away. A keeper who could reach into the chest
        // to pay himself would make the deposit mean nothing.
        FakeClient client = joinRich("Ala", "zloto", 500);
        openChest(client);
        runner.submit(new Command.DepositCoins(client, "zloto", 450, false));
        assertThat(client.await(f -> f.contains("\"type\":\"purse\"") && f.contains("\"amount\":50")))
                .isTrue();
        int after = client.frames().size();

        runner.submit(new Command.BuyTab(client, false));

        assertThat(client.awaitAfter(after, f -> f.contains("Za mało"))).isTrue();
        assertThat(chestCoinsIn(latest(client, "\"type\":\"storage\""), "character")).isEqualTo(450);
    }

    // ---- what is written down ------------------------------------------

    @Test
    void whatIsPutAwayIsWrittenDownOnLoggingStraightOut() throws Exception {
        FakeClient client = joinCarrying("Ala", "probny-miecz");
        openChest(client);
        String sword = firstCarriedId(latest(client, "\"type\":\"bag\""));
        runner.submit(new Command.Deposit(client, sword, 0, false));
        assertThat(client.await(f -> f.contains("\"type\":\"storage\"")
                && f.contains("probny-miecz"))).isTrue();

        runner.submit(new Command.Detach(client));
        sleep(500);

        assertThat(saved.snapshots).anySatisfy(snapshot -> {
            assertThat(snapshot.deposit()).isNotNull();
            assertThat(snapshot.deposit().items())
                    .containsExactly(new StoredDeposit(sword, "probny-miecz", 0));
            assertThat(snapshot.items())
                    .as("and the bag is written in the same breath, minus the sword")
                    .isEmpty();
        });
    }

    @Test
    void whatGoesOnTheSharedShelfIsWrittenAgainstTheAccount() throws Exception {
        FakeClient client = joinCarrying("Ala", "probny-miecz");
        openChest(client);
        String sword = firstCarriedId(latest(client, "\"type\":\"bag\""));
        runner.submit(new Command.Deposit(client, sword, 0, true));
        assertThat(client.await(f -> f.contains("\"type\":\"storage\"")
                && f.contains("probny-miecz"))).isTrue();

        runner.submit(new Command.Detach(client));
        sleep(500);

        assertThat(saved.snapshots).anySatisfy(snapshot -> {
            assertThat(snapshot.accountId())
                    .as("the shelf belongs to the account, not to the character standing at it")
                    .isEqualTo(ACCOUNT);
            assertThat(snapshot.accountDeposit().items())
                    .containsExactly(new StoredDeposit(sword, "probny-miecz", 0));
            assertThat(snapshot.deposit())
                    .as("and the character's own chest is not even rewritten")
                    .isNull();
        });
    }

    @Test
    void walkingAboutDoesNotRewriteEitherChest() throws Exception {
        FakeClient client = joinRich("Ala", "zloto", 100);
        client.await("\"type\":\"purse\"");

        runner.submit(new Command.MoveTo(client, 9, 2));
        assertThat(client.await(f -> f.contains("\"x\":9,\"y\":2"))).isTrue();
        runner.submit(new Command.Detach(client));
        sleep(500);

        assertThat(saved.snapshots).anySatisfy(snapshot -> {
            assertThat(snapshot.deposit())
                    .as("null means leave those rows alone, and a walk moves nothing")
                    .isNull();
            assertThat(snapshot.accountDeposit()).isNull();
        });
    }

    @Test
    void whatWasStoredIsThereOnComingBack() throws Exception {
        // The round trip the whole milestone is for, without a database in it:
        // the chest is read back exactly as the bag is.
        SavedCharacter returning = new SavedCharacter(PlayerNames.key("Ola"), "Ola", VAULT.id(),
                5, 5, Direction.DOWN, 1, 0L, -1, 0L, Attributes.FRESH, 0, List.of(), null, 1,
                List.of(), List.of(),
                new Deposit(2, List.of(new StoredDeposit("stary-miecz", "probny-miecz", 1)),
                        List.of(new StoredCoin("zloto", 70))),
                Deposit.EMPTY);
        FakeClient client = join(returning);
        openChest(client);

        String chest = latest(client, "\"type\":\"storage\"");
        assertThat(tabsIn(chest, "character")).isEqualTo(2);
        assertThat(keptIn(chest, "character")).containsExactly("probny-miecz");
        assertThat(chestCoinsIn(chest, "character")).isEqualTo(70);
        assertThat(tabOf(chest, "stary-miecz"))
                .as("and in the tab it was left in, not merely somewhere in the chest")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private void openChest(FakeClient client) throws Exception {
        int before = client.frames().size();
        runner.submit(new Command.Talk(client, idOf(client, "Magazynier")));
        assertThat(client.awaitAfter(before, f -> f.contains("\"Otwieraj.\""))).isTrue();

        int asked = client.frames().size();
        runner.submit(new Command.Choose(client, optionSaying(client, "Otwieraj.")));
        assertThat(client.awaitAfter(asked, f -> f.contains("\"type\":\"storage\"")
                && f.contains("\"chests\""))).isTrue();
    }

    private SavedCharacter withAFullTabAndOneInHand() {
        List<StoredDeposit> tab = new ArrayList<>();
        for (int i = 0; i < Storage.TAB; i++) {
            tab.add(new StoredDeposit("odlozony-" + i, "probny-miecz", 0));
        }
        return new SavedCharacter(PlayerNames.key("Ala"), "Ala", VAULT.id(), 5, 5,
                Direction.DOWN, 1, 0L, -1, 0L, Attributes.FRESH, 0,
                List.of(new StoredItem("w-reku", "probny-miecz", null)), null, 1,
                List.of(), List.of(), new Deposit(1, tab, List.of()), Deposit.EMPTY);
    }

    private SavedCharacter withAFullBagAndSomethingStored() {
        List<StoredItem> bag = new ArrayList<>();
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            bag.add(new StoredItem("rzecz-" + i, "probny-miecz", null));
        }
        return new SavedCharacter(PlayerNames.key("Ala"), "Ala", VAULT.id(), 5, 5,
                Direction.DOWN, 1, 0L, -1, 0L, Attributes.FRESH, 0, bag, null, 1,
                List.of(), List.of(),
                new Deposit(1, List.of(new StoredDeposit("odlozony", "probny-miecz", 0)),
                        List.of()),
                Deposit.EMPTY);
    }

    private FakeClient joinCarrying(String name, String... itemIds) {
        List<StoredItem> items = new ArrayList<>();
        for (int i = 0; i < itemIds.length; i++) {
            items.add(new StoredItem("rzecz-" + i, itemIds[i], null));
        }
        return join(new SavedCharacter(PlayerNames.key(name), name, VAULT.id(), 5, 5,
                Direction.DOWN, 1, 0L, -1, 0L, Attributes.FRESH, 0, items, null, 1,
                List.of(), List.of(), Deposit.EMPTY, Deposit.EMPTY));
    }

    private FakeClient joinRich(String name, String currencyId, int amount) {
        return join(new SavedCharacter(PlayerNames.key(name), name, VAULT.id(), 5, 5,
                Direction.DOWN, 1, 0L, -1, 0L, Attributes.FRESH, 0, List.of(), null, 1,
                List.of(), List.of(new StoredCoin(currencyId, amount)),
                Deposit.EMPTY, Deposit.EMPTY));
    }

    private FakeClient join(SavedCharacter character) {
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, ACCOUNT, character, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
    }

    private int idOf(FakeClient client, String name) throws Exception {
        for (JsonNode actor : JSON.readTree(client.await("\"type\":\"init\"")).path("actors")) {
            if (name.equals(actor.path("name").asText())) {
                return actor.path("id").asInt();
            }
        }
        throw new AssertionError("nobody called " + name);
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

    private int amountOf(FakeClient client, String currencyId) throws Exception {
        JsonNode purse = JSON.readTree(latest(client, "\"type\":\"purse\""));
        for (JsonNode coin : purse.path("coins")) {
            if (currencyId.equals(coin.path("id").asText())) {
                return coin.path("amount").asInt();
            }
        }
        throw new AssertionError("the purse never mentioned " + currencyId + ": " + purse);
    }

    private static JsonNode chest(String storageFrame, String scope) {
        try {
            for (JsonNode chest : JSON.readTree(storageFrame).path("chests")) {
                if (scope.equals(chest.path("scope").asText())) {
                    return chest;
                }
            }
        } catch (Exception e) {
            throw new AssertionError("unreadable chest " + storageFrame, e);
        }
        throw new AssertionError("no " + scope + " chest in " + storageFrame);
    }

    private static List<String> keptIn(String storageFrame, String scope) {
        List<String> ids = new ArrayList<>();
        for (JsonNode kept : chest(storageFrame, scope).path("items")) {
            ids.add(kept.path("item").path("defId").asText());
        }
        return ids;
    }

    private static int tabOf(String storageFrame, String itemId) {
        for (JsonNode kept : chest(storageFrame, "character").path("items")) {
            if (itemId.equals(kept.path("item").path("id").asText())) {
                return kept.path("tab").asInt();
            }
        }
        throw new AssertionError("nothing called " + itemId + " in " + storageFrame);
    }

    private static int tabsIn(String storageFrame, String scope) {
        return chest(storageFrame, scope).path("tabs").asInt();
    }

    private static int chestCoinsIn(String storageFrame, String scope) {
        for (JsonNode coin : chest(storageFrame, scope).path("coins")) {
            if (coin.path("primary").asBoolean()) {
                return coin.path("amount").asInt();
            }
        }
        throw new AssertionError("no primary money in " + storageFrame);
    }

    private static List<String> carriedIn(String bagFrame) {
        try {
            List<String> ids = new ArrayList<>();
            for (JsonNode item : JSON.readTree(bagFrame).path("carried")) {
                ids.add(item.path("defId").asText());
            }
            return ids;
        } catch (Exception e) {
            throw new AssertionError("unreadable bag " + bagFrame, e);
        }
    }

    private static String firstCarriedId(String bagFrame) throws Exception {
        return JSON.readTree(bagFrame).path("carried").get(0).path("id").asText();
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
            return awaitAfter(0, match);
        }

        String await(String needle) {
            assertThat(await(f -> f.contains(needle))).isTrue();
            List<String> seen = frames();
            for (int i = seen.size() - 1; i >= 0; i--) {
                if (seen.get(i).contains(needle)) {
                    return seen.get(i);
                }
            }
            throw new AssertionError("unreachable");
        }
    }
}
