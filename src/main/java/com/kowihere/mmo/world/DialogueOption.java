package com.kowihere.mmo.world;

/**
 * One thing a player may say.
 *
 * <p>Every option has to say where the conversation goes: to another node, or
 * to the end of it. It may carry a deed as well - "patch me up" both heals and
 * gets an answer, and splitting that into two clicks would be an interface
 * chore pretending to be a rule of the game.
 *
 * @param goTo the node this leads to, or null when the action ends the talk
 * @param action what it does besides, or null when it only leads somewhere
 */
public record DialogueOption(String text, String goTo, DialogueAction action) {

    public boolean leadsSomewhere() {
        return goTo != null;
    }

    /** @return the deed to do before moving on, or null when there is none */
    public DialogueAction deed() {
        return action != null && !action.isDestination() ? action : null;
    }
}
