package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Reading a {@code "look"} out of content, in one place, because three loaders
 * read the same field and all three refuse the same mistake.
 *
 * <p>The mistake being a misspelt look, which is drawn as the plain one - so in
 * the file it is indistinguishable from a creature that was given a look and in
 * the game it is indistinguishable from one that was not.
 */
final class LooksInContent {

    private LooksInContent() {
    }

    /** @return the look, or null when this thing is content to be drawn plainly */
    static String look(JsonNode root, Map<String, LookDef> looks, String where, String id) {
        JsonNode node = root.get("look");
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        String look = node.asText().trim();
        if (!looks.containsKey(look)) {
            throw new IllegalStateException(where + ": '" + id + "' is drawn as '" + look
                    + "', which is not a look this game has. Known: " + looks.keySet());
        }
        return look;
    }
}
