package com.kowihere.mmo.world;

/**
 * How much of one currency a creature may leave behind.
 *
 * <p>Kept apart from {@link LootEntry} rather than folded into it. That one is
 * about things, and a thing either drops or it does not; money drops in an
 * amount, and a record that means two different shapes depending on which field
 * is filled in is the kind of economy nobody can read a month later.
 *
 * @param chance 0..1, rolled once per kill with the map's own randomness, so a
 *               test can settle the outcome instead of running a hundred fights
 */
public record CoinDrop(String currencyId, int min, int max, double chance) {

    /** How much this drop is worth this time, given the map's own randomness. */
    public int roll(java.util.Random random) {
        return min >= max ? min : min + random.nextInt(max - min + 1);
    }
}
