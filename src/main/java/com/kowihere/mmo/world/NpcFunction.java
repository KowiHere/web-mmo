package com.kowihere.mmo.world;

import java.util.Locale;

/**
 * What an NPC <em>does</em>. A list, not a field: a blacksmith sells, repairs
 * and talks, and the day one of those has to be taken away from him should not
 * be the day he changes into a different kind of thing.
 *
 * <p>Kept strictly apart from what he <em>is</em>: see {@link NpcKind}.
 *
 * <p>Most of these cannot be honoured yet, and say so. The alternative - loading
 * a shopkeeper into a world with no money in it - produces an NPC who opens,
 * offers nothing, and looks for all the world like a bug in the client.
 */
public enum NpcFunction {

    /** Talks, in a tree of nodes and options. The only one that works today. */
    DIALOGUE(true, null),

    /**
     * Restores health for nothing. The one cure in the game: nothing regenerates
     * on its own, and dying no longer heals either.
     */
    HEALER(true, null),

    /** A second bag, kept in one place. */
    STORAGE(false, "skład wymaga drugiego pojemnika i migracji"),

    /** Sells ranks in a skill. */
    TRAINER(false, "nauka umiejętności jest na razie darmowa, więc nie ma czym płacić"),

    /**
     * Buys and sells. Blocked on something larger than itself: this world has no
     * money of any kind, and the answer is not one column called gold - there
     * will be several currencies, so it is a registry in content and a table of
     * character-by-currency, and that is a milestone, not a field.
     */
    SHOP(false, "nie ma żadnej waluty - sklep nie ma czym handlować"),

    /** Moves a character to another map. */
    TELEPORT(false, "jest tylko jedna mapa, więc nie ma dokąd przenosić"),

    /** Gives and settles tasks. */
    QUEST(false, "nie ma gdzie zapisać postępu zadania");

    private final boolean implemented;
    private final String why;

    NpcFunction(boolean implemented, String why) {
        this.implemented = implemented;
        this.why = why;
    }

    /**
     * Whether anything in the current code knows how to honour this function.
     * Deliberately the same shape as {@link MobTier#isSpawnable()}, and for the
     * same reason: content that names it is refused at startup rather than
     * loaded into a state where it silently does nothing.
     */
    public boolean isImplemented() {
        return implemented;
    }

    /** What to tell whoever wrote the content, in their own language. */
    public String whyNotYet() {
        return why;
    }

    /** @return the function, or null when content names one that does not exist */
    public static NpcFunction parse(String raw) {
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
