package com.kowihere.mmo.loop;

/**
 * One item lying in a storage tab, as it is written down and read back.
 *
 * <p>The twin of {@link StoredItem}, and deliberately not the same record: an
 * item in a bag answers "is it being worn", an item in storage answers "which
 * tab is it in", and a single record answering both with a null in the other
 * field would be a shape that is half wrong wherever it is used.
 *
 * @param tab which tab it is in, counted from zero
 * @param remaining what is left in the bottle, or null for everything else
 */
public record StoredDeposit(String id, String defId, int tab, Integer remaining) {

    public StoredDeposit(String id, String defId, int tab) {
        this(id, defId, tab, null);
    }
}
