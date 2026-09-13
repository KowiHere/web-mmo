package com.kowihere.mmo.loop;

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
}
