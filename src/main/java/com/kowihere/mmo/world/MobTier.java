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
    MOB(true),

    /** Random tile and random time on a shared map, alone or escorted by mobs. */
    ELITE(true),

    /** Dungeon boss, placed by an instance template. */
    HERO(false),

    /** Raid boss, placed by an instance template. */
    COLOSSUS(false);

    private final boolean spawnable;

    MobTier(boolean spawnable) {
        this.spawnable = spawnable;
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
