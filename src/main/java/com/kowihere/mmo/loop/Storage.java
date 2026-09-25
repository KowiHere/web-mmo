package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.Vault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A chest: the second place a character's things can be, and the only one that
 * stays behind when they walk away.
 *
 * <p>Sibling of {@link Inventory}, and simpler than it, because nothing here is
 * worn: a chest holds, it does not equip. Like everything else under
 * {@code loop} it is mutable, unsynchronised and touched by exactly one map
 * thread - including the account's chest, which is safe to hold this way only
 * because one character from an account is in the world at a time.
 *
 * <p>Decides nothing. Whether a tab may be bought, what it costs and whether
 * the character is standing in front of the right NPC are rules of the game and
 * live with the other rules, in the map runner.
 */
final class Storage {

    /** Both from {@link Vault}, where content can be checked against them. */
    static final int TAB = Vault.TAB;
    static final int MAX_TABS = Vault.MAX_TABS;

    /** One item and the tab it lies in. Order within a tab is order of arrival. */
    record Kept(ItemStack stack, int tab) {
    }

    private final List<Kept> kept = new ArrayList<>();
    private int tabs;

    Storage(int tabs) {
        this.tabs = clamp(tabs);
    }

    private static int clamp(int tabs) {
        return Math.min(MAX_TABS, Math.max(1, tabs));
    }

    int tabs() {
        return tabs;
    }

    boolean hasTab(int tab) {
        return tab >= 0 && tab < tabs;
    }

    int countIn(int tab) {
        int total = 0;
        for (Kept one : kept) {
            if (one.tab() == tab) {
                total++;
            }
        }
        return total;
    }

    boolean isFull(int tab) {
        return countIn(tab) >= TAB;
    }

    /** @return false when that tab is not open or has no room; the caller must say which */
    boolean put(ItemStack stack, int tab) {
        if (!hasTab(tab) || isFull(tab)) {
            return false;
        }
        kept.add(new Kept(stack, tab));
        return true;
    }

    /** @return the item, now out of the chest, or null when it was never in it */
    ItemStack take(String itemId) {
        for (int i = 0; i < kept.size(); i++) {
            if (kept.get(i).stack().id().equals(itemId)) {
                return kept.remove(i).stack();
            }
        }
        return null;
    }

    /** @return false when every tab this chest can ever have is already open */
    boolean openAnotherTab() {
        if (tabs >= MAX_TABS) {
            return false;
        }
        tabs++;
        return true;
    }

    List<Kept> contents() {
        return List.copyOf(kept);
    }

    /** Puts back what the database remembered, without applying any rules to it. */
    void restore(List<StoredDeposit> stored, Map<String, ItemDef> definitions) {
        for (StoredDeposit item : stored) {
            ItemDef def = definitions.get(item.defId());
            if (def == null) {
                // Content edited under a live database, exactly as in Inventory:
                // losing the item is bad, refusing to let somebody play is worse.
                continue;
            }
            // Tabs can shrink if this ever gets edited by hand. Something in a
            // tab nobody can see is something lost, so it comes back into the
            // first one rather than nowhere.
            int tab = hasTab(item.tab()) ? item.tab() : 0;
            ItemStack stack = item.remaining() == null
                    ? new ItemStack(item.id(), def)
                    : new ItemStack(item.id(), def, item.remaining());
            kept.add(new Kept(stack, tab));
        }
    }

    List<StoredDeposit> stored() {
        List<StoredDeposit> all = new ArrayList<>(kept.size());
        for (Kept one : kept) {
            all.add(new StoredDeposit(one.stack().id(), one.stack().def().id(), one.tab(),
                    one.stack().remaining() < 0 ? null : one.stack().remaining()));
        }
        return List.copyOf(all);
    }
}
