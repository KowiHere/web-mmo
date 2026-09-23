package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.NpcFunction;
import com.kowihere.mmo.world.NpcPlacement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards on the content the server actually ships, as opposed to the fixtures.
 *
 * <p>These are the checks the loader cannot make. It reads one file at a time
 * and has no idea where anything ends up standing; what follows is about the
 * shape of the world once it is all put together.
 */
class ShippedContentTest {

    private static final MapDef STARTER = new MapDefLoader().loadAll().get("starter");

    @Test
    void thereIsSomewhereToBePutBackTogether() {
        // Nothing regenerates and dying no longer heals, so a map with no healer
        // on it is a map a hurt character can never recover on.
        assertThat(healers()).isNotEmpty();
    }

    @Test
    void everyHealerStandsWhereNothingMayAttack() {
        // The one that would go wrong quietly. Waking up with a single point of
        // health and having to cross open ground to be mended is a death loop,
        // and it would look to a player like the game being unfair rather than
        // like an NPC standing four tiles too far to the left.
        for (NpcPlacement healer : healers()) {
            int distance = Math.max(Math.abs(healer.x() - STARTER.spawnX()),
                    Math.abs(healer.y() - STARTER.spawnY()));
            assertThat(distance)
                    .as("%s stands %d tiles from the spawn, outside the %d that are safe",
                            healer.npc().name(), distance, MobBehaviour.SAFE_RADIUS)
                    .isLessThanOrEqualTo(MobBehaviour.SAFE_RADIUS);
        }
    }

    private static java.util.List<NpcPlacement> healers() {
        return STARTER.npcs().stream()
                .filter(placement -> placement.npc().does(NpcFunction.HEALER))
                .toList();
    }
}
