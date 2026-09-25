package com.kowihere.mmo.party;

/**
 * One offer of a seat, waiting for an answer.
 *
 * <p>It expires on its own. An invitation that waited for ever would let
 * somebody join a party that has since filled up, changed leader, or stopped
 * existing - and the person who sent it would have forgotten it by then.
 */
public record Invitation(long partyId, String fromKey, String fromName, long expiresAt) {

    public boolean hasExpired(long now) {
        return now >= expiresAt;
    }
}
