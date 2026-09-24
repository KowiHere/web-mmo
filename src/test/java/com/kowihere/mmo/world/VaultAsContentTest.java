package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a storekeeper charges is five numbers somebody typed, and every way of
 * getting them wrong produces a keeper who opens, shows a button, and refuses
 * everybody for ever without saying why.
 */
class VaultAsContentTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();
    private static final Map<String, CurrencyDef> MONEY =
            new CurrencyDefLoader("classpath:test-currencies/*.json").loadAll();

    private static NpcDefLoader keepers(String location) {
        return new NpcDefLoader(location, ITEMS, MONEY);
    }

    @Test
    void aWellWrittenKeeperLoads() {
        NpcDef keeper = keepers("classpath:test-npcs-vault/*.json").loadAll().get("magazynier");

        assertThat(keeper.does(NpcFunction.STORAGE)).isTrue();
        assertThat(keeper.vault().tabPrices()).containsExactly(100, 300);
        assertThat(keeper.vault().accountCurrencyId())
                .as("the two chests are deliberately not paid for in the same money")
                .isNotEqualTo(keeper.vault().currencyId());
    }

    @Test
    void refusesRoomPricedInMoneyThatDoesNotExist() {
        assertThatThrownBy(() -> keepers("classpath:bad-npcs-vault-currency/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dukaty");
    }

    @Test
    void refusesPricesThatGoDown() {
        // Three hundred and then one hundred: the fourth tab cheaper than the
        // third. Nobody means it, and nobody notices until somebody buys.
        assertThatThrownBy(() -> keepers("classpath:bad-npcs-vault-order/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of order");
    }

    @Test
    void refusesMorePricesThanThereAreTabsToSell() {
        assertThatThrownBy(() -> keepers("classpath:bad-npcs-vault-too-many/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only ever have " + Vault.MAX_TABS);
    }

    @Test
    void refusesATabThatCostsNothing() {
        // A free tab is a tab everybody already has, and a button that takes
        // nothing and appears to do nothing.
        assertThatThrownBy(() -> keepers("classpath:bad-npcs-vault-free/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("costs nothing");
    }

    @Test
    void refusesAKeeperWhoDoesNotSayWhatRoomCosts() {
        assertThatThrownBy(() -> keepers("classpath:bad-npcs-vaultless/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vault");
    }

    @Test
    void refusesAVaultOnSomebodyWhoKeepsNoChest() {
        assertThatThrownBy(() -> keepers("classpath:bad-npcs-vault-unlisted/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not list STORAGE");
    }

    @Test
    void thePriceListRunsOutRatherThanRepeatingItself() {
        Vault vault = new Vault("zloto", List.of(100, 300), "kly", List.of(5));

        assertThat(vault.priceOfNextTab(1, false)).isEqualTo(100);
        assertThat(vault.priceOfNextTab(2, false)).isEqualTo(300);
        assertThat(vault.priceOfNextTab(3, false))
                .as("-1 is how the interface knows to stop offering")
                .isEqualTo(-1);
        assertThat(vault.priceOfNextTab(1, true)).isEqualTo(5);
    }

    @Test
    void theShippedKeeperCanActuallyBePaid() {
        NpcDef keeper = new NpcDefLoader().loadAll().get("magazynier");

        assertThat(keeper).isNotNull();
        assertThat(new CurrencyDefLoader().loadAll())
                .as("including in the premium money, which exists but nothing grants yet")
                .containsKey(keeper.vault().accountCurrencyId());
    }
}
