package com.kowihere.mmo.world;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NPCs are hand-edited content, and a broken conversation is the one kind of
 * content failure nothing else in the game reports: a creature with the wrong
 * health is obviously wrong the first time somebody fights it, but an option
 * pointing at a node that does not exist is wrong only for the player who picks
 * it, and to them it looks like the game freezing.
 *
 * <p>So most of these are about refusing things.
 */
class NpcDefLoaderTest {

    private final Map<String, NpcDef> npcs = new NpcDefLoader("classpath:test-npcs/*.json").loadAll();

    @Test
    void readsSomebodyAndSomethingAlike() {
        assertThat(npcs.get("zielarka").kind()).isEqualTo(NpcKind.PERSON);
        assertThat(npcs.get("tablica").kind())
                .as("a noticeboard talks too, and that is the whole point of keeping"
                        + " what it is apart from what it does")
                .isEqualTo(NpcKind.OBJECT);
        assertThat(npcs.get("tablica").does(NpcFunction.DIALOGUE)).isTrue();
    }

    @Test
    void readsTheWholeTreeAndNotJustTheFirstThingSaid() {
        Dialogue dialogue = npcs.get("zielarka").dialogue();

        assertThat(dialogue.start().text()).isEqualTo("Witaj.");
        assertThat(dialogue.start().options()).hasSize(3);
        assertThat(dialogue.start().option(0).goTo()).isEqualTo("ziola");
        assertThat(dialogue.start().option(2).action()).isEqualTo(DialogueAction.END);
        assertThat(dialogue.node("ziola").option(0).goTo())
                .as("a branch has to lead back, or the conversation is a list")
                .isEqualTo("powitanie");
    }

    @Test
    void anOptionThatWasNeverOfferedIsNotAnOption() {
        DialogueNode start = npcs.get("zielarka").dialogue().start();

        assertThat(start.option(3)).isNull();
        assertThat(start.option(-1))
                .as("the index comes off the wire, so it may be anything at all")
                .isNull();
    }

    @Test
    void theShippedContentLoads() {
        // The template, loaded from where the server will actually look for it.
        assertThat(new NpcDefLoader().loadAll()).containsKey("zielarka");
    }

    @Test
    void refusesAKindThatDoesNotExist() {
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-kind/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ZJAWA");
    }

    @Test
    void refusesAFunctionThatDoesNotExist() {
        assertThatThrownBy(() ->
                new NpcDefLoader("classpath:bad-npcs-unknown-function/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GADANIE");
    }

    @Test
    void refusesAFunctionNothingCanHonourYet() {
        // A ferryman in a world with one map would open, offer nothing, and
        // look to every player like a bug in the client rather than content
        // that was written too early.
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-function/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jedna mapa");
    }

    @Test
    void refusesAnOptionLeadingNowhere() {
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-goto/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bb");
    }

    @Test
    void refusesANodeWithNoWayOut() {
        // A window with no button on it. The player would have to reload.
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-dead-end/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stuck");
    }

    @Test
    void refusesANodeNobodyCouldEverReach() {
        // Either a typo in somebody's "goto" or a branch whose way in was
        // deleted. Both are content written and never seen, and nothing else
        // would ever mention it.
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-unreachable/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skarb");
    }

    @Test
    void refusesAnOptionThatEndsTheTalkAndAlsoLeadsSomewhere() {
        // Every option has to say where the conversation goes. END is itself
        // somewhere to go, so it cannot also point at a node.
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-both/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("goes nowhere");
    }

    @Test
    void refusesAnOptionThatNeitherLeadsNorActs() {
        // A button that does nothing at all. This guard had no fixture of its
        // own until it was deleted and every test stayed green.
        assertThatThrownBy(() ->
                new NpcDefLoader("classpath:bad-npcs-mute-option/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lead somewhere or end");
    }

    @Test
    void refusesADeedThatLeavesTheConversationOnNothing() {
        // Healing and then having nowhere to go leaves the window open on a
        // node that was never sent. A deed happens; it is not a destination.
        assertThatThrownBy(() ->
                new NpcDefLoader("classpath:bad-npcs-deed-nowhere/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HEAL");
    }

    @Test
    void refusesAHealerWhoCannotBeAskedToHeal() {
        // The function is listed, no option anywhere does it, and the NPC would
        // stand there being a healer at nobody.
        assertThatThrownBy(() ->
                new NpcDefLoader("classpath:bad-npcs-healer-idle/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HEALER");
    }

    @Test
    void refusesHealingFromSomebodyWhoIsNotAHealer() {
        // The other direction, and the one that matters: the list of functions
        // is what everything else in the game will ask, so it may not lie.
        assertThatThrownBy(() ->
                new NpcDefLoader("classpath:bad-npcs-heal-unlisted/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HEALER");
    }

    @Test
    void oneNpcCanDoTwoThings() {
        // The whole point of keeping what an NPC is apart from what it does. If
        // this needed a new kind, or a HEALING_TALKER, the last milestone was
        // designed wrong.
        NpcDef herbalist = npcs.get("zielarka");

        assertThat(herbalist.does(NpcFunction.DIALOGUE)).isTrue();
        assertThat(herbalist.does(NpcFunction.HEALER)).isTrue();
        assertThat(herbalist.kind()).isEqualTo(NpcKind.PERSON);
    }

    @Test
    void anOptionCanBothActAndLeadSomewhere() {
        DialogueOption patchMeUp = npcs.get("zielarka").dialogue().start().option(1);

        assertThat(patchMeUp.deed()).isEqualTo(DialogueAction.HEAL);
        assertThat(patchMeUp.goTo())
                .as("a deed gets an answer; splitting that into two clicks would be"
                        + " an interface chore pretending to be a rule")
                .isEqualTo("po-leczeniu");
    }

    @Test
    void endingTheTalkIsNotADeed() {
        DialogueOption goodbye = npcs.get("zielarka").dialogue().start().option(2);

        assertThat(goodbye.action()).isEqualTo(DialogueAction.END);
        assertThat(goodbye.deed())
                .as("END is somewhere to go, so it must never arrive as something to do")
                .isNull();
    }

    @Test
    void refusesATalkerWithNothingToSay() {
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-silent/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIALOGUE");
    }

    @Test
    void refusesAConversationThatStartsNowhere() {
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-unheard/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("brak");
    }

    @Test
    void everyFunctionThatIsNotReadyExplainsItself() {
        // Otherwise the refusal above would be a stack trace at startup with
        // nothing in it for whoever wrote the JSON.
        for (NpcFunction function : NpcFunction.values()) {
            if (!function.isImplemented()) {
                assertThat(function.whyNotYet())
                        .as("%s is refused without saying why", function)
                        .isNotBlank();
            }
        }
    }
}
