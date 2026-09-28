package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which waking place is nearest, worked out from the passages.
 *
 * <p>The rule is the world author's: a character wakes at the nearest one,
 * counted in passages first. Two towns three and two maps off, and it is the
 * one two maps off - whatever else is true about either.
 *
 * <p>The towns are content; the choice is not. Writing "wakes in Miasto B" on
 * every map around Miasto B is the same fact a dozen times, and the dozenth
 * copy is the one nobody corrects when a nearer town is built.
 */
class RespawnsTest {

    /** Every fixture world here is peopled by the test creatures, not the shipped ones. */
    private static Map<String, MapDef> world(String where) {
        return new MapDefLoader(new MobDefLoader("classpath:test-mobs/*.json"),
                new NpcDefLoader(), where, Map.of()).loadAll();
    }

    private static final Map<String, MapDef> COUNTRY = world("classpath:test-maps-respawn/*.json");

    @Test
    void theNearerTownWinsEvenWhenTheOtherIsTheStartingOne() {
        // Rozstaje is three passages from Miasto A and two from Miasto B.
        Respawns respawns = Respawns.of(COUNTRY);

        List<Respawns.Way> ways = respawns.from("rozstaje");

        assertThat(ways).hasSize(1);
        assertThat(ways.get(0).point().mapId()).isEqualTo("miasto-b");
        assertThat(ways.get(0).passages()).isEqualTo(2);
    }

    @Test
    void dyingInTheTownItselfWakesYouInIt() {
        Respawns respawns = Respawns.of(COUNTRY);

        List<Respawns.Way> ways = respawns.from("miasto-b");

        assertThat(ways).hasSize(1);
        assertThat(ways.get(0).passages()).isZero();
        assertThat(ways.get(0).point()).isEqualTo(new RespawnPoint("miasto-b", 5, 5));
    }

    @Test
    void theRoadOutIsPartOfTheAnswer() {
        // Not just which town, but which door on this map leads to it: that is
        // what lets whoever has the body decide between two equally far towns
        // by how far the body is from each door.
        Respawns respawns = Respawns.of(COUNTRY);

        Respawns.Way way = respawns.from("bor-2").get(0);

        assertThat(way.point().mapId()).isEqualTo("miasto-a");
        assertThat(way.leaveByX()).isEqualTo(1);
        assertThat(way.leaveByY()).isEqualTo(7);
    }

    @Test
    void twoTownsEquallyFarOffAreBothOffered() {
        // One passage each, in opposite directions. Neither can be preferred
        // here - the tie belongs to the map with the body on it.
        Respawns respawns = Respawns.of(world("classpath:test-maps-respawn-tie/*.json"));

        List<Respawns.Way> ways = respawns.from("pole");

        assertThat(ways).hasSize(2);
        assertThat(ways).allMatch(way -> way.passages() == 1);
        assertThat(ways).extracting(way -> way.point().mapId())
                .containsExactlyInAnyOrder("kapliczka-zachod", "kapliczka-wschod");
        assertThat(ways).extracting(Respawns.Way::leaveByX)
                .as("and each says which way out of this map it is")
                .containsExactlyInAnyOrder(1, 10);
    }

    @Test
    void twoDoorsToTheSameTownAreTwoAnswers() {
        // The same town, equally far, through either end of the meadow. Keeping
        // only the first door found would decide from the map file which way
        // out a body takes - and the body may be lying at the other end.
        Respawns respawns = Respawns.of(world("classpath:test-maps-respawn-twodoors/*.json"));

        List<Respawns.Way> ways = respawns.from("laka");

        assertThat(ways).hasSize(2);
        assertThat(ways).allMatch(way -> way.point().mapId().equals("gaj") && way.passages() == 1);
        assertThat(ways).extracting(Respawns.Way::leaveByX).containsExactlyInAnyOrder(1, 10);
    }

    @Test
    void thereIsOneAnswerPerRoad() {
        // The gate reaches the village by two of its own doors, but from the
        // glade both of those roads begin at the same step. Two identical
        // answers would leave the map with the body picking between two of the
        // same thing, which is the shape a search that walks in circles takes.
        Respawns respawns = Respawns.of(world("classpath:test-maps-respawn-cycle/*.json"));

        List<Respawns.Way> ways = respawns.from("glusza");

        assertThat(ways).hasSize(1);
        assertThat(ways.get(0).passages()).isEqualTo(2);
    }

    @Test
    void aWorldWithNoTownsOffersNothing() {
        // Which is most test worlds, and the world this game had until now:
        // every map answers for itself, exactly as before.
        Respawns respawns = Respawns.of(world("classpath:test-maps-combat/*.json"));

        assertThat(respawns.from("arena-walki")).isEmpty();
        assertThat(Respawns.NONE.from("arena-walki")).isEmpty();
    }

    @Test
    void theShippedWorldWakesItsDeadOnThePolana() {
        Map<String, MapDef> shipped = new MapDefLoader().loadAll();

        Respawns respawns = Respawns.of(shipped);

        assertThat(respawns.from("jaskinia"))
                .as("two passages from the cave, and the only town in the world")
                .singleElement()
                .satisfies(way -> {
                    assertThat(way.point().mapId()).isEqualTo("starter");
                    assertThat(way.passages()).isEqualTo(2);
                });
        assertThat(shipped.get("las").declaredRespawn())
                .as("and no map says it by hand any more")
                .isNull();
    }
}
