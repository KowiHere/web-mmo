package com.kowihere.mmo.loop;

import java.util.Locale;

/**
 * One place that decides what a character is called.
 *
 * <p>Both the network layer (looking a character up before the world is told
 * about it) and the map thread (creating the actor) need the same answer, and
 * two slightly different cleanups would mean a player could be handed someone
 * else's character - or none at all.
 */
public final class PlayerNames {

    public static final int MAX_LENGTH = 16;
    private static final String FALLBACK = "Wedrowiec";

    private PlayerNames() {
    }

    /** The name as it will be shown. Idempotent: sanitising twice changes nothing. */
    public static String sanitise(String raw) {
        if (raw == null) {
            return FALLBACK;
        }
        String cleaned = raw.strip().replaceAll("[^\\p{L}\\p{N} _-]", "");
        if (cleaned.length() > MAX_LENGTH) {
            cleaned = cleaned.substring(0, MAX_LENGTH);
        }
        return cleaned.isBlank() ? FALLBACK : cleaned;
    }

    /** The name as identity: case-folded, so "Ala" and "ala" are one character. */
    public static String key(String raw) {
        return sanitise(raw).toLowerCase(Locale.ROOT);
    }
}
