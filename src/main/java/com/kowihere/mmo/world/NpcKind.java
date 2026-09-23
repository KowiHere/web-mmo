package com.kowihere.mmo.world;

import java.util.Locale;

/**
 * What an NPC <em>is</em>, which is only ever about how it presents itself.
 *
 * <p>Kept strictly apart from what it <em>does</em>: see {@link NpcFunction}. A
 * blacksmith sells, repairs and talks; a noticeboard only talks. Folding the two
 * together would produce a {@code SHOPKEEPER} that cannot also give a quest, and
 * then a {@code SHOPKEEPER_WITH_QUEST} - which is exactly how a creature tier
 * would have turned into "how much health".
 */
public enum NpcKind {

    /** Somebody. Drawn like a character and named above their head. */
    PERSON,

    /** Something. A chest, a noticeboard, a campfire - interacted with, not met. */
    OBJECT;

    /** @return the kind, or null when content names one that does not exist */
    public static NpcKind parse(String raw) {
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
