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

        ItemDef def = new ItemDef(id, name, slot,
                atLeast(root, "requiresLevel", 1, 1, where),
                granted,
                atLeast(bonuses, "attack", 0, 0, where),
                atLeast(bonuses, "armor", 0, 0, where),
                // Worth something, always. A free item makes every price in
                // the game meaningless, and "value" left out of the JSON by
                // accident would be exactly that.
                atLeast(root, "value", 1, 0, where));

        if (granted.equals(new Attributes(0, 0, 0)) && def.attack() == 0 && def.armor() == 0) {
            // Not pedantry: an item that grants nothing cannot be told apart from
            // one whose bonuses were misspelled, and the second is a bug that
            // would otherwise be discovered by a player wondering why nothing
            // changed when they put it on.
            throw new IllegalStateException(where + ": '" + id + "' grants nothing at all."
                    + " Check the spelling inside \"bonuses\".");
        }
        return def;
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
