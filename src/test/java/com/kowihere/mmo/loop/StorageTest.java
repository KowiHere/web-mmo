package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemDefLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The chest on its own: what it holds, and what it refuses to hold. */
class StorageTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();

    private static ItemStack sword(String id) {
        return new ItemStack(id, ITEMS.get("probny-miecz"));
    }

    @Test
    void aTabHoldsAsMuchAsABagAndNoMore() {
        Storage chest = new Storage(1);
        for (int i = 0; i < Storage.TAB; i++) {
            assertThat(chest.put(sword("miecz-" + i), 0)).isTrue();
        }

        assertThat(chest.isFull(0)).isTrue();
        assertThat(chest.put(sword("jeszcze-jeden"), 0)).isFalse();
    }

    @Test
    void aTabNobodyBoughtHoldsNothing() {
        assertThat(new Storage(1).put(sword("miecz"), 1)).isFalse();
    }

    @Test
    void tabsFillSeparately() {
        Storage chest = new Storage(2);
        for (int i = 0; i < Storage.TAB; i++) {
            chest.put(sword("miecz-" + i), 0);
        }

        assertThat(chest.isFull(1)).isFalse();
        assertThat(chest.put(sword("obok"), 1)).isTrue();
    }

    @Test
    void thereIsACeilingOnTabs() {
        Storage chest = new Storage(Storage.MAX_TABS);

        assertThat(chest.openAnotherTab()).isFalse();
        assertThat(chest.tabs()).isEqualTo(Storage.MAX_TABS);
    }

    @Test
    void takingSomethingOutTakesThatOneAndLeavesTheRest() {
        Storage chest = new Storage(1);
        chest.put(sword("pierwszy"), 0);
        chest.put(sword("drugi"), 0);

        assertThat(chest.take("pierwszy")).isNotNull();
        assertThat(chest.take("pierwszy")).as("and only once").isNull();
        assertThat(chest.contents()).hasSize(1);
    }

    @Test
    void whatWasLeftInATabThatNoLongerExistsComesBackRatherThanVanishing() {
        // Tabs can only shrink by hand, but something in a tab nobody can see
        // is something lost, and losing it silently is the worst of the two.
        Storage chest = new Storage(1);

        chest.restore(List.of(new StoredDeposit("miecz", "probny-miecz", 4)), ITEMS);

        assertThat(chest.contents()).hasSize(1);
        assertThat(chest.contents().get(0).tab()).isZero();
    }

    @Test
    void anItemWhoseDefinitionWasDeletedIsSkippedRatherThanRefused() {
        Storage chest = new Storage(1);

        chest.restore(List.of(new StoredDeposit("duch", "nie-ma-takiego", 0)), ITEMS);

        assertThat(chest.contents()).isEmpty();
    }
}
