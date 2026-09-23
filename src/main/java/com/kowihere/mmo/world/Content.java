package com.kowihere.mmo.world;

import java.util.Map;

/**
 * Everything a running map reads from content files, in one parameter.
 *
 * <p>The alternative was a sixth and seventh argument to {@code MapRunner},
 * which already carries more than it should. Content is one thing conceptually
 * — the JSON the server was started with — so it travels as one thing.
 */
public record Content(Map<String, MobDef> mobs, Map<String, ItemDef> items) {

    public static final Content EMPTY = new Content(Map.of(), Map.of());

    /** Creatures with nothing to give. Most tests never look at an item. */
    public static Content ofMobs(Map<String, MobDef> mobs) {
        return new Content(mobs, Map.of());
    }
}
