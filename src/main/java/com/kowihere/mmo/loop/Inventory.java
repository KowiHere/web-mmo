package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemSlot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What one character is carrying and what it is wearing.
 *
 * <p>Like everything else under {@code loop}, this is mutable and completely
 * unsynchronised: only the owning map thread ever touches one.
 *
 * <p>Nothing here decides whether a character is <em>allowed</em> to wear
 * something — a level requirement is a rule of the game and lives with the other
 * rules, in the map runner. This class only keeps track.
 */
final class Inventory {

    /** Bag size. Small enough to make "full" a case that actually happens. */
    static final int CAPACITY = 20;

    private final List<ItemStack> bag = new ArrayList<>();
    private final Map<ItemSlot, ItemStack> worn = new EnumMap<>(ItemSlot.class);

    boolean isFull() {
        return bag.size() >= CAPACITY;
    }

    /** @return false when there was nowhere to put it; the caller must say so */
    boolean add(ItemStack stack) {
        if (isFull()) {
            return false;
        }
        bag.add(stack);
        return true;
    }

    ItemStack inBag(String itemId) {
        for (ItemStack stack : bag) {
            if (stack.id().equals(itemId)) {
                return stack;
            }
        }
        return null;
    }

    ItemStack wornIn(ItemSlot slot) {
        return worn.get(slot);
    }

    /**
     * Moves an item from the bag onto the character.
     *
     * <p>Whatever was in the slot goes back to the bag — which cannot fail,
     * because the item being put on has just left a space behind it.
     *
     * @return what came off, or null if the slot was empty
     */
    ItemStack wear(ItemStack stack) {
        bag.remove(stack);
        ItemStack removed = worn.put(stack.def().slot(), stack);
        if (removed != null) {
            bag.add(removed);
        }
        return removed;
    }

    /** @return what came off, or null when the slot was empty or the bag full */
    ItemStack takeOff(ItemSlot slot) {
        ItemStack stack = worn.get(slot);
        if (stack == null || isFull()) {
            return null;
        }
        worn.remove(slot);
        bag.add(stack);
        return stack;
    }

    /**
     * Takes one thing out of the bag for good.
     *
     * <p>Deliberately only the bag. Something being worn is not something a
     * character can hand over without taking it off first, and quietly
     * unequipping it on their behalf is how somebody sells the sword they were
     * holding and does not notice until the next fight.
     *
     * @return true when it was there and is now gone
     */
    boolean removeFromBag(String itemId) {
        return bag.removeIf(stack -> stack.id().equals(itemId));
    }

    /** Attributes granted by everything currently worn. */
    Attributes grantedAttributes() {
        Attributes total = new Attributes(0, 0, 0);
        for (ItemStack stack : worn.values()) {
            total = total.plus(stack.def().bonuses());
        }
        return total;
    }

    int grantedAttack() {
        int total = 0;
        for (ItemStack stack : worn.values()) {
            total += stack.def().attack();
        }
        return total;
    }

    int grantedArmor() {
        int total = 0;
        for (ItemStack stack : worn.values()) {
            total += stack.def().armor();
        }
        return total;
    }

    List<ItemStack> bag() {
        return List.copyOf(bag);
    }

    Map<ItemSlot, ItemStack> worn() {
        return Map.copyOf(worn);
    }

    /** Puts back what the database remembered, without applying any rules to it. */
    void restore(List<StoredItem> stored, Map<String, ItemDef> definitions) {
        for (StoredItem item : stored) {
            ItemDef def = definitions.get(item.defId());
            if (def == null) {
                // Content was edited or rolled back under a live database. Losing
                // the item is bad; refusing to let someone play is worse.
                continue;
            }
            ItemStack stack = item.remaining() == null
                    ? new ItemStack(item.id(), def)
                    : new ItemStack(item.id(), def, item.remaining());
            if (item.slot() != null && def.slot() == item.slot() && !worn.containsKey(item.slot())) {
                worn.put(item.slot(), stack);
            } else {
                add(stack);
            }
        }
    }

    private static StoredItem written(ItemStack stack, ItemSlot slot) {
        return new StoredItem(stack.id(), stack.def().id(), slot,
                stack.remaining() < 0 ? null : stack.remaining());
    }

    /** Everything, flattened into the shape that gets written down. */
    List<StoredItem> stored() {
        List<StoredItem> all = new ArrayList<>(bag.size() + worn.size());
        for (Map.Entry<ItemSlot, ItemStack> entry : worn.entrySet()) {
            all.add(written(entry.getValue(), entry.getKey()));
        }
        for (ItemStack stack : bag) {
            all.add(written(stack, null));
        }
        return List.copyOf(all);
    }
}
