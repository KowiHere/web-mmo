package com.kowihere.mmo.world;

/**
 * One thing a player may say. It either leads to another node or does
 * something; the loader refuses one that does neither, and one that does both.
 *
 * @param text what the player says
 * @param goTo the node this leads to, or null when this option acts instead
 * @param action what it does, or null when it leads somewhere instead
 */
public record DialogueOption(String text, String goTo, DialogueAction action) {

    public boolean leadsSomewhere() {
        return goTo != null;
    }
}
