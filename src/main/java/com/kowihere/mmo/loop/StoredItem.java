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
 * @param slot the slot it is worn in, or null when it is in the bag
 */
public record StoredItem(String id, String defId, ItemSlot slot) {
}
