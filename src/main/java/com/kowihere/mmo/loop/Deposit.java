package com.kowihere.mmo.loop;

import java.util.List;

/**
 * One whole storage chest as it crosses a thread boundary: how many tabs have
 * been paid for, what lies in them, and what money is kept there.
 *
 * <p>One record rather than three fields on {@link SavedCharacter}, because a
 * character has two of these - its own and its account's - and they are the
 * same thing in two places.
 *
 * @param tabs how many tabs are open; the first is always one of them
 */
public record Deposit(int tabs, List<StoredDeposit> items, List<StoredCoin> coins) {

    /** What a chest nobody has ever opened looks like. */
    public static final Deposit EMPTY = new Deposit(1, List.of(), List.of());

    public Deposit {
        items = items == null ? List.of() : List.copyOf(items);
        coins = coins == null ? List.of() : List.copyOf(coins);
        tabs = Math.max(1, tabs);
    }
}
