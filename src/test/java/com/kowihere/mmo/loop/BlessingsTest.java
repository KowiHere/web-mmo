package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.BlessingDef;
import com.kowihere.mmo.world.BlessingLine;
import com.kowihere.mmo.world.BlessingStat;
import com.kowihere.mmo.world.Rarity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list itself, with the clock in hand.
 *
 * <p>Everything here is about the two ways one gets laid on: the one a player
 * chooses, which the cap may refuse, and the one dying lays on, which it may
 * not. Counted here rather than in a running map because a tick can be made to
 * pass without waiting one out.
 */
class BlessingsTest {

    private static BlessingDef lasting(String id, int minutes) {
        return new BlessingDef(id, id, Rarity.COMMON, minutes, 1,
                Map.of(BlessingStat.STRENGTH, BlessingLine.flat(1)));
    }

    private static final BlessingDef WEAKNESS = new BlessingDef(
            "oslabienie-po-smierci", "Osłabienie po śmierci", Rarity.COMMON, 5, 1,
            Map.of(BlessingStat.STRENGTH, BlessingLine.percent(-10)));

    @Test
    void theSameOneAgainSetsTheClockBackRatherThanAddingASecond() {
        Blessings blessings = new Blessings();
        blessings.lay(lasting("probne", 5));
        blessings.tick(60_000);

        blessings.lay(lasting("probne", 5));

        assertThat(blessings.size()).isEqualTo(1);
        assertThat(blessings.stored().get(0).remainingMs()).isEqualTo(5 * 60_000L);
    }

    @Test
    void aFourthChosenOneIsRefused() {
        Blessings blessings = new Blessings();
        blessings.lay(lasting("a", 5));
        blessings.lay(lasting("b", 5));
        blessings.lay(lasting("c", 5));

        assertThat(blessings.lay(lasting("d", 5))).isFalse();
        assertThat(blessings.size()).isEqualTo(Blessings.MAX_ACTIVE);
    }

    @Test
    void thePenaltyForDyingIsLaidOverTheCap() {
        // Three bottles must not be a way of not being punished.
        Blessings blessings = new Blessings();
        blessings.lay(lasting("a", 5));
        blessings.lay(lasting("b", 5));
        blessings.lay(lasting("c", 5));

        blessings.layAnyway(WEAKNESS);

        assertThat(blessings.has(WEAKNESS.id())).isTrue();
        assertThat(blessings.size()).isEqualTo(Blessings.MAX_ACTIVE + 1);
    }

    @Test
    void asecondDeathSetsThatClockBackToo() {
        Blessings blessings = new Blessings();
        blessings.layAnyway(WEAKNESS);
        blessings.tick(120_000);
        assertThat(blessings.stored().get(0).remainingMs()).isEqualTo(3 * 60_000L);

        blessings.layAnyway(WEAKNESS);

        assertThat(blessings.size()).as("refreshed, not stacked").isEqualTo(1);
        assertThat(blessings.stored().get(0).remainingMs()).isEqualTo(5 * 60_000L);
    }

    @Test
    void whatWasWrittenDownComesBackEvenWhenItIsOneOverTheCap() {
        // The cap is a rule about laying one on, not about remembering it -
        // and the one that can push a character over it is the penalty for
        // dying, which is exactly the one nobody may lose by logging out.
        Blessings blessings = new Blessings();
        Map<String, BlessingDef> content = Map.of(
                "a", lasting("a", 5), "b", lasting("b", 5), "c", lasting("c", 5),
                WEAKNESS.id(), WEAKNESS);

        blessings.restore(List.of(
                new StoredBlessing("a", 60_000), new StoredBlessing("b", 60_000),
                new StoredBlessing("c", 60_000), new StoredBlessing(WEAKNESS.id(), 60_000)),
                content);

        assertThat(blessings.has(WEAKNESS.id())).isTrue();
        assertThat(blessings.size()).isEqualTo(4);
    }

    @Test
    void itRunsOutAndSaysSo() {
        Blessings blessings = new Blessings();
        blessings.lay(lasting("probne", 1));

        assertThat(blessings.tick(59_000)).isEmpty();
        assertThat(blessings.tick(2_000)).extracting(BlessingDef::id).containsExactly("probne");
        assertThat(blessings.size()).isZero();
    }
}
