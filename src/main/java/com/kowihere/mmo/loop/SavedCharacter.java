package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.Direction;

/**
 * Where a returning character was last seen. Handed to the world when a player
 * arrives, so the map thread can place them without ever reading a database.
 *
 * @param nameKey the case-folded name, which is also the character's identity -
 *                characters are never renamed, so it needs no surrogate key
 */
public record SavedCharacter(String nameKey, String name, String mapId, int x, int y, Direction dir) {
}
