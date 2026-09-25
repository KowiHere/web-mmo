package com.kowihere.mmo.world;

/**
 * What one mouthful of something gives back.
 *
 * <p>Two shapes, never both: a flat number of health, or a share of whatever
 * this particular character's maximum is. The second is what makes one bottle
 * worth carrying at every level instead of being outgrown.
 *
 * @param flat    health restored per mouthful, or 0 when it heals by share
 * @param percent share of maximum health per mouthful, or 0 when it heals flat
 * @param pool    how much health is in the bottle altogether, or 0 for a single
 *                mouthful and then gone. A bottle is drained by what actually
 *                came back, so treating a scratch costs the scratch
 */
public record Healing(int flat, int percent, int pool) {

    public boolean hasPool() {
        return pool > 0;
    }

    /**
     * One mouthful, for a character of this size and this hurt.
     *
     * @param left what is left in the bottle, ignored when it has no pool
     * @return health restored, which is never more than is missing
     */
    public int sip(int maxHp, int missing, int left) {
        int mouthful = percent > 0 ? Math.max(1, maxHp * percent / 100) : flat;
        if (hasPool()) {
            mouthful = Math.min(mouthful, left);
        }
        return Math.max(0, Math.min(missing, mouthful));
    }

    /** What to show on the bottle: "+40" or "50%". */
    public String describe() {
        return percent > 0 ? percent + "%" : "+" + flat;
    }
}
