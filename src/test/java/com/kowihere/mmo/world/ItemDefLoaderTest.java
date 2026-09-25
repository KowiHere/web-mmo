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
    void somethingCanBeCarriedRatherThanWorn() {
        // A torch burnt to get into a cave, and before long a potion. It grants
        // nothing while it sits in the bag, which is the whole point of saying
        // so in the file rather than leaving it to look like a mistake.
        ItemDef torch = items.get("pochodnia");

        assertThat(torch.slot()).isEqualTo(ItemSlot.NONE);
        assertThat(torch.slot().isWorn()).isFalse();
        assertThat(torch.value()).isPositive();
    }

    @Test
    void aBottleSaysHowMuchItGivesBackAndHowMuchIsInIt() {
        ItemDef flask = items.get("flakon-50");

        assertThat(flask.isDrinkable()).isTrue();
        assertThat(flask.healing().percent()).isEqualTo(50);
        assertThat(flask.healing().hasPool()).isTrue();
        assertThat(items.get("mikstura-mala").healing().hasPool())
                .as("and a one-mouthful potion says it has no pool at all")
                .isFalse();
    }

    @Test
    void refusesABrewThatHealsNothing() {
        assertThatThrownBy(() ->
                new ItemDefLoader("classpath:bad-items-brew-empty/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heals nothing");
    }

    @Test
    void refusesABrewThatHealsBothWays() {
        // Which of the two applies would be decided by whichever line the code
        // reads first, and the file would look as though it said both.
        assertThatThrownBy(() ->
                new ItemDefLoader("classpath:bad-items-brew-both/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can only be one");
    }

    @Test
    void refusesMoreThanAllOfSomebodysHealth() {
        assertThatThrownBy(() ->
                new ItemDefLoader("classpath:bad-items-brew-percent/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than all of it");
    }

    @Test
    void refusesSomethingWornThatIsAlsoDrunk() {
        // Two verbs. An item claiming both would be worn for its bonuses and
        // never drunk - or drunk off somebody's body in the middle of a fight.
        assertThatThrownBy(() ->
                new ItemDefLoader("classpath:bad-items-brew-worn/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one or the other");
    }

    @Test
    void oneMouthfulIsNeverMoreThanIsMissing() {
        Healing flask = items.get("flakon-100").healing();

        assertThat(flask.sip(200, 30, 1_000))
                .as("treating a scratch costs the scratch, not the bottle")
                .isEqualTo(30);
        assertThat(flask.sip(200, 500, 1_000))
                .as("and a mouthful is capped by what it is worth")
                .isEqualTo(200);
        assertThat(flask.sip(200, 500, 45))
                .as("and by what is left in the bottle")
                .isEqualTo(45);
    }

    @Test
    void refusesBonusesOnSomethingNobodyCanPutOn() {
        // They would sit in the file looking as though somebody will one day
        // have them, and nobody ever could.
        assertThatThrownBy(() -> new ItemDefLoader("classpath:bad-items-carried/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could never reach anybody");
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
