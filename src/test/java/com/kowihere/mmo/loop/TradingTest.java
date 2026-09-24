package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
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
import com.kowihere.mmo.world.Shop;
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
 * Buying and selling inside a running map, driven with raw commands rather than
 * through the interface — which is exactly what a client is free not to use.
 *
 * <p>The test this whole design exists for is {@link #goldIsWorthlessToSomebodyWhoDealsInFangs}.
 * Money being plural is the reason none of this is a column called gold, and a
 * purse full of the wrong kind buying nothing is the only way to show it.
 */
class TradingTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final Map<String, CurrencyDef> MONEY =
            new CurrencyDefLoader("classpath:test-currencies/*.json").loadAll();
    private static final MapDef MARKET = new MapDefLoader(
            new MobDefLoader("classpath:test-mobs/no-such-mob-*.json", ITEMS, MONEY),
            new NpcDefLoader("classpath:test-npcs-trade/*.json", ITEMS, MONEY),
            "classpath:test-maps-trade/*.json").loadAll().get("targ");
    private static final Content CONTENT = new Content(
            Map.of(), ITEMS, new com.kowihere.mmo.world.ClassDefLoader().loadAll(),
            new com.kowihere.mmo.world.SkillDefLoader().loadAll(), Map.of(), MONEY);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_MS = 10_000;
    private static final int SWORD_PRICE = 60;

    private MapRunner runner;
    private Thread thread;
    private RecordingPersistence saved;

    @BeforeEach
    void startMap() {
        saved = new RecordingPersistence();
        runner = new MapRunner(MARKET, JSON, saved, CONTENT, 20);
        thread = new Thread(runner, "test-market");
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopMap() throws InterruptedException {
        runner.stop();
        thread.join(2_000);
    }

    // ---- what the plural design is for -------------------------------

    @Test
    void goldIsWorthlessToSomebodyWhoDealsInFangs() throws Exception {
        // A purse with a thousand gold in it and no fangs buys nothing from the
        // chest. Written as a column this could not even be asked.
        FakeClient client = joinRich("Ala", 1, 6, "zloto", 1_000);
        openStall(client, "Skrzynia", "Unies wieko.");

        runner.submit(new Command.Buy(client, "probny-topor"));

        assertThat(client.await(f -> f.contains("\"type\":\"error\"") && f.contains("Za mało")))
                .isTrue();
        assertThat(carriedIn(latest(client, "\"type\":\"bag\""))).isEmpty();
        assertThat(amountOf(client, "zloto"))
                .as("and a refused purchase must not quietly cost anything either")
                .isEqualTo(1_000);
    }

    @Test
    void theRightMoneyBuysFromTheSameChest() throws Exception {
        FakeClient client = joinRich("Ala", 1, 6, "kly", 200);
        openStall(client, "Skrzynia", "Unies wieko.");

        runner.submit(new Command.Buy(client, "probny-topor"));

        assertThat(client.await(f -> f.contains("probny-topor") && f.contains("\"carried\"")))
                .isTrue();
    }

    // ---- buying ------------------------------------------------------

    @Test
    void buyingTakesExactlyThePriceAndGivesExactlyOne() throws Exception {
        FakeClient client = joinRich("Ala", 5, 5, "zloto", 100);
        openStall(client, "Kupiec", "Pokaz towar.");

        runner.submit(new Command.Buy(client, "probny-miecz"));
        assertThat(client.await(f -> f.contains("probny-miecz") && f.contains("\"carried\""))).isTrue();

        assertThat(amountOf(client, "zloto")).isEqualTo(100 - SWORD_PRICE);
        assertThat(carriedIn(latest(client, "\"type\":\"bag\""))).containsExactly("probny-miecz");
    }

    @Test
    void thereIsNoBuyingWhatIsNotOnTheShelf() throws Exception {
        FakeClient client = joinRich("Ala", 5, 5, "zloto", 1_000);
        openStall(client, "Kupiec", "Pokaz towar.");

        runner.submit(new Command.Buy(client, "probny-topor"));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("Tego tu nie ma"));
        assertThat(amountOf(client, "zloto")).isEqualTo(1_000);
    }

    @Test
    void thereIsNoBuyingFromAcrossTheMap() throws Exception {
        // The stall opens from a conversation, so it inherits the conversation's
        // door. Every function added later gets the same one for free.
        FakeClient client = joinRich("Ala", 10, 1, "zloto", 1_000);

        runner.submit(new Command.Buy(client, "probny-miecz"));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("Nie ma tu z kim handlować"));
        assertThat(amountOf(client, "zloto")).isEqualTo(1_000);
    }

    @Test
    void walkingAwayFromAStallEndsTheTrading() throws Exception {
        // The real door, and the only one: a conversation ends when you leave,
        // and trade is something you do inside a conversation. This is what
        // makes a second range check on buying unnecessary.
        FakeClient client = joinRich("Ala", 5, 5, "zloto", 1_000);
        openStall(client, "Kupiec", "Pokaz towar.");

        runner.submit(new Command.MoveTo(client, 10, 1));
        assertThat(client.await(f -> f.contains("\"x\":10,\"y\":1"))).isTrue();
        int after = client.frames().size();
        runner.submit(new Command.Buy(client, "probny-miecz"));

        assertThat(client.awaitAfter(after, f -> f.contains("Nie ma tu z kim handlować")))
                .isTrue();
        assertThat(amountOf(client, "zloto")).isEqualTo(1_000);
    }

    @Test
    void aFullBagIsRefusedBeforeTheMoneyMoves() throws Exception {
        // Taking payment and then having nowhere to put the goods is the one
        // outcome nobody forgives.
        FakeClient client = joinRich("Ala", 5, 5, "zloto", 10_000);
        openStall(client, "Kupiec", "Pokaz towar.");
        for (int i = 0; i < Inventory.CAPACITY; i++) {
            runner.submit(new Command.Buy(client, "probny-miecz"));
        }
        assertThat(client.await(f -> f.contains("\"type\":\"bag\"")
                && carriedIn(f).size() == Inventory.CAPACITY)).isTrue();
        int before = amountOf(client, "zloto");

        runner.submit(new Command.Buy(client, "probny-miecz"));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("Plecak jest pełny"));
        assertThat(amountOf(client, "zloto"))
                .as("a refused purchase costs nothing")
                .isEqualTo(before);
    }

    // ---- selling -----------------------------------------------------

    @Test
    void sellingPaysAFractionAndTakesTheThingAway() throws Exception {
        FakeClient client = joinRich("Ala", 5, 5, "zloto", 100);
        openStall(client, "Kupiec", "Pokaz towar.");
        runner.submit(new Command.Buy(client, "probny-miecz"));
        assertThat(client.await(f -> f.contains("probny-miecz") && f.contains("\"carried\""))).isTrue();
        String sword = firstCarriedId(latest(client, "\"type\":\"bag\""));

        runner.submit(new Command.Sell(client, sword));

        // Waited on the sale itself, not on an empty bag: the bag was empty on
        // arrival too, so that await passed before anything had happened at all
        // and every assertion below it read the state from before the sale.
        assertThat(client.await(f -> f.contains("* sprzedaje:"))).isTrue();
        assertThat(carriedIn(latest(client, "\"type\":\"bag\""))).isEmpty();
        assertThat(amountOf(client, "zloto"))
                .as("what a round trip costs is the only way money ever leaves this world")
                .isEqualTo(100 - SWORD_PRICE + Shop.buybackPrice(SWORD_PRICE));
        assertThat(amountOf(client, "zloto")).isLessThan(100);
    }

    @Test
    void aTraderWillNotBuyWhatTheyDoNotSell() throws Exception {
        FakeClient client = joinRich("Ala", 1, 6, "kly", 500);
        openStall(client, "Skrzynia", "Unies wieko.");
        runner.submit(new Command.Buy(client, "probny-topor"));
        assertThat(client.await(f -> f.contains("probny-topor") && f.contains("\"carried\""))).isTrue();
        String axe = firstCarriedId(latest(client, "\"type\":\"bag\""));

        // Carry it to the other trader, who deals in gold and in other goods.
        runner.submit(new Command.StopTalking(client));
        runner.submit(new Command.MoveTo(client, 5, 5));
        assertThat(client.await(f -> f.contains("\"x\":5,\"y\":5"))).isTrue();
        openStall(client, "Kupiec", "Pokaz towar.");

        runner.submit(new Command.Sell(client, axe));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("tego nie skupuje"));
        assertThat(amountOf(client, "zloto")).isZero();
    }

    @Test
    void whatIsBeingWornIsNotForSale() throws Exception {
        // Quietly taking it off on the player's behalf is how somebody sells the
        // sword they were holding and finds out in the next fight.
        FakeClient client = joinRich("Ala", 5, 5, "zloto", 100);
        openStall(client, "Kupiec", "Pokaz towar.");
        runner.submit(new Command.Buy(client, "probny-miecz"));
        assertThat(client.await(f -> f.contains("probny-miecz") && f.contains("\"carried\""))).isTrue();
        String sword = firstCarriedId(latest(client, "\"type\":\"bag\""));

        runner.submit(new Command.StopTalking(client));
        runner.submit(new Command.Equip(client, sword));
        assertThat(client.await(f -> f.contains("\"worn\":[{"))).isTrue();
        openStall(client, "Kupiec", "Pokaz towar.");
        int before = amountOf(client, "zloto");

        runner.submit(new Command.Sell(client, sword));
        sleep(400);

        assertThat(client.frames()).anyMatch(f -> f.contains("Zdejmij, zanim sprzedasz"));
        assertThat(amountOf(client, "zloto")).isEqualTo(before);
        assertThat(latest(client, "\"type\":\"bag\"")).contains("\"worn\":[{");
    }

    // ---- what is written down ----------------------------------------

    @Test
    void moneySpentIsWrittenDownOnLoggingStraightOut() throws Exception {
        // Through Detach rather than by stopping the map: shutting a map down
        // flushes everybody whether or not anything changed, so it would save
        // this character just as happily with the purchase thrown away.
        FakeClient client = joinRich("Ala", 5, 5, "zloto", 100);
        openStall(client, "Kupiec", "Pokaz towar.");
        runner.submit(new Command.Buy(client, "probny-miecz"));
        assertThat(client.await(f -> f.contains("probny-miecz") && f.contains("\"carried\""))).isTrue();

        runner.submit(new Command.Detach(client));
        sleep(500);

        assertThat(saved.snapshots)
                .anySatisfy(snapshot -> {
                    assertThat(snapshot.nameKey()).isEqualTo("ala");
                    assertThat(snapshot.coins())
                            .as("the saved purse should be the one that paid, not the one before")
                            .containsExactly(new StoredCoin("zloto", 100 - SWORD_PRICE));
                });
    }

    @Test
    void walkingAboutDoesNotRewriteThePurse() throws Exception {
        FakeClient client = joinRich("Ala", 5, 5, "zloto", 100);
        client.await("\"type\":\"purse\"");

        runner.submit(new Command.MoveTo(client, 9, 2));
        assertThat(client.await(f -> f.contains("\"x\":9,\"y\":2"))).isTrue();
        runner.submit(new Command.Detach(client));
        sleep(500);

        assertThat(saved.snapshots)
                .as("null means leave those rows alone, and a walk changes no money")
                .anySatisfy(snapshot -> assertThat(snapshot.coins()).isNull());
    }

    // ------------------------------------------------------------------

    /**
     * Walks up, says the thing that opens the stall, and waits for it.
     *
     * <p>Every wait here is for a frame that arrived <em>after</em> the command,
     * because these tests open a stall twice. A plain await scans the whole
     * history, so the second one matched the first one's frames and read the
     * options off a conversation that had already moved on.
     */
    private void openStall(FakeClient client, String who, String option) throws Exception {
        int before = client.frames().size();
        runner.submit(new Command.Talk(client, idOf(client, who)));
        assertThat(client.awaitAfter(before, f -> f.contains("\"" + option + "\"")))
                .as("%s should greet with %s on offer", who, option)
                .isTrue();

        int asked = client.frames().size();
        runner.submit(new Command.Choose(client, optionSaying(client, option)));
        assertThat(client.awaitAfter(asked, f -> f.contains("\"type\":\"shop\"")
                && f.contains("\"goods\"")))
                .as("%s should open a stall", who)
                .isTrue();
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

    /**
     * The number the server offered for that answer.
     *
     * <p>The latest frame that actually asked something: closing a conversation
     * sends a dialogue frame too, and taking the last one of any kind meant
     * looking for options in the frame that says there are none.
     */
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

    private FakeClient joinRich(String name, int x, int y, String currencyId, int amount) {
        SavedCharacter character = new SavedCharacter(PlayerNames.key(name), name, MARKET.id(),
                x, y, Direction.DOWN, 1, 0L, -1, 0L, Attributes.FRESH, 0, List.of(), null, 1,
                List.of(), List.of(new StoredCoin(currencyId, amount)));
        FakeClient client = new FakeClient();
        runner.submit(new Command.Join(client, 1L, character, 0));
        assertThat(client.await(f -> f.contains("\"type\":\"init\""))).isTrue();
        return client;
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

        /** The same, but only counting frames that arrived after {@code from}. */
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
