package com.kowihere.mmo.party;

/**
 * One seat in a party: who it belongs to, and nothing else.
 *
 * <p>Deliberately not the character. A party is a list of names; how much
 * health each of them has is published separately by whichever map they happen
 * to be standing on, and joining a party has nothing to do with where anybody
 * is.
 */
public record Member(String nameKey, String name) {
}
