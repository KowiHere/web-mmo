package com.kowihere.mmo.loop;

/**
 * How a map hands a character to another map.
 *
 * <p>The twin of {@link WorldPersistence}: a map runner knows nothing about the
 * world around it, and says what it wants done rather than doing it.
 *
 * <p>This is the only place two map threads meet, so it is the only place the
 * single-writer rule could be broken - and the reason it is not, is that
 * everything crossing the boundary is a {@link SavedCharacter}, which is
 * immutable, and a {@link Client}, which is a socket rather than world state.
 * No live actor is ever handed over, because a live actor is exactly what
 * another thread must not be able to read while this one is writing it.
 */
public interface MapTransfers {

    /**
     * Moves this socket's character to another map.
     *
     * <p>Called from the map thread that is giving the character up, and only
     * after it has removed its own copy. The character reappears through the
     * ordinary {@link Command.Join} path, so there is one way into a map and
     * not two.
     *
     * @param accountId who owns this character. Travels beside the record
     *                  rather than inside it, because owning a character is a
     *                  fact about an account and not about the character
     * @param character everything worth keeping, already carrying the map and
     *                  the tile it is going to
     */
    void move(Client client, String toMapId, long accountId, SavedCharacter character);

    /** A world with one map and no way out of it, for tests that need no more. */
    MapTransfers NOWHERE = (client, toMapId, accountId, character) -> {
    };
}
