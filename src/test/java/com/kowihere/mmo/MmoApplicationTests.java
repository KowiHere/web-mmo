package com.kowihere.mmo;

import com.kowihere.mmo.loop.WorldService;
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
        assertThat(world.defaultMap().mapId()).isEqualTo("starter");
    }
}
