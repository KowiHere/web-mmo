package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Items are hand-edited content, so the loader's job is to turn a typo into a
 * server that will not start rather than into an item somebody picks up and
 * cannot work out why it does nothing. Most of these are about refusing things.
 */
class ItemDefLoaderTest {

    private final Map<String, ItemDef> items = new ItemDefLoader().loadAll();

    @Test
    void loadsTheShippedItems() {
        ItemDef sword = items.get("zardzewialy-miecz");

        assertThat(sword).isNotNull();
        assertThat(sword.slot()).isEqualTo(ItemSlot.WEAPON);
        assertThat(sword.attack()).isEqualTo(3);
        assertThat(sword.requiresLevel()).isEqualTo(1);
    }

    @Test
    void anItemCanGrantAttributesRatherThanStatistics() {
        ItemDef ring = items.get("pierscien-sily");

        assertThat(ring.slot()).isEqualTo(ItemSlot.TRINKET);
        assertThat(ring.bonuses().strength()).isEqualTo(4);
        assertThat(ring.attack()).isZero();
    }

    @Test
    void somethingInTheSetActuallyRequiresALevel() {
        // A requirement nobody has ever tested is a requirement that does not
        // exist, so the shipped content has to contain at least one.
        assertThat(items.values())
                .as("no shipped item requires a level; the check is then untested content")
                .anyMatch(item -> item.requiresLevel() > 1);
    }

    @Test
    void refusesASlotThatDoesNotExist() {
        assertThatThrownBy(() -> new ItemDefLoader("classpath:bad-items-slot/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HEAD");
    }

    @Test
    void refusesAnItemWorthNothing() {
        // Every price in the game is a fraction or a multiple of this number.
        // An item left without one would be free to buy and free to sell, and
        // the only sign of it would be somebody's purse behaving oddly.
        assertThatThrownBy(() -> new ItemDefLoader("classpath:bad-items-free/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("value");
    }

    @Test
    void refusesAnItemThatGrantsNothing() {
        // The fixture says "sila" where it means "strength". Loading it would
        // produce a ring that is worn, looks right and does nothing at all.
        assertThatThrownBy(() -> new ItemDefLoader("classpath:bad-items-empty/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bonuses");
    }

    @Test
    void refusesTwoItemsWithTheSameId() {
        assertThatThrownBy(() -> new ItemDefLoader("classpath:bad-items-duplicate/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bliznjak");
    }

    @Test
    void refusesACreatureThatDropsSomethingThatIsNotAnItem() {
        // The reward would silently never be given, and the only symptom would
        // be players saying that creature "never drops anything".
        assertThatThrownBy(() ->
                new MobDefLoader("classpath:bad-mobs-loot/*.json", items).loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nie-ma-takiego-przedmiotu");
    }

    @Test
    void theShippedCreaturesDropOnlyThingsThatExist() {
        // The check above proves the loader can refuse; this proves the content
        // we actually ship passes it.
        Map<String, MobDef> mobs = new MobDefLoader().loadAll();

        assertThat(mobs.values())
                .allSatisfy(mob -> assertThat(mob.loot())
                        .allSatisfy(entry -> assertThat(items).containsKey(entry.itemId())));
    }
}
