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
        assertThat(dialogue.start().options()).hasSize(2);
        assertThat(dialogue.start().option(0).goTo()).isEqualTo("ziola");
        assertThat(dialogue.start().option(1).action()).isEqualTo(DialogueAction.END);
        assertThat(dialogue.node("ziola").option(0).goTo())
                .as("a branch has to lead back, or the conversation is a list")
                .isEqualTo("powitanie");
    }

    @Test
    void anOptionThatWasNeverOfferedIsNotAnOption() {
        DialogueNode start = npcs.get("zielarka").dialogue().start();

        assertThat(start.option(2)).isNull();
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
        // A shopkeeper in a world with no money would open, offer nothing, and
        // look to every player like a bug in the client rather than content
        // that was written too early.
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-function/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("waluty");
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
    void refusesAnOptionThatBothLeadsAndActs() {
        assertThatThrownBy(() -> new NpcDefLoader("classpath:bad-npcs-both/*.json").loadAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
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
