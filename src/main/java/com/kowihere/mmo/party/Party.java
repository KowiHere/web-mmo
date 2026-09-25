package com.kowihere.mmo.party;

import java.util.ArrayList;
import java.util.List;

/**
 * A party, as it is handed to anybody who asks.
 *
 * <p>Immutable, and replaced wholesale on every change - which is what makes it
 * safe to read from a map thread, a heartbeat and a socket at the same time
 * without a lock between them. The registry that owns it is the only writer.
 *
 * @param version goes up on every change, so a reader can tell "the same party
 *                again" from "the party, changed"
 */
public record Party(long id, long version, String leaderKey, List<Member> members) {

    /** As many as will fit on the panel and still mean something when loot is split. */
    public static final int MAX_MEMBERS = 5;

    public Party {
        members = List.copyOf(members);
    }

    public boolean has(String nameKey) {
        return members.stream().anyMatch(member -> member.nameKey().equals(nameKey));
    }

    public boolean isLedBy(String nameKey) {
        return leaderKey.equals(nameKey);
    }

    public boolean isFull() {
        return members.size() >= MAX_MEMBERS;
    }

    public Party with(Member joining) {
        List<Member> grown = new ArrayList<>(members);
        grown.add(joining);
        return new Party(id, version + 1, leaderKey, grown);
    }

    /**
     * The party without somebody.
     *
     * <p>When that somebody was leading it, the next one along takes over: a
     * party with nobody in charge cannot invite, cannot remove and cannot be
     * handed back, so it would be a party that can only shrink.
     */
    public Party without(String nameKey) {
        List<Member> left = new ArrayList<>(members);
        left.removeIf(member -> member.nameKey().equals(nameKey));
        if (left.isEmpty()) {
            return new Party(id, version + 1, leaderKey, left);
        }
        String leader = leaderKey.equals(nameKey) ? left.get(0).nameKey() : leaderKey;
        return new Party(id, version + 1, leader, left);
    }

    public Party ledBy(String nameKey) {
        return new Party(id, version + 1, nameKey, members);
    }
}
