package com.kowihere.mmo.world;

/**
 * One thing a creature may leave behind, and how often.
 *
 * @param chance 0..1, rolled once per kill with the map's own randomness — so a
 *               test can decide the outcome instead of running the fight a
 *               hundred times to see it
 */
public record LootEntry(String itemId, double chance) {
}
