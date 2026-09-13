package com.kowihere.mmo.loop;

/**
 * An immutable copy of what is worth keeping about one character.
 *
 * <p>This is what leaves the map thread. It is a copy on purpose: handing out
 * the live {@link Actor} would let another thread read fields while the tick is
 * writing them, which is exactly the single-writer rule the loop is built on.
 */
public record ActorSnapshot(String nameKey, String name, String mapId, int x, int y, String dir) {
}
