package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ground a map is drawn on, as content.
 *
 * <p>A picture drawn in characters, so every mistake here is a map that looks
 * wrong rather than a map that fails - and a map that looks wrong gets reported
 * as a bug in the drawing, which is the hardest kind of report to act on.
 */
class GroundAsContentTest {

    private static final Map<String, TerrainDef> TEST_TERRAIN =
            new TerrainDefLoader("classpath:test-terrain/*.json").loadAll();

    private static MapDefLoader maps(String where) {
        return new MapDefLoader(new MobDefLoader("classpath:test-mobs/*.json"),
                new NpcDefLoader(), where, Map.of(), TEST_TERRAIN);
    }

    @Test
    void theShippedWorldIsDrawnOnSomething() {
        MapDef starter = new MapDefLoader().loadAll().get("starter");

        assertThat(starter.ground()).hasSize(starter.height());
        assertThat(starter.ground().get(0)).hasSize(starter.width());
        assertThat(starter.groundLegend())
                .as("and the client is told which character means what, so it holds no second copy")
                .containsEntry("t", "trawa")
                .containsEntry("s", "sciezka");
    }

    @Test
    void thereIsARoadFromTheSpawnToTheDoor() {
        // Not decoration: the road is where walking speed will hang when tiles
        // start deciding it, so it has to be a real kind of ground now.
        MapDef starter = new MapDefLoader().loadAll().get("starter");

        assertThat(starter.ground().get(starter.spawnY()).chars()
                .filter(mark -> mark == 's').count())
                .as("the spawn's own row should be mostly road")
                .isGreaterThan(5);
    }

    @Test
    void aMapThatSaysNothingAboutItsGroundIsStillAMap() {
        // The pin that keeps this milestone from forcing every map to be
        // repainted - and every test fixture with it.
        MapDef plain = maps("classpath:test-maps-combat/*.json").loadAll().get("arena-walki");

        assertThat(plain.ground()).isEmpty();
        assertThat(plain.groundLegend()).isEmpty();
    }

    @Test
    void groundIsReadAndKeptAsWritten() {
        MapDef meadow = maps("classpath:test-maps-ground/*.json").loadAll().get("polanka");

        assertThat(meadow.ground().get(1)).isEqualTo("wttttttttw");
    }

    @Test
    void refusesFewerRowsOfGroundThanOfCollision() {
        assertThatThrownBy(() -> maps("classpath:bad-maps-ground-rows/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 rows of ground");
    }

    @Test
    void refusesOneRowBeingShortOfATile() {
        // The mistake worth a test of its own: counting rows would let this
        // through, and the map would slide sideways partway down.
        assertThatThrownBy(() -> maps("classpath:bad-maps-ground-width/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in row 1");
    }

    @Test
    void refusesAKindOfGroundThisGameDoesNotHave() {
        assertThatThrownBy(() -> maps("classpath:bad-maps-ground-symbol/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'Q' at 5,1");
    }

    // ---- the registry itself ---------------------------------------------

    @Test
    void refusesAKindOfGroundThatIsNotOneCharacter() {
        assertThatThrownBy(() ->
                new TerrainDefLoader("classpath:bad-terrain-symbol/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("one character per tile");
    }

    @Test
    void refusesBorrowingTheCollisionGridsOwnCharacters() {
        // A ground grid full of '#' reads as a collision grid at a glance,
        // which is the one mistake nobody questions while reading a file.
        assertThatThrownBy(() ->
                new TerrainDefLoader("classpath:bad-terrain-collision-char/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("collision grid already uses");
    }

    @Test
    void refusesTwoKindsOfGroundBehindOneCharacter() {
        assertThatThrownBy(() ->
                new TerrainDefLoader("classpath:bad-terrain-duplicate-symbol/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both answer to 'q'");
    }
}
