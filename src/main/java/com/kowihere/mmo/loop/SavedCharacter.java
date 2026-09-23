package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.world.Direction;

import java.util.List;

/**
 * Where a returning character was last seen, and what it was carrying. Handed to
 * the world when a player arrives, so the map thread can place them without ever
 * reading a database.
 *
 * @param nameKey the case-folded name, which is also the character's identity -
 *                characters are never renamed, so it needs no surrogate key
 * @param items   everything owned, worn and carried alike; the slot on each one
 *                says which it was
 */
public record SavedCharacter(String nameKey, String name, String mapId, int x, int y, Direction dir,
                             int level, long xp, int hp, long weakenedUntil,
                             Attributes attributes, int unspentPoints, List<StoredItem> items) {

    public SavedCharacter {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** A brand new character: level one, unhurt, unpenalised, empty-handed. */
    public static SavedCharacter fresh(String nameKey, String name, String mapId, int x, int y,
                                       Direction dir) {
        return new SavedCharacter(nameKey, name, mapId, x, y, dir, 1, 0L, -1, 0L,
                Attributes.FRESH, 0, List.of());
    }
}
