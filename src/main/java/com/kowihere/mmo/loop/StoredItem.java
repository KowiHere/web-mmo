package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.ItemSlot;

/**
 * One item as it is written down and read back.
 *
 * <p>Only three things need keeping: which copy this is, what it is a copy of,
 * and whether it is being worn. Its name, its bonuses and what it requires all
 * come from the definition, so storing them would be storing a second version of
 * the truth — and the two would disagree the first time an item is rebalanced.
 *
 * <p>Four things now, and the fourth is the exception that proves the rule:
 * how much is left in a bottle cannot come from the definition, because that is
 * the one thing two copies of it disagree about.
 *
 * @param slot the slot it is worn in, or null when it is in the bag
 * @param remaining what is left in the bottle, or null for everything else
 */
public record StoredItem(String id, String defId, ItemSlot slot, Integer remaining) {

    public StoredItem(String id, String defId, ItemSlot slot) {
        this(id, defId, slot, null);
    }
}
