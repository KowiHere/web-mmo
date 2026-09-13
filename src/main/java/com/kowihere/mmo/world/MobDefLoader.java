package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads creature definitions from classpath JSON at startup, the same way maps
 * are read: content versioned with the code, editable in a text editor, and
 * validated hard enough that a typo stops the server instead of producing a
 * creature that quietly never appears.
 */
@Component
public class MobDefLoader {

    private static final String LOCATION = "classpath:mobs/*.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String location;

    public MobDefLoader() {
        this(LOCATION);
    }

    /** Where the definitions live. Content need not sit at the default path. */
    public MobDefLoader(String location) {
        this.location = location;
    }

    public Map<String, MobDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, MobDef>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                MobDef def = parse(resource);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate mob id: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read mob definitions from " + location, e);
        }
        return Map.copyOf(loaded);
    }

    private MobDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();

        String id = text(root, "id", where);
        String name = root.path("name").asText(id);
        MobTier tier = tier(root, where);

        int stepTicks = positive(root, "stepTicks", 4, where);
        int aggroRadius = atLeast(root, "aggroRadius", 0, 4, where);
        int leashRadius = atLeast(root, "leashRadius", 0, 8, where);
        if (leashRadius < aggroRadius) {
            // Otherwise a creature notices a player, takes one step, finds itself
            // past the leash and turns back - jittering in place for ever.
            throw new IllegalStateException(
                    where + ": leashRadius (" + leashRadius + ") must be at least aggroRadius ("
                            + aggroRadius + "), or the creature would give up the moment it starts");
        }

        return new MobDef(id, name, tier,
                atLeast(root, "level", 1, 1, where),
                positive(root, "hp", 10, where),
                atLeast(root, "attack", 0, 1, where),
                atLeast(root, "armor", 0, 0, where),
                stepTicks, aggroRadius, leashRadius,
                positive(root, "respawnSeconds", 30, where));
    }

    private static MobTier tier(JsonNode root, String where) {
        String raw = text(root, "tier", where);
        MobTier tier;
        try {
            tier = MobTier.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(where + ": unknown tier '" + raw + "'");
        }
        if (!tier.isSpawnable()) {
            throw new IllegalStateException(where + ": tier " + tier
                    + " needs instances, which do not exist yet. Refusing to start rather than"
                    + " loading a creature that would never appear.");
        }
        return tier;
    }

    private static String text(JsonNode root, String field, String where) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException(where + ": missing required field '" + field + "'");
        }
        return node.asText();
    }

    private static int positive(JsonNode root, String field, int fallback, String where) {
        return atLeast(root, field, 1, fallback, where);
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
