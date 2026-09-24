package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Money is content, and a trader's stall is content, so both are checked at
 * startup rather than discovered by a player whose purse will not open a door.
 *
 * <p>The refusals matter more than the successes here. A currency nobody holds,
 * a shelf holding something that is not an item, a price in money that does not
 * exist — none of those would say anything at runtime. They would simply be a
 * shop that opens and cannot be bought from.
 */
class MoneyAsContentTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final Map<String, CurrencyDef> MONEY =
            new CurrencyDefLoader("classpath:test-currencies/*.json").loadAll();

    private NpcDefLoader npcs(String location) {
        return new NpcDefLoader(location, ITEMS, MONEY);
    }

    // ---- the registry ------------------------------------------------

    @Test
    void theShippedCurrenciesLoadInTheOrderContentAsksFor() {
        Map<String, CurrencyDef> shipped = new CurrencyDefLoader().loadAll();

        assertThat(shipped.keySet())
                .as("sorted by id, the odd currency would come first and that would be an"
                        + " accident dressed up as a decision")
                .containsExactly("zloto", "kly");
        assertThat(shipped.get("zloto").shortName()).isEqualTo("zł");
    }

    @Test
    void thereIsMoreThanOneKindOfMoney() {
        // The whole reason none of this is a column called gold.
        assertThat(new CurrencyDefLoader().loadAll()).hasSizeGreaterThan(1);
    }

    @Test
    void refusesTwoCurrenciesWithTheSameId() {
        assertThatThrownBy(() ->
                new CurrencyDefLoader("classpath:bad-currencies-duplicate/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("talar");
    }

    @Test
    void exactlyOneCurrencyIsTheOneTheGameChargesIn() {
        // Spending a skill point from the character panel has a price and
        // nobody standing there to say which coin they want.
        assertThat(new CurrencyDefLoader().loadAll().values())
                .filteredOn(CurrencyDef::primary)
                .singleElement()
                .extracting(CurrencyDef::id)
                .isEqualTo("zloto");
    }

    @Test
    void refusesARegistryWithNoPrimaryCurrency() {
        assertThatThrownBy(() ->
                new CurrencyDefLoader("classpath:bad-currencies-no-primary/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary");
    }

    @Test
    void refusesARegistryWithTwoOfThem() {
        // Two would mean the same thing has two prices, and which one you paid
        // would depend on which currency the loader happened to see first.
        assertThatThrownBy(() ->
                new CurrencyDefLoader("classpath:bad-currencies-two-primary/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primary");
    }

    // ---- the stall ---------------------------------------------------

    @Test
    void aTraderDealsInOneNamedCurrency() {
        NpcDef trader = new NpcDefLoader().loadAll().get("skrzynia");

        assertThat(trader.kind())
                .as("a thing can trade; kind and function stay independent")
                .isEqualTo(NpcKind.OBJECT);
        assertThat(trader.shop().currencyId()).isEqualTo("kly");
        assertThat(trader.shop().dealsIn("wilczy-naszyjnik")).isTrue();
        assertThat(trader.shop().dealsIn("zardzewialy-miecz"))
                .as("a trader deals in what is on their own shelf")
                .isFalse();
    }

    @Test
    void refusesAStallPricedInMoneyThatDoesNotExist() {
        assertThatThrownBy(() -> npcs("classpath:bad-npcs-shop-currency/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("muszelki");
    }

    @Test
    void refusesAStallHoldingSomethingThatIsNotAnItem() {
        assertThatThrownBy(() -> npcs("classpath:bad-npcs-shop-item/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("miecz-ktorego-nie-ma");
    }

    @Test
    void refusesAStallWithNothingOnIt() {
        // It would open, show nothing, and buy nothing back either, since a
        // trader only buys what they sell.
        assertThatThrownBy(() -> npcs("classpath:bad-npcs-shop-empty/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing in it");
    }

    @Test
    void refusesAShopkeeperWithNoShop() {
        assertThatThrownBy(() -> npcs("classpath:bad-npcs-shopless/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHOP");
    }

    @Test
    void refusesAStallOpenedBySomebodyWhoIsNotAShopkeeper() {
        // The generic pair of checks, extended to trading by one entry in a map
        // rather than by any new logic at all.
        assertThatThrownBy(() -> npcs("classpath:bad-npcs-shop-unlisted/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHOP");
    }

    // ---- prices ------------------------------------------------------

    @Test
    void aTraderBuysBackForLessThanTheySell() {
        // The only place money leaves this world. At the full price, buying and
        // selling the same thing is a free loop and loot is worth nothing.
        int value = 100;

        assertThat(Shop.buybackPrice(value)).isLessThan(value).isPositive();
    }

    @Test
    void evenTheCheapestThingIsWorthSomethingSecondHand() {
        assertThat(Shop.buybackPrice(1))
                .as("rounding a small price to nothing would make selling it a way of"
                        + " throwing things away, which is not what the button says")
                .isPositive();
    }

    @Test
    void everyShippedItemHasAPrice() {
        assertThat(new ItemDefLoader().loadAll().values())
                .allSatisfy(item -> assertThat(item.value()).isPositive());
    }

    // ---- what creatures carry ----------------------------------------

    @Test
    void creaturesCarryMoney() {
        Map<String, MobDef> mobs = new MobDefLoader().loadAll();

        assertThat(mobs.get("dzik").coins()).isNotEmpty();
        assertThat(mobs.get("wilk").coins())
                .as("the second currency has to come from somewhere, or it is a"
                        + " registry entry nobody will ever hold")
                .anyMatch(drop -> "kly".equals(drop.currencyId()));
    }

    @Test
    void refusesACreatureCarryingMoneyThatDoesNotExist() {
        assertThatThrownBy(() -> new MobDefLoader(
                "classpath:bad-mobs-coin-currency/*.json", ITEMS, MONEY).loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("muszelki");
    }

    @Test
    void refusesACoinDropWhoseRangeIsBackwards() {
        assertThatThrownBy(() -> new MobDefLoader(
                "classpath:bad-mobs-coin-backwards/*.json", ITEMS, MONEY).loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("backwards");
    }

    @Test
    void aCoinDropIsRolledWithinItsOwnRange() {
        CoinDrop drop = new CoinDrop("zloto", 3, 7, 1.0);

        assertThat(drop.roll(new java.util.Random(1))).isBetween(3, 7);
        assertThat(new CoinDrop("kly", 2, 2, 1.0).roll(new java.util.Random(1)))
                .as("a range of one is a fixed amount, not an empty range to divide by")
                .isEqualTo(2);
    }

    @Test
    void everyCurrencyACreatureCarriesCanBeSpentSomewhere() {
        // Content-wide, and the kind of thing only a test across the whole set
        // can see: money that drops and buys nothing is a number going up for
        // no reason.
        List<String> traded = new NpcDefLoader().loadAll().values().stream()
                .filter(npc -> npc.shop() != null)
                .map(npc -> npc.shop().currencyId())
                .toList();

        assertThat(new MobDefLoader().loadAll().values().stream()
                .flatMap(mob -> mob.coins().stream())
                .map(CoinDrop::currencyId)
                .distinct())
                .allSatisfy(dropped -> assertThat(traded).contains(dropped));
    }
}
