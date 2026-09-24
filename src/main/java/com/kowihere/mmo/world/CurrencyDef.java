package com.kowihere.mmo.world;

/**
 * One kind of money. Content, like everything else.
 *
 * <p>There is more than one on purpose. A single column called {@code gold}
 * would have to be undone the day a trader deals in something else, and traders
 * dealing in something else are planned - so the registry is plural from the
 * first day it exists, and so is the table behind it.
 *
 * @param shortName what fits beside a number in the interface
 * @param order     where it sits in that list. In content rather than derived,
 *                  because sorting by id would put the odd currency ahead of
 *                  the ordinary one and call that a decision.
 * @param primary   the money the game itself charges in when no trader is
 *                  involved - spending a skill point from the character panel,
 *                  with nobody to ask which coin they want. Exactly one carries
 *                  it, and the loader refuses any other number: a game with no
 *                  primary currency cannot price anything, and a game with two
 *                  prices the same thing twice.
 *
 *                  <p>A flag rather than "the first by order", which would be
 *                  the same field meaning two things - and somebody reordering
 *                  the purse for looks would silently change what the game
 *                  charges in.
 */
public record CurrencyDef(String id, String name, String shortName, int order, boolean primary) {
}
