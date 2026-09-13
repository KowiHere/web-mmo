package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Creature definitions are hand-edited content, so the loader's job is to turn
 * a typo into a server that will not start rather than a creature that quietly
 * never appears. Most of these tests are about refusing things.
 */
class MobDefLoaderTest {

    private final Map<String, MobDef> mobs = new MobDefLoader().loadAll();

    @Test
    void loadsTheShippedCreatures() {
        MobDef boar = mobs.get("dzik");

        assertThat(boar).isNotNull();
        assertThat(boar.name()).isEqualTo("Dzik");
        assertThat(boar.tier()).isEqualTo(MobTier.MOB);
        assertThat(boar.aggroRadius()).isPositive();
    }

    @Test
    void anEliteIsMarkedAsOne() {
        assertThat(mobs.get("wilczyca").tier()).isEqualTo(MobTier.ELITE);
    }

    @Test
    void everyShippedCreatureCanActuallyBeSpawned() {
        assertThat(mobs.values())
                .allSatisfy(mob -> assertThat(mob.tier().isSpawnable())
                        .as("tier of '%s'", mob.id())
                        .isTrue());
    }

    @Test
    void aTierThatNeedsInstancesIsRefusedRatherThanLoaded() {
        // HERO and COLOSSUS have nowhere to appear until instances exist.
        // Loading one would leave a creature defined, reachable by name, and
        // permanently absent from the world with nothing to explain why.
        assertThatThrownBy(() -> new MobDefLoader("classpath:bad-mobs-hero/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HERO")
                .hasMessageContaining("instances");
    }

    @Test
    void anUnknownTierIsRefused() {
        assertThatThrownBy(() -> new MobDefLoader("classpath:bad-mobs-tier/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POTWORNY");
    }

    @Test
    void aLeashShorterThanTheAggroRadiusIsRefused() {
        // Such a creature would notice a player, take one step, find itself past
        // its leash and turn back - jittering on the spot for ever.
        assertThatThrownBy(() -> new MobDefLoader("classpath:bad-mobs-leash/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("leashRadius");
    }
}
