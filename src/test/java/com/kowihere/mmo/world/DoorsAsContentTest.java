package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Doors are hand-edited content, and every way of getting one wrong produces a
 * world that looks fine until somebody walks into it.
 *
 * <p>Most of these need <em>every</em> map at once: a door names a map, a tile
 * and sometimes a key, and each of those is only right or wrong in the company
 * of the others. That is why they are checked after all the files are read
 * rather than while each one is.
 */
class DoorsAsContentTest {

    private static final Map<String, ItemDef> ITEMS =
            new ItemDefLoader("classpath:test-items/*.json").loadAll();

    private static MapDefLoader maps(String location) {
        return new MapDefLoader(new MobDefLoader("classpath:test-mobs/*.json", ITEMS),
                new NpcDefLoader("classpath:test-npcs/*.json", ITEMS,
                        new CurrencyDefLoader("classpath:test-currencies/*.json").loadAll()),
                location, ITEMS);
    }

    @Test
    void theShippedMapsLeadToEachOther() {
        Map<String, MapDef> shipped = new MapDefLoader().loadAll();

        assertThat(shipped).containsKeys("starter", "las");
        assertThat(shipped.get("starter").doorAt(27, 9)).isNotNull();
        assertThat(shipped.get("starter").doorAt(27, 9).toMap()).isEqualTo("las");
        assertThat(shipped.get("las").doorAt(2, 9).toMap()).isEqualTo("starter");
    }

    @Test
    void theWoodSendsItsDeadHome() {
        // Nothing regenerates, so a map that wakes its dead on its own spawn is
        // a map you wake up on with one point of health and no healer.
        RespawnPoint wood = new MapDefLoader().loadAll().get("las").respawn();

        assertThat(wood.mapId()).isEqualTo("starter");
    }

    @Test
    void aMapThatNamesNoRespawnWakesItsOwnDead() {
        MapDef house = maps("classpath:test-maps-doors/*.json").loadAll().get("dom");

        assertThat(house.respawn().mapId()).isEqualTo("dom");
        assertThat(house.respawn().x()).isEqualTo(house.spawnX());
    }

    @Test
    void refusesADoorToAMapThatDoesNotExist() {
        assertThatThrownBy(() -> maps("classpath:bad-maps-door-nowhere/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("krainalagodnosci");
    }

    @Test
    void refusesADoorThatLandsOnAnotherDoor() {
        // Walking in would take the same step twice - through, and straight
        // back - and whoever tried it would be thrown between two maps until
        // they closed the tab. This one caught the first real pair of doors
        // this game ever had.
        assertThatThrownBy(() -> maps("classpath:bad-maps-door-onto-door/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bounce");
    }

    @Test
    void refusesADoorThatLandsInAWall() {
        assertThatThrownBy(() -> maps("classpath:bad-maps-door-wall/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stand on");
    }

    @Test
    void refusesADoorAskingForAKeyThatIsNotAnItem() {
        // It would refuse everybody for ever, and nothing at runtime would say
        // why: a locked door and a mistyped door look identical from outside.
        assertThatThrownBy(() -> maps("classpath:bad-maps-door-key/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("klucz-ktorego-nie-ma");
    }

    @Test
    void refusesADoorStandingOnTheTileEverybodyArrivesAt() {
        // Including somebody who has just woken up dead there, who would be
        // sent straight back out of the map they were sent to.
        assertThatThrownBy(() -> maps("classpath:bad-maps-door-on-spawn/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arrive at");
    }

    @Test
    void refusesAMapThatWakesItsDeadNowhere() {
        assertThatThrownBy(() -> maps("classpath:bad-maps-respawn/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("atlantyda");
    }

    @Test
    void aDoorSaysWhyItWillNotOpen() {
        Door gated = new Door(1, 1, "gdzies", 2, 2, "Gdzieś", 5, 0, null);

        assertThat(gated.refuse(4, true, null)).contains("5");
        assertThat(gated.refuse(5, true, null))
                .as("and says nothing at all when it will")
                .isNull();
    }

    @Test
    void refusesADoorNobodyCouldEverBeTheRightLevelFor() {
        // From level nine, until level three. On the page they are two sensible
        // numbers; together they are a door with no possible visitor, and it
        // would look to everybody who tried it like an ordinary locked door.
        assertThatThrownBy(() -> maps("classpath:bad-maps-door-impossible/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nobody is ever the right level");
    }
}
