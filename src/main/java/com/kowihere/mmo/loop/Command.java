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
     * @param saved what the database knew about this name, or null for someone
     *              who has never played here. Loaded by the network layer so
     *              the map thread never waits on a query.
     */
    record Join(Client client, String name, String token, long since, SavedCharacter saved)
            implements Command {

        public Join(Client client, String name, String token, long since) {
            this(client, name, token, since, null);
        }
    }

    record Detach(Client client) implements Command {
    }

    record MoveTo(Client client, int x, int y) implements Command {
    }

    record Chat(Client client, String text) implements Command {
    }
}
