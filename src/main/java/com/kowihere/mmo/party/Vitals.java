package com.kowihere.mmo.party;

/**
 * What one map thread last said about one character.
 *
 * <p>Immutable, and written by exactly one thread: the map that owns the
 * character. Everybody else only reads it, which is what makes the party panel
 * safe to build from outside any tick.
 *
 * @param at when this was published, so that a character the world has stopped
 *           talking about can be told from one standing still
 */
public record Vitals(String name, int level, int hp, int maxHp,
                     String mapId, String mapName, int x, int y,
                     boolean online, long at) {
}
