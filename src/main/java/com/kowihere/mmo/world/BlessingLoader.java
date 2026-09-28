package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads blessings from classpath JSON, the twin of every other loader here:
 * content versioned with the code, and a typo that stops the server rather than
 * a blessing that looks right in the tooltip and does nothing at all.
 *
 * <p>That last case is the whole reason this class is strict. A blessing is
 * read once and then believed for twenty minutes by everything that computes a
 * statistic; there is no moment at runtime where a misspelt line could announce
 * itself.
 */
@Component
public class BlessingLoader {

    private static final String LOCATION = "classpath:blessings/*.json";

    /** Longer than a session, and nobody meant it. */
    private static final int MAX_MINUTES = 240;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String location;

    public BlessingLoader() {
        this(LOCATION);
    }

    public BlessingLoader(String location) {
        this.location = location;
    }

    public Map<String, BlessingDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, BlessingDef>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                BlessingDef def = parse(resource);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate blessing id: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read blessings from " + location, e);
        }
        return Map.copyOf(loaded);
    }

    private BlessingDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();

        String id = text(root, "id", where);
        String name = root.path("name").asText(id);

        String rawRarity = text(root, "rarity", where);
        Rarity rarity = Rarity.parse(rawRarity);
        if (rarity == null) {
            throw new IllegalStateException(where + ": unknown rarity '" + rawRarity + "'");
        }

        int minutes = root.path("minutes").asInt(0);
        if (minutes <= 0) {
            // A blessing that has already expired when it is drunk, priced as
            // though it had not.
            throw new IllegalStateException(where + ": '" + id + "' lasts " + minutes
                    + " minutes, so it would be over before it began.");
        }
        if (minutes > MAX_MINUTES) {
            throw new IllegalStateException(where + ": '" + id + "' lasts " + minutes
                    + " minutes, which is longer than anybody plays in one sitting.");
        }

        Map<BlessingStat, Integer> lines = lines(root.path("lines"), where, id);
        return new BlessingDef(id, name, rarity, minutes,
                Math.max(1, root.path("requiresLevel").asInt(1)), lines);
    }

    /**
     * The lines. Every mistake here produces a blessing that reads as though it
     * works - which is the one failure nobody reports, because the player
     * assumes the number was small.
     */
    private static Map<BlessingStat, Integer> lines(JsonNode node, String where, String id) {
        if (!node.isObject() || node.isEmpty()) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has no \"lines\", so it would change nothing for twenty minutes.");
        }
        Map<BlessingStat, Integer> lines = new LinkedHashMap<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String field = names.next();
            BlessingStat stat = BlessingStat.parse(field);
            if (stat == null) {
                throw new IllegalStateException(where + ": '" + id + "' changes '" + field
                        + "', which is not a statistic this game has. Known: " + BlessingStat.known());
            }
            int value = node.path(field).asInt(0);
            if (lines.put(stat, value) != null) {
                throw new IllegalStateException(where + ": '" + id + "' names " + stat.key()
                        + " twice, and only one of the two would apply.");
            }
        }
        if (lines.values().stream().allMatch(value -> value == 0)) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has lines that are all zero, which looks exactly like a blessing that"
                    + " works and is not one.");
        }
        return lines;
    }

    private static String text(JsonNode root, String field, String where) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException(where + ": missing required field '" + field + "'");
        }
        return node.asText();
    }
}
