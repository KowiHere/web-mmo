package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.Attributes.Attribute;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads character classes from classpath JSON at startup, the third of the
 * content loaders and validated the same way: a typo stops the server rather
 * than producing a class somebody picks and then cannot play.
 */
@Component
public class ClassDefLoader {

    private static final String LOCATION = "classpath:classes/*.json";

    /**
     * The class every character falls back to. Named here rather than picked
     * from whatever loaded first, because the loaded order is not something the
     * content author controls, and a default that moves is not a default.
     */
    public static final String FALLBACK_ID = "wojownik";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String location;

    public ClassDefLoader() {
        this(LOCATION);
    }

    public ClassDefLoader(String location) {
        this.location = location;
    }

    public Map<String, ClassDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, ClassDef>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                ClassDef def = parse(resource);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate class id: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read class definitions from " + location, e);
        }
        if (loaded.isEmpty()) {
            // Every character has a class, so a server with none could not
            // create a single character. Better to say so at startup than to
            // fail on the first registration.
            throw new IllegalStateException("No character classes found at " + location);
        }
        // Sorted, so the choice offered to a player is in the same order every
        // time rather than in whatever order the classpath was walked.
        var ordered = new java.util.TreeMap<String, ClassDef>(loaded);
        return java.util.Collections.unmodifiableMap(ordered);
    }

    private ClassDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();

        String id = text(root, "id", where);
        String name = root.path("name").asText(id);
        String description = root.path("description").asText("");

        JsonNode start = root.path("startingAttributes");
        Attributes startingAttributes = new Attributes(
                atLeast(start, "strength", 1, Attributes.STARTING, where),
                atLeast(start, "agility", 1, Attributes.STARTING, where),
                atLeast(start, "intellect", 1, Attributes.STARTING, where));

        Attribute damageFrom = Attribute.parse(text(root, "damageFrom", where));
        if (damageFrom == null) {
            throw new IllegalStateException(where + ": unknown damageFrom '"
                    + root.path("damageFrom").asText() + "'");
        }

        double armorIgnored = root.path("armorIgnored").asDouble(0);
        if (armorIgnored < 0 || armorIgnored > 1) {
            throw new IllegalStateException(
                    where + ": armorIgnored must be between 0 and 1, got " + armorIgnored);
        }

        return new ClassDef(id, name, description, startingAttributes, damageFrom, armorIgnored,
                atLeast(root, "hpBonus", 0, 0, where));
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
