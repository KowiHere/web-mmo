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
 * @param vault    what it charges for room in a chest, or null when it keeps none
 */
public record NpcDef(String id, String name, NpcKind kind,
                     Set<NpcFunction> functions, Dialogue dialogue, Shop shop, Master master,
                     Vault vault, String look) {

    public NpcDef {
        functions = Set.copyOf(functions);
    }

    /** The shape every test with no opinion about how people are drawn uses. */
    public NpcDef(String id, String name, NpcKind kind, Set<NpcFunction> functions,
                  Dialogue dialogue, Shop shop, Master master, Vault vault) {
        this(id, name, kind, functions, dialogue, shop, master, vault, null);
    }

    public boolean does(NpcFunction function) {
        return functions.contains(function);
    }
}
