package com.kowihere.mmo.account;

import java.time.Instant;

public record Account(long id, String loginKey, String login, String passwordHash,
                      int failedLogins, Instant lockedUntil) {

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
