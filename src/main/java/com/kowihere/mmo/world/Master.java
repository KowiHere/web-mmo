package com.kowihere.mmo.world;

/**
 * Whose class this NPC keeps.
 *
 * <p>A master is not a shop: nothing is bought here that could not be had from
 * the character panel. What they offer is the same thing for less, and the only
 * way to take a decision back.
 *
 * <p>No currency of their own, deliberately. Spending a point has one price and
 * two places to pay it; a master charging in something else would make the same
 * act two different things depending on where it happened.
 */
public record Master(String classId) {
}
