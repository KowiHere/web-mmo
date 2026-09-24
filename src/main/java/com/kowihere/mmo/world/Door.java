package com.kowihere.mmo.world;

/**
 * A tile that leads somewhere else, and what it asks of whoever steps on it.
 *
 * <p>Stepping is the whole interaction: there is nothing to click and nothing
 * to confirm. A door that needed a second action would be a door that a player
 * walks onto and wonders about.
 *
 * @param toMap where it leads
 * @param name  what to call the place on the other side, for the interface
 * @param fromLevel the level below which it will not let anybody in; 0 for
 *                  anybody
 * @param untilLevel the level above which it will not let anybody in - a way in
 *                   for beginners that stops being one; 0 for no ceiling
 * @param requiresItem an item that must be in the bag. <strong>Not taken</strong>:
 *                     it is a key, not a ticket, and a door that eats what
 *                     opened it can only be walked through once
 * @param consumesItem an item that must be in the bag and <strong>is</strong>
 *                     taken by passing: a ticket. The only difference from the
 *                     key is what is left afterwards - and it is the reason
 *                     this one is the only door in the game that asks before it
 *                     opens
 */
public record Door(int x, int y, String toMap, int toX, int toY, String name,
                   int fromLevel, int untilLevel, String requiresItem, String consumesItem) {

    public boolean isAt(int atX, int atY) {
        return x == atX && y == atY;
    }

    /** Whether stepping on this one costs something the character owns. */
    public boolean takesSomething() {
        return consumesItem != null;
    }

    /**
     * @param hasTheKey    whether the key, if any, is in the bag
     * @param hasTheTicket whether the ticket, if any, is in the bag
     * @param keyName      what to call the key when it is missing
     * @param ticketName   and the ticket
     * @return why this character may not pass, or null when they may
     */
    public String refuse(int level, boolean hasTheKey, boolean hasTheTicket,
                         String keyName, String ticketName) {
        if (fromLevel > 0 && level < fromLevel) {
            return "To przejście otwiera się od " + fromLevel + " poziomu.";
        }
        if (untilLevel > 0 && level > untilLevel) {
            return "To przejście jest dla postaci najwyżej " + untilLevel + " poziomu.";
        }
        if (requiresItem != null && !hasTheKey) {
            return "Potrzebujesz: " + keyName + ".";
        }
        if (consumesItem != null && !hasTheTicket) {
            return "Potrzebujesz: " + ticketName + ".";
        }
        return null;
    }
}
