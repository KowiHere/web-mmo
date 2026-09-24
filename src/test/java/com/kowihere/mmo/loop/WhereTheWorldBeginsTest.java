package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDefLoader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which map a character with nowhere else to be begins on.
 *
 * <p>It used to be whichever one the loader happened to iterate first, out of a
 * map whose order is unspecified - so the answer could differ between two runs
 * of the same build. With one map in the world that was invisible; with two it
 * is a coin toss deciding where everybody starts.
 *
 * <p>A world is built here by hand rather than by Spring, because the thing
 * under test is what happens to content that should stop the world coming up,
 * and the shipped content is by definition content that should not.
 */
class WhereTheWorldBeginsTest {

    private static WorldService worldOf(String mapsLocation) {
        return new WorldService(new MapDefLoader(new MobDefLoader(), mapsLocation),
                new MobDefLoader(), new ItemDefLoader(), new ClassDefLoader(),
                new ObjectMapper(), WorldPersistence.NONE);
    }

    @Test
    void aWorldWithNoBeginningDoesNotComeUp() {
        WorldService world = worldOf("classpath:bad-maps-no-starting/*.json");

        assertThatThrownBy(world::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("starting");
    }

    @Test
    void norDoesOneWithTwo() {
        WorldService world = worldOf("classpath:bad-maps-two-starting/*.json");

        assertThatThrownBy(world::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pierwsza")
                .hasMessageContaining("druga");
    }

    @Test
    void andNothingIsLeftRunningWhenItRefuses() {
        // The check comes before a single map thread starts. Finding this out
        // afterwards would mean stopping three of them again inside an
        // exception path that nothing ever exercises.
        WorldService world = worldOf("classpath:bad-maps-two-starting/*.json");
        int before = Thread.activeCount();

        assertThatThrownBy(world::start).isInstanceOf(IllegalStateException.class);

        assertThat(Thread.activeCount())
                .as("a refused world should leave no threads behind")
                .isLessThanOrEqualTo(before);
    }
}
