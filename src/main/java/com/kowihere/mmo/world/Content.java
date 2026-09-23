package com.kowihere.mmo.world;

import java.util.Map;

/**
 * Everything a running map reads from content files, in one parameter.
 *
 * <p>The alternative was a sixth and seventh argument to {@code MapRunner},
 * which already carries more than it should. Content is one thing conceptually
 * — the JSON the server was started with — so it travels as one thing.
 */
public record Content(Map<String, MobDef> mobs, Map<String, ItemDef> items,
                      Map<String, ClassDef> classes) {

    public Content(Map<String, MobDef> mobs, Map<String, ItemDef> items) {
        this(mobs, items, new ClassDefLoader().loadAll());
    }

    public static final Content EMPTY = new Content(Map.of(), Map.of());

    /** Creatures with nothing to give. Most tests never look at an item. */
    public static Content ofMobs(Map<String, MobDef> mobs) {
        return new Content(mobs, Map.of());
    }

    /**
     * The class a character falls back to when none was chosen, or when a
     * stored one has since been removed from the content.
     *
     * <p>Named rather than "the first one loaded": the loaders hand back
     * immutable maps whose iteration order is not specified, so "first" would
     * be an accident that could differ between two runs of the same build.
     */
    public ClassDef defaultClass() {
        ClassDef fallback = classes.get(ClassDefLoader.FALLBACK_ID);
        if (fallback == null) {
            throw new IllegalStateException("No class '" + ClassDefLoader.FALLBACK_ID
                    + "' to fall back on; content is missing the one class that must exist.");
        }
        return fallback;
    }

    /**
     * @param id a stored class, which may be null - the column allows it, and
     *           so does every character created before classes existed. Asked
     *           of a sorted map, a null key is an exception rather than a miss,
     *           so it is answered here rather than left to the map.
     */
    public ClassDef classOrDefault(String id) {
        ClassDef found = id == null ? null : classes.get(id);
        return found != null ? found : defaultClass();
    }
}
