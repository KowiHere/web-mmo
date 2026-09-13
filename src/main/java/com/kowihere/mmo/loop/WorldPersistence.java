package com.kowihere.mmo.loop;

/**
 * Where the world sends what should outlive it.
 *
 * <p>The one rule an implementation must honour: {@link #save} is called from
 * the map thread and must not block it. A slow disk or a locked table has to
 * become someone else's problem before it becomes everyone's lag.
 */
public interface WorldPersistence {

    /** Keeps nothing. The world runs the same, it just forgets. */
    WorldPersistence NONE = snapshot -> {
    };

    void save(ActorSnapshot snapshot);
}
