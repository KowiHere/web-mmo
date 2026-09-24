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
 * @param skills  what has been learned, and how far
 * @param coins   what there is to spend, in however many currencies
 * @param deposit what is lying in this character's own chest
 * @param accountDeposit what is lying in the chest shared by every character on
 *                       the account. Held in memory like the rest of this, and
 *                       safe to hold that way only because one character from
 *                       an account is in the world at a time
 */
public record SavedCharacter(String nameKey, String name, String mapId, int x, int y, Direction dir,
                             int level, long xp, int hp, long wakesAt,
                             Attributes attributes, int unspentPoints, List<StoredItem> items,
                             String classId, int skillPoints,
                             List<StoredSkill> skills, List<StoredCoin> coins,
                             Deposit deposit, Deposit accountDeposit) {

    public SavedCharacter {
        items = items == null ? List.of() : List.copyOf(items);
        skills = skills == null ? List.of() : List.copyOf(skills);
        coins = coins == null ? List.of() : List.copyOf(coins);
        deposit = deposit == null ? Deposit.EMPTY : deposit;
        accountDeposit = accountDeposit == null ? Deposit.EMPTY : accountDeposit;
    }

    /** A brand new character: level one, unhurt, unpenalised, empty-handed and broke. */
    public static SavedCharacter fresh(String nameKey, String name, String mapId, int x, int y,
                                       Direction dir) {
        return fresh(nameKey, name, mapId, x, y, dir, null, Attributes.FRESH);
    }

    /**
     * A brand new character of a given class, which decides both what it starts
     * with and how it fights.
     */
    public static SavedCharacter fresh(String nameKey, String name, String mapId, int x, int y,
                                       Direction dir, String classId, Attributes attributes) {
        // One skill point at level one, unlike attributes. A brand new
        // character should have a first choice to make rather than a system it
        // cannot see until it levels.
        return new SavedCharacter(nameKey, name, mapId, x, y, dir, 1, 0L, -1, 0L,
                attributes, 0, List.of(), classId, 1, List.of(), List.of(),
                Deposit.EMPTY, Deposit.EMPTY);
    }
}
