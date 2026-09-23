package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.ItemDef;

/**
 * One actual item in the world, as opposed to the definition it is a copy of.
 *
 * <p>The identifier is a UUID minted where the item is created, which is the map
 * thread. A database-generated key would mean waiting for an {@code INSERT} in
 * the middle of a tick to learn what somebody just picked up, and the loop is
 * not allowed to wait for anything.
 */
public record ItemStack(String id, ItemDef def) {

    public static ItemStack of(ItemDef def) {
        return new ItemStack(java.util.UUID.randomUUID().toString(), def);
    }
}
