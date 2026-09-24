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
 */
public record CurrencyDef(String id, String name, String shortName, int order) {
}
