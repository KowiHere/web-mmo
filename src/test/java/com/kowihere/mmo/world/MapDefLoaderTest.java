package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the shipped map data. A map is edited by hand, so the cheap mistakes -
 * a row one character short, a spawn point inside a wall, a hole in the border -
 * are exactly the ones worth catching before a player walks into them.
 */
class MapDefLoaderTest {

    private final Map<String, MapDef> maps = new MapDefLoader().loadAll();

    @Test
    void loadsTheStarterMap() {
        MapDef starter = maps.get("starter");

        assertThat(starter).isNotNull();
        assertThat(starter.width()).isEqualTo(30);
        assertThat(starter.height()).isEqualTo(20);
        assertThat(starter.collisionRows()).hasSize(20).allSatisfy(row -> assertThat(row).hasSize(30));
    }

    @Test
    void everySpawnPointIsSomewhereAPlayerCanStand() {
        assertThat(maps.values()).allSatisfy(map ->
                assertThat(map.walkable(map.spawnX(), map.spawnY()))
                        .as("spawn of map '%s'", map.id())
                        .isTrue());
    }

    @Test
    void theBorderIsSealedSoNobodyCanWalkOffTheEdge() {
        MapDef starter = maps.get("starter");

        for (int x = 0; x < starter.width(); x++) {
            assertThat(starter.isBlocked(x, 0)).as("top edge at x=%s", x).isTrue();
            assertThat(starter.isBlocked(x, starter.height() - 1)).as("bottom edge at x=%s", x).isTrue();
        }
        for (int y = 0; y < starter.height(); y++) {
            assertThat(starter.isBlocked(0, y)).as("left edge at y=%s", y).isTrue();
            assertThat(starter.isBlocked(starter.width() - 1, y)).as("right edge at y=%s", y).isTrue();
        }
    }

    @Test
    void treatsAnythingOutsideTheGridAsNotWalkable() {
        MapDef starter = maps.get("starter");

        assertThat(starter.walkable(-1, 5)).isFalse();
        assertThat(starter.walkable(5, -1)).isFalse();
        assertThat(starter.walkable(starter.width(), 5)).isFalse();
        assertThat(starter.walkable(5, starter.height())).isFalse();
    }
}
