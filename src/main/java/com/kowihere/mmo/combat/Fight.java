package com.kowihere.mmo.combat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One fight in progress, owned by the map it happens on and resolved inside its
 * tick. Not a thread, not a queue, and never touched from anywhere else.
 *
 * <p>Deliberately generic over its participants: the loop keeps actors, and this
 * class only needs to know which ids are on which side. That keeps combat rules
 * testable without dragging a running world along.
 */
public final class Fight {

    private final List<Integer> players = new ArrayList<>();
    private final List<Integer> mobs = new ArrayList<>();
    private final Set<Integer> tryingToFlee = new HashSet<>();

    private long nextRoundTick;

    public Fight(int playerId, int mobId, long firstRoundTick) {
        players.add(playerId);
        mobs.add(mobId);
        this.nextRoundTick = firstRoundTick;
    }

    public List<Integer> players() {
        return players;
    }

    public List<Integer> mobs() {
        return mobs;
    }

    public boolean has(int actorId) {
        return players.contains(actorId) || mobs.contains(actorId);
    }

    /** A creature that wandered into an existing fight joins it rather than starting another. */
    public void addMob(int mobId) {
        if (!mobs.contains(mobId)) {
            mobs.add(mobId);
        }
    }

    public void addPlayer(int playerId) {
        if (!players.contains(playerId)) {
            players.add(playerId);
        }
    }

    public void remove(int actorId) {
        players.remove(Integer.valueOf(actorId));
        mobs.remove(Integer.valueOf(actorId));
        tryingToFlee.remove(actorId);
    }

    public boolean isOver() {
        return players.isEmpty() || mobs.isEmpty();
    }

    public void wantsToFlee(int actorId) {
        if (players.contains(actorId)) {
            tryingToFlee.add(actorId);
        }
    }

    public boolean isFleeing(int actorId) {
        return tryingToFlee.contains(actorId);
    }

    /** Forgets every escape asked for this round. Asking again is a new round. */
    public void clearFleeRequests() {
        tryingToFlee.clear();
    }

    public void stopFleeing(int actorId) {
        tryingToFlee.remove(actorId);
    }

    public boolean isRoundDue(long tick) {
        return tick >= nextRoundTick;
    }

    public void scheduleNextRound(long tick) {
        this.nextRoundTick = tick;
    }

    /** Everyone in the fight, in a stable order, so the same line-up always resolves the same way. */
    public List<Integer> everyone() {
        List<Integer> all = new ArrayList<>(players.size() + mobs.size());
        all.addAll(players);
        all.addAll(mobs);
        all.sort(Integer::compare);
        return all;
    }
}
