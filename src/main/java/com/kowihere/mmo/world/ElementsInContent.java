package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.kowihere.mmo.combat.Element;
import com.kowihere.mmo.combat.Resistances;
import com.kowihere.mmo.combat.Strikes;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Reading elements out of content, in one place, because three loaders read the
 * same two blocks and all three refuse the same mistakes.
 *
 * <pre>
 *   "strikes": { "element": "fire", "chance": 25 }
 *   "resists": { "fire": 30, "frost": -20 }
 * </pre>
 *
 * <p>Strict for the usual reason: an element misspelt in a file is a sword that
 * looks like a fire sword in every tooltip and has never set anything alight,
 * and nobody reports that as a bug - they report it as the sword being weak.
 */
final class ElementsInContent {

    private ElementsInContent() {
    }

    /** @return what this thing's blows carry, or null when they carry nothing */
    static Strikes strikes(JsonNode root, String where, String id) {
        JsonNode node = root.get("strikes");
        if (node == null || node.isNull()) {
            return null;
        }
        String named = node.path("element").asText(null);
        Element element = Element.parse(named);
        if (element == null) {
            throw new IllegalStateException(where + ": '" + id + "' strikes with '" + named
                    + "', which is not an element this game has. Known: " + Element.known());
        }
        if (!node.has("chance")) {
            throw new IllegalStateException(where + ": '" + id + "' strikes with " + element.key()
                    + " but never says how often, and a blow that always takes hold"
                    + " is the only weapon anybody would carry.");
        }
        int chance = node.path("chance").asInt(0);
        if (chance < 1 || chance > 100) {
            throw new IllegalStateException(where + ": '" + id + "' takes hold " + chance
                    + "% of the time, which is either never or more often than always.");
        }
        return new Strikes(element, chance);
    }

    /** @return what this thing shrugs off; never null, often nothing */
    static Resistances resists(JsonNode root, String where, String id) {
        JsonNode node = root.get("resists");
        if (node == null || node.isNull()) {
            return Resistances.NONE;
        }
        if (!node.isObject() || node.isEmpty()) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has an empty \"resists\", which reads as armour against"
                    + " everything and is armour against nothing.");
        }
        Map<Element, Integer> points = new EnumMap<>(Element.class);
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String field = names.next();
            Element element = Element.parse(field);
            if (element == null) {
                throw new IllegalStateException(where + ": '" + id + "' resists '" + field
                        + "', which is not an element this game has. Known: " + Element.known());
            }
            int amount = node.path(field).asInt(0);
            if (amount == 0) {
                throw new IllegalStateException(where + ": '" + id + "' resists "
                        + element.key() + " by nothing at all, which looks in every"
                        + " tooltip exactly like a line that does something.");
            }
            if (amount < Resistances.LEAST || amount > Resistances.MOST) {
                throw new IllegalStateException(where + ": '" + id + "' resists "
                        + element.key() + " by " + amount + "%, which is outside "
                        + Resistances.LEAST + "%.." + Resistances.MOST + "%.");
            }
            points.put(element, amount);
        }
        return new Resistances(points);
    }
}
