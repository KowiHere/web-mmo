package com.kowihere.mmo.world;

import java.util.List;

/**
 * What one trader deals in, and in what money.
 *
 * <p>Prices are not here. An item's worth is a property of the item, so a trader
 * only says <em>which</em> items and <em>which</em> currency - and there is one
 * number to edit when something turns out to be too cheap, rather than one per
 * shop that sells it.
 *
 * @param currencyId the only money this trader will take or hand over. A purse
 *                   full of the wrong kind buys nothing here, which is the whole
 *                   reason money is plural
 * @param sells      what is on the shelf, by item id
 */
public record Shop(String currencyId, List<String> sells) {

    /**
     * What a trader hands back for something they sold.
     *
     * <p>A fraction, and this is the only place money leaves the world. At the
     * full price, buying and selling the same thing is a free loop and loot is
     * worth exactly nothing, because anything can be turned into anything else
     * at no cost.
     */
    public static final double BUYBACK = 0.4;

    public Shop {
        sells = List.copyOf(sells);
    }

    public boolean dealsIn(String itemId) {
        return sells.contains(itemId);
    }

    /** What this trader pays for one, rounded so nobody argues about halves. */
    public static int buybackPrice(int value) {
        return Math.max(1, (int) Math.round(value * BUYBACK));
    }
}
