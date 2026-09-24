package com.kowihere.mmo.loop;

/**
 * How much of one currency a character has, as it is written down.
 *
 * <p>A row rather than a column, and that is the whole design: money is plural
 * here, so "how much gold" is a question about one row among several rather
 * than a field on the character.
 */
public record StoredCoin(String currencyId, int amount) {
}
