package com.kowihere.mmo.world;

import java.util.Locale;

/**
 * What an option does instead of leading somewhere. Today there is exactly one,
 * which is on purpose: every function beyond talking is a future member of this
 * enum ({@code HEAL}, {@code OPEN_SHOP}, {@code TAKE_QUEST}), so the shape is
 * here from the start and the options that use it arrive one at a time.
 */
public enum DialogueAction {

    /** Closes the conversation. Every branch needs one of these at the bottom. */
    END;

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
