package com.kowihere.mmo.loop;

/**
 * Where a map thread says how one of its characters is doing.
 *
 * <p>The twin of {@link WorldPersistence} and {@link MapTransfers}: an
 * interface so that the loop never learns what Spring is, and a callback so
 * that a party - which is not world state and does not belong to any one map -
 * can be built outside every tick.
 *
 * <p>Every method here returns nothing and must never block. A tick that waited
 * on a party would be a tick waiting on somebody else's map.
 */
public interface PartyBoard {

    /**
     * How this character is, right now, as the map that owns it sees things.
     *
     * @param online false while the socket is gone but the character is still
     *               standing in the world
     */
    void publish(String nameKey, String name, int level, int hp, int maxHp,
                 String mapId, String mapName, int x, int y, boolean online);

    /**
     * Everybody sharing a party with this character, including it, or an empty
     * set when it is alone.
     *
     * <p>The one thing a tick needs to <em>read</em> rather than announce, so
     * that a kill can be paid out to a party the map knows nothing else about.
     * It must answer without waiting: the implementation keeps an immutable
     * snapshot that one thread replaces, which is the same bargain as
     * {@code publish} in the other direction - a reader that catches the
     * previous instant pays a kill to the party as it stood a moment ago.
     */
    java.util.Set<String> fellowsOf(String nameKey);

    /** A board that listens to nothing, for every test that has no parties in it. */
    PartyBoard NONE = new PartyBoard() {
        @Override
        public void publish(String nameKey, String name, int level, int hp, int maxHp,
                            String mapId, String mapName, int x, int y, boolean online) {
        }

        @Override
        public java.util.Set<String> fellowsOf(String nameKey) {
            return java.util.Set.of();
        }
    };
}
