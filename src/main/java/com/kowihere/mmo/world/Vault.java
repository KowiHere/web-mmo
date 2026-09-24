package com.kowihere.mmo.world;

import java.util.List;

/**
 * What one storekeeper charges for room.
 *
 * <p>The first tab of either chest is free and is not on either list: what is
 * priced here is the second tab, the third, and so on, in order. A list rather
 * than a formula, because these are five numbers somebody will want to argue
 * about one at a time.
 *
 * @param currencyId      what the character's own tabs are paid for in
 * @param tabPrices       the price of each tab after the free one
 * @param accountCurrencyId what the account's shared tabs are paid for in,
 *                          which is deliberately something else
 * @param accountTabPrices the same list, for the shared chest
 */
public record Vault(String currencyId, List<Integer> tabPrices,
                    String accountCurrencyId, List<Integer> accountTabPrices) {

    /** One tab, the same size as a bag: full is a state that has to happen. */
    public static final int TAB = 20;

    /**
     * The free tab plus the five that can be bought.
     *
     * <p>Here rather than beside the chest itself, because it is content that
     * prices tabs and content that has to be told, while it is being read, that
     * it has priced one too many.
     */
    public static final int MAX_TABS = 6;

    public Vault {
        tabPrices = List.copyOf(tabPrices);
        accountTabPrices = List.copyOf(accountTabPrices);
    }

    /**
     * What it costs to open the tab after the ones already open.
     *
     * @param tabsOpen how many there are now
     * @return the price, or -1 when there is no tab left to sell
     */
    public int priceOfNextTab(int tabsOpen, boolean forAccount) {
        List<Integer> prices = forAccount ? accountTabPrices : tabPrices;
        int index = tabsOpen - 1;
        return index >= 0 && index < prices.size() ? prices.get(index) : -1;
    }
}
