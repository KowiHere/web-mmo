package com.kowihere.mmo.world;

import java.util.Map;

/**
 * A whole conversation: the node it opens on, and every node it can reach.
 *
 * <p>Immutable and shared, like every other piece of content. Where a player has
 * got to is not here - that belongs to the player, and to the server.
 */
public record Dialogue(String startId, Map<String, DialogueNode> nodes) {

    public Dialogue {
        nodes = Map.copyOf(nodes);
    }

    public DialogueNode start() {
        return nodes.get(startId);
    }

    public DialogueNode node(String id) {
        return id == null ? null : nodes.get(id);
    }
}
