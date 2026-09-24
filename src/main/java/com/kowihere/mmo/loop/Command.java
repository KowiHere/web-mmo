package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.ItemSlot;

/**
 * The only way into a map's state. Network threads enqueue these; the map's own
 * thread is the sole consumer and the sole writer of everything they touch.
 * That single-writer rule is the whole concurrency design — there are no locks
 * on world state because nothing else can reach it.
 */
public sealed interface Command {

    Client client();

    /**
     * @param character the character this socket was already proven to own, at
     *                  the handshake. The map thread never has to ask who
     *                  someone is, and never waits on a query to find out.
     */
    record Join(Client client, long accountId, SavedCharacter character, long since)
            implements Command {
    }

    record Detach(Client client) implements Command {
    }

    record MoveTo(Client client, int x, int y) implements Command {
    }

    record Chat(Client client, String text) implements Command {
    }

    /** Walk to this actor and fight it. The server does the walking. */
    record Attack(Client client, int targetId) implements Command {
    }

    /** Try to leave the current fight. Resolved at the start of the next round. */
    record Flee(Client client) implements Command {
    }

    /** Put on something from the bag. The item is named by its own id, not a slot. */
    record Equip(Client client, String itemId) implements Command {
    }

    record Unequip(Client client, ItemSlot slot) implements Command {
    }

    /** Spend one earned point on an attribute. */
    record Spend(Client client, Attributes.Attribute attribute) implements Command {
    }

    /** Use a skill in the coming round. Resolved there, like fleeing. */
    record Use(Client client, String skillId) implements Command {
    }

    /** Put one earned point into a skill. */
    record Learn(Client client, String skillId) implements Command {
    }

    /** Start talking to somebody standing next to you. */
    record Talk(Client client, int npcId) implements Command {
    }

    /**
     * Answer the question just asked.
     *
     * @param option an index into the options last sent to this client, not a
     *               node id - which is the difference between a conversation
     *               and a list of nodes anybody may ask for by name.
     */
    record Choose(Client client, int option) implements Command {
    }

    /** Close the conversation. */
    record StopTalking(Client client) implements Command {
    }

    /** Buy one of these from the trader you are talking to. */
    record Buy(Client client, String itemDefId) implements Command {
    }

    /**
     * Sell this one.
     *
     * @param itemId the particular copy, not its definition - somebody with two
     *               swords is selling one of them, and which one is theirs to
     *               decide rather than the server's
     */
    record Sell(Client client, String itemId) implements Command {
    }
}
