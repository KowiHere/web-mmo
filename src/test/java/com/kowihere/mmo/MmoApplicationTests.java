package com.kowihere.mmo;

import com.kowihere.mmo.loop.WorldService;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MmoApplicationTests {

    @Autowired
    private WorldService world;

    @Test
    void theWorldStartsWithTheContext() {
        assertThat(world.startingMap().mapId()).isEqualTo("starter");
    }

    @Test
    void theStartingMapIsTheOneContentDeclares() {
        // It used to be whichever map the loader iterated first, out of a map
        // whose iteration order is unspecified - so which map the game began on
        // could differ between two runs of the same build. Asserting the id
        // alone would have gone on passing, right up until a second map existed.
        MapDef declared = new MapDefLoader().loadAll().values().stream()
                .filter(MapDef::isStarting)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no map declares itself the starting one"));

        assertThat(world.startingMap().mapId()).isEqualTo(declared.id());
    }

    @Test
    void andExactlyOneMapClaimsIt() {
        assertThat(new MapDefLoader().loadAll().values())
                .filteredOn(MapDef::isStarting)
                .as("two starting maps is the same accident as none")
                .hasSize(1);
    }
}
