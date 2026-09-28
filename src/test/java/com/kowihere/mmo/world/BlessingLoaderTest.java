package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Blessings are hand-edited content that nothing at runtime can check.
 *
 * <p>A misspelt line is the worst case in this whole project: the tooltip says
 * "+5 siły", the player pays for it, and the number is simply never added. So
 * the loader is strict to the point of pedantry, and every refusal here is a
 * bug that would otherwise be reported as "blessings feel weak".
 */
class BlessingLoaderTest {

    private final Map<String, BlessingDef> blessings =
            new BlessingLoader("classpath:test-blessings/*.json").loadAll();

    @Test
    void aBlessingIsSeveralLinesAtOnce() {
        BlessingDef valour = blessings.get("probne-mestwo");

        assertThat(valour.rarity()).isEqualTo(Rarity.HEROIC);
        assertThat(valour.durationMs()).isEqualTo(10 * 60_000L);
        assertThat(valour.of(BlessingStat.STRENGTH)).isEqualTo(10);
        assertThat(valour.of(BlessingStat.ARMOR))
                .as("and a line may take something away, which is what makes it a bargain")
                .isEqualTo(-2);
        assertThat(valour.costsSomething()).isTrue();
    }

    @Test
    void theShippedBlessingsAskForSomethingBack() {
        Map<String, BlessingDef> shipped = new BlessingLoader().loadAll();

        assertThat(shipped).isNotEmpty();
        assertThat(shipped.values())
                .as("a blessing with no price is a present, and this game does not hand those out")
                .allMatch(BlessingDef::costsSomething);
    }

    @Test
    void refusesALineThatIsNotAStatisticThisGameHas() {
        // The whole reason this loader exists. "sila" instead of "strength"
        // would be a blessing that reads perfectly and does nothing.
        assertThatThrownBy(() ->
                new BlessingLoader("classpath:bad-blessings-stat/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sila");
    }

    @Test
    void refusesOneThatChangesNothing() {
        assertThatThrownBy(() ->
                new BlessingLoader("classpath:bad-blessings-empty/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no \"lines\"");
    }

    @Test
    void refusesOneWhoseLinesAreAllZero() {
        assertThatThrownBy(() ->
                new BlessingLoader("classpath:bad-blessings-zero/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all zero");
    }

    @Test
    void refusesARarityNobodyHasHeardOf() {
        assertThatThrownBy(() ->
                new BlessingLoader("classpath:bad-blessings-rarity/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MITYCZNE");
    }

    @Test
    void refusesOneThatIsOverBeforeItBegins() {
        assertThatThrownBy(() ->
                new BlessingLoader("classpath:bad-blessings-time/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before it began");
    }

    @Test
    void linesComeOutInOneOrderWhoeverAsks() {
        // Two tooltips of the same blessing must read the same way round, or
        // players compare them and find a difference that is not there.
        BlessingDef sharp = blessings.get("probna-zwinnosc");

        assertThat(sharp.ordered().keySet())
                .containsExactly(BlessingStat.DODGE_POINTS, BlessingStat.SECOND_BLOW_POINTS,
                        BlessingStat.HEAL_PER_ROUND);
    }

    @Test
    void anElixirSaysWhichBlessingItPours() {
        Map<String, ItemDef> items = new ItemDefLoader("classpath:test-items/*.json").loadAll();
        ItemDef elixir = items.get("probny-eliksir");

        assertThat(elixir.isBlessing()).isTrue();
        assertThat(elixir.isDrinkable()).isTrue();
        assertThat(elixir.grants()).isEqualTo("probne-mestwo");
        assertThat(blessings).containsKey(elixir.grants());
    }
}
