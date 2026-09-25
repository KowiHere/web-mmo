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

    /** A board that listens to nothing, for every test that has no parties in it. */
    PartyBoard NONE = (nameKey, name, level, hp, maxHp, mapId, mapName, x, y, online) -> { };
}
