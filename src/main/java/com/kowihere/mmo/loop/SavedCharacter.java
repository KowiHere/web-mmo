package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.Direction;

/**
 * Where a returning character was last seen. Handed to the world when a player
 * arrives, so the map thread can place them without ever reading a database.
 */
public record SavedCharacter(String name, String mapId, int x, int y, Direction dir) {
}
