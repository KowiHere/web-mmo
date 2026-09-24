package com.kowihere.mmo.world;

import java.util.Set;

/**
 * Somebody, or something, that stands on a map and is talked to rather than
 * fought. Shared and immutable: one definition serves every copy of it, exactly
 * as {@link MobDef} does.
 *
 * @param functions what it does, one or more; see {@link NpcFunction}
 * @param dialogue its conversation, or null when it does not talk
 * @param shop     what it deals in, or null when it does not trade
 * @param master   whose class it keeps, or null when it keeps none
 */
public record NpcDef(String id, String name, NpcKind kind,
                     Set<NpcFunction> functions, Dialogue dialogue, Shop shop, Master master) {

    public NpcDef {
        functions = Set.copyOf(functions);
    }

    public boolean does(NpcFunction function) {
        return functions.contains(function);
    }
}
