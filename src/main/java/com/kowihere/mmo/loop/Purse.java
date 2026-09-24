package com.kowihere.mmo.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one character has to spend, in however many currencies.
 *
 * <p>The third of its kind beside {@link Inventory} and {@link Skills}: mutable,
 * unsynchronised, touched only by the map thread that owns the character, and
 * deciding nothing. Whether a price is fair, whether a trader deals in this
 * money at all, and whether the character is close enough to hand it over are
 * rules of the game and live with the other rules.
 */
final class Purse {

    private final Map<String, Integer> amounts = new LinkedHashMap<>();

    int amountOf(String currencyId) {
        return amounts.getOrDefault(currencyId, 0);
    }

    boolean canAfford(String currencyId, int price) {
        return price >= 0 && amountOf(currencyId) >= price;
    }

    void add(String currencyId, int amount) {
        if (amount <= 0) {
            return;
        }
        amounts.merge(currencyId, amount, Integer::sum);
    }

    /**
     * @return false when there was not enough, having changed nothing. Half a
     *         payment is worse than a refusal, and the caller has to be able to
     *         tell the player which it was.
     */
    boolean take(String currencyId, int price) {
        if (!canAfford(currencyId, price)) {
            return false;
        }
        amounts.merge(currencyId, -price, Integer::sum);
        return true;
    }

    /** Puts back what the database remembered, without applying any rules to it. */
    void restore(List<StoredCoin> stored, Map<String, ?> definitions) {
        for (StoredCoin coin : stored) {
            if (!definitions.containsKey(coin.currencyId())) {
                // Content was edited under a live database. Dropping money
                // nobody can spend anywhere is better than refusing to let
                // somebody play.
                continue;
            }
            amounts.put(coin.currencyId(), Math.max(0, coin.amount()));
        }
    }

    List<StoredCoin> stored() {
        List<StoredCoin> all = new ArrayList<>(amounts.size());
        for (Map.Entry<String, Integer> entry : amounts.entrySet()) {
            if (entry.getValue() > 0) {
                all.add(new StoredCoin(entry.getKey(), entry.getValue()));
            }
        }
        return List.copyOf(all);
    }
}
