package com.kowihere.mmo.world;

import java.util.Locale;

/**
 * What an option does, over and above where it leads.
 *
 * <p>{@link #END} is the odd one out and always will be: it is the only member
 * that is a destination rather than a deed, which is why it is the only one
 * allowed to have no {@code goto} beside it. Everything added here later - a
 * shop opening, a quest taken - happens and then the conversation carries on.
 */
public enum DialogueAction {

    /** Closes the conversation. Every branch needs one of these at the bottom. */
    END,

    /** Restores the character to full health. Free, and free of any waiting. */
    HEAL,

    /** Opens this trader's stall. The conversation stays open behind it. */
    OPEN_SHOP;

    /**
     * Whether this is somewhere to go rather than something to do. Only
     * {@code END} is, and an option carrying anything else still has to say
     * where the conversation goes next.
     */
    public boolean isDestination() {
        return this == END;
    }

    /** @return the action, or null when content names one that does not exist */
    public static DialogueAction parse(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
