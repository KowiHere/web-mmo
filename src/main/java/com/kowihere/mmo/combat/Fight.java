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

    /**
     * Who has actually done something in this fight.
     *
     * <p>The whole of "taking part", and deliberately a yes or a no rather
     * than a tally of damage. Counting blows would make a party a race and a
     * supporting role unplayable, because there is no honest exchange rate
     * between a sword and a bandage. This way there does not have to be one:
     * whoever acted shares, and healing somebody will qualify exactly as
     * hitting something does, on the day healing somebody exists.
     *
     * <p>Belongs to the fight and dies with it, like the energy a skill is
     * paid for with.
     */
    private final Set<Integer> tookPart = new HashSet<>();

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

    /** Noted once per fight, however many blows follow. */
    public void tookPart(int actorId) {
        tookPart.add(actorId);
    }

    public boolean didTakePart(int actorId) {
        return tookPart.contains(actorId);
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
        // Not tookPart: somebody who fought and then fled or was killed still
        // fought, and the kill a moment later is partly theirs.
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
