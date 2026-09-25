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
public final class ItemStack {

    private final String id;
    private final ItemDef def;

    /**
     * How much is left in this bottle, or -1 for everything that is not one.
     *
     * <p>The first thing in this game that differs between two copies of one
     * definition: two rusty swords are the same sword, but a full bottle and an
     * almost-empty one are not the same bottle. Mutable for the same reason
     * everything else under {@code loop} is - one map thread owns it, and
     * nobody else ever looks.
     */
    private int remaining;

    public ItemStack(String id, ItemDef def) {
        this(id, def, def.isDrinkable() && def.healing().hasPool() ? def.healing().pool() : -1);
    }

    public ItemStack(String id, ItemDef def, int remaining) {
        this.id = id;
        this.def = def;
        this.remaining = remaining;
    }

    /** A brand new one, which for a bottle means a full one. */
    public static ItemStack of(ItemDef def) {
        return new ItemStack(java.util.UUID.randomUUID().toString(), def);
    }

    public String id() {
        return id;
    }

    public ItemDef def() {
        return def;
    }

    /** What is left in the bottle, or -1 when this is not one. */
    public int remaining() {
        return remaining;
    }

    /**
     * Takes out of the bottle what actually reached somebody.
     *
     * @return true when there is nothing left and the bottle should go
     */
    boolean drain(int healed) {
        if (remaining < 0) {
            return true; // no pool: one mouthful and gone
        }
        remaining -= healed;
        return remaining <= 0;
    }
}
