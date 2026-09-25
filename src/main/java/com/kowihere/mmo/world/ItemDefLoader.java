package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads item definitions from classpath JSON at startup, the twin of
 * {@link MobDefLoader}: content versioned with the code, editable in a text
 * editor, and validated hard enough that a typo stops the server instead of
 * producing an item nobody can wear and nobody can explain.
 */
@Component
public class ItemDefLoader {

    private static final String LOCATION = "classpath:items/*.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String location;

    public ItemDefLoader() {
        this(LOCATION);
    }

    /** Where the definitions live. Content need not sit at the default path. */
    public ItemDefLoader(String location) {
        this.location = location;
    }

    public Map<String, ItemDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, ItemDef>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                ItemDef def = parse(resource);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate item id: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read item definitions from " + location, e);
        }
        return Map.copyOf(loaded);
    }

    private ItemDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();

        String id = text(root, "id", where);
        String name = root.path("name").asText(id);
        ItemSlot slot = slot(root, where);

        JsonNode bonuses = root.path("bonuses");
        Attributes granted = new Attributes(
                atLeast(bonuses, "strength", 0, 0, where),
                atLeast(bonuses, "agility", 0, 0, where),
                atLeast(bonuses, "intellect", 0, 0, where));

        Healing healing = root.has("heal") ? healing(root.get("heal"), where, id) : null;
        if (healing != null && slot.isWorn()) {
            // Something you wear and something you drink are two different
            // verbs, and an item claiming both would be worn for its bonuses
            // and never drunk - or drunk off somebody's body mid-fight.
            throw new IllegalStateException(where + ": '" + id + "' is worn in " + slot
                    + " and also drinkable; it has to be one or the other.");
        }

        ItemDef def = new ItemDef(id, name, slot,
                atLeast(root, "requiresLevel", 1, 1, where),
                granted,
                atLeast(bonuses, "attack", 0, 0, where),
                atLeast(bonuses, "armor", 0, 0, where),
                healing,
                // Worth something, always. A free item makes every price in
                // the game meaningless, and "value" left out of the JSON by
                // accident would be exactly that.
                atLeast(root, "value", 1, 0, where));

        boolean grantsNothing = granted.equals(new Attributes(0, 0, 0))
                && def.attack() == 0 && def.armor() == 0;
        if (slot.isWorn() && grantsNothing) {
            // Not pedantry: an item that grants nothing cannot be told apart from
            // one whose bonuses were misspelled, and the second is a bug that
            // would otherwise be discovered by a player wondering why nothing
            // changed when they put it on.
            throw new IllegalStateException(where + ": '" + id + "' grants nothing at all."
                    + " Check the spelling inside \"bonuses\".");
        }
        if (!slot.isWorn() && !grantsNothing) {
            // The other way round, and the same mistake: bonuses on something
            // nobody can put on are bonuses nobody will ever have, sitting in
            // the file looking as though somebody will.
            throw new IllegalStateException(where + ": '" + id + "' is carried rather than worn,"
                    + " so its bonuses could never reach anybody.");
        }
        return def;
    }

    /**
     * The "heal" block. Every way of writing it wrongly ends in a bottle that
     * looks like a bottle and heals nothing, which a player finds out at the
     * one moment they cannot afford to.
     */
    private static Healing healing(JsonNode node, String where, String id) {
        int flat = atLeast(node, "flat", 0, 0, where);
        int percent = atLeast(node, "percent", 0, 0, where);
        int pool = atLeast(node, "pool", 0, 0, where);
        if (flat == 0 && percent == 0) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has a \"heal\" that heals nothing. Say \"flat\" or \"percent\".");
        }
        if (flat > 0 && percent > 0) {
            // Which of the two applies would be decided by whichever line the
            // code reads first, and the file would look like it said both.
            throw new IllegalStateException(where + ": '" + id
                    + "' heals both " + flat + " and " + percent + "%; it can only be one.");
        }
        if (percent > 100) {
            throw new IllegalStateException(where + ": '" + id + "' heals " + percent
                    + "% of maximum health, and there is no such thing as more than all of it.");
        }
        if (pool > 0 && pool < (percent > 0 ? 1 : flat)) {
            // A bottle holding less than one mouthful is a bottle that empties
            // on its first use, priced as though it would not.
            throw new IllegalStateException(where + ": '" + id + "' holds " + pool
                    + ", which is less than the " + flat + " one mouthful gives back.");
        }
        return new Healing(flat, percent, pool);
    }

    private static ItemSlot slot(JsonNode root, String where) {
        String raw = text(root, "slot", where);
        ItemSlot slot = ItemSlot.parse(raw);
        if (slot == null) {
            throw new IllegalStateException(where + ": unknown slot '" + raw + "'");
        }
        return slot;
    }

    private static String text(JsonNode root, String field, String where) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException(where + ": missing required field '" + field + "'");
        }
        return node.asText();
    }

    private static int atLeast(JsonNode root, String field, int minimum, int fallback, String where) {
        int value = root.path(field).asInt(fallback);
        if (value < minimum) {
            throw new IllegalStateException(
                    where + ": " + field + " must be at least " + minimum + ", got " + value);
        }
        return value;
    }
}
