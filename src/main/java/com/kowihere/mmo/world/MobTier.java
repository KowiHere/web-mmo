package com.kowihere.mmo.world;

/**
 * What kind of creature this is - and, more usefully, how it comes to be on a
 * map at all.
 *
 * <p>The tier is not a difficulty multiplier. It decides the spawn policy, which
 * is the part that actually differs: a mob stands at a fixed point, an elite
 * turns up somewhere unpredictable, and a boss is placed by the instance it
 * belongs to. Treating the tier as "how much health" is how a boss ends up being
 * a fat mob.
 */
public enum MobTier {

    /** Fixed spawn points on a shared map, short respawn. */
    MOB(true, 1.0),

    /** Random tile and random time on a shared map, alone or escorted by mobs. */
    ELITE(true, 8.0),

    /** Dungeon boss, placed by an instance template. */
    HERO(false, 30.0),

    /** Raid boss, placed by an instance template. */
    COLOSSUS(false, 100.0);

    private final boolean spawnable;
    private final double xpMultiplier;

    MobTier(boolean spawnable, double xpMultiplier) {
        this.spawnable = spawnable;
        this.xpMultiplier = xpMultiplier;
    }

    /**
     * How much more this tier is worth than a common creature of the same level.
     * It lives here rather than in the combat rules because it is a property of
     * what the thing *is*, not of how a blow is resolved.
     */
    public double xpMultiplier() {
        return xpMultiplier;
    }

    /**
     * Whether anything in the current code knows how to put this tier into the
     * world. HERO and COLOSSUS need instances, which do not exist yet - so a
     * definition using one is refused at startup rather than loaded into a state
     * where it silently never appears.
     */
    public boolean isSpawnable() {
        return spawnable;
    }
}
