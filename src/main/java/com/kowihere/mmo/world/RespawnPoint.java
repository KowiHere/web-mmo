package com.kowihere.mmo.world;

/**
 * Where a character killed on this map wakes up.
 *
 * <p>Written per map rather than worked out from a graph of maps. "The nearest
 * town" is a judgement about the world, and content is where judgements about
 * the world belong; deriving it from distances would mean the answer changing
 * because somebody moved a door.
 *
 * <p>A map with none wakes its dead on its own spawn, which is what every map
 * did before this existed.
 */
public record RespawnPoint(String mapId, int x, int y) {
}
