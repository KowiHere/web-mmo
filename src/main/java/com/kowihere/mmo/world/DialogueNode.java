package com.kowihere.mmo.world;

import java.util.List;

/**
 * One thing an NPC says, and everything that may be said back.
 *
 * @param options never empty - the loader refuses a node with no way out,
 *                because a player who reaches it is stuck in a window that
 *                cannot be closed
 */
public record DialogueNode(String id, String text, List<DialogueOption> options) {

    public DialogueNode {
        options = List.copyOf(options);
    }

    /**
     * @param index what the client sent. It is an index into what was last sent
     *              to that client, never a node id: given ids, a client could
     *              jump straight to the node with the reward on it.
     * @return the option, or null when there is no such choice here
     */
    public DialogueOption option(int index) {
        return index < 0 || index >= options.size() ? null : options.get(index);
    }
}
