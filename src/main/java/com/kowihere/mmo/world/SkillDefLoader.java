package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Energy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads skills from classpath JSON at startup, the fourth content loader and
 * validated like the rest: a typo stops the server rather than producing a
 * skill somebody spends a point on and then cannot use.
 */
@Component
public class SkillDefLoader {

    private static final String LOCATION = "classpath:skills/*.json";

    /**
     * The skill that decides how fast energy charges. Named here because the
     * loop has to find it by more than convention, and because a server whose
     * content forgot it would charge everyone at the base rate for ever without
     * anything looking broken.
     */
    public static final String REGENERATION_ID = "regeneracja";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String location;
    private final Map<String, ClassDef> classes;

    public SkillDefLoader() {
        this(LOCATION);
    }

    public SkillDefLoader(String location) {
        this(location, new ClassDefLoader().loadAll());
    }

    /**
     * @param classes what a skill is allowed to belong to. Checked while
     *                loading, so a skill attached to a class that does not
     *                exist stops the server instead of becoming a skill nobody
     *                can ever learn.
     */
    public SkillDefLoader(String location, Map<String, ClassDef> classes) {
        this.location = location;
        this.classes = classes;
    }

    public Map<String, SkillDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, SkillDef>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                SkillDef def = parse(resource);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate skill id: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read skill definitions from " + location, e);
        }
        if (!loaded.isEmpty() && !loaded.containsKey(REGENERATION_ID)) {
            throw new IllegalStateException("No '" + REGENERATION_ID + "' skill at " + location
                    + ". Without it energy charges at the base rate for everybody and no point"
                    + " spent on it can do anything, which nothing else would report.");
        }
        // Sorted, for the same reason the classes are: a list that moves between
        // restarts is a small thing that reads as a bug.
        return Collections.unmodifiableMap(new TreeMap<>(loaded));
    }

    private SkillDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();

        String id = text(root, "id", where);
        String name = root.path("name").asText(id);
        String description = root.path("description").asText("");

        String classId = root.path("classId").isMissingNode() || root.path("classId").isNull()
                ? null
                : root.path("classId").asText();
        if (classId != null && !classes.containsKey(classId)) {
            throw new IllegalStateException(where + ": belongs to class '" + classId
                    + "', which does not exist. Refusing to start rather than loading a skill"
                    + " nobody could ever learn.");
        }

        int cost = atLeast(root, "cost", 0, 0, where);
        if (cost > Energy.MAX) {
            throw new IllegalStateException(where + ": costs " + cost + " energy, but nobody can"
                    + " hold more than " + Energy.MAX + ". It could never be used.");
        }

        int energyPerRank = atLeast(root, "energyPerRank", 0, 0, where);
        double power = root.path("power").asDouble(0);
        int blows = atLeast(root, "blows", 0, 0, where);

        if (cost > 0 && (power <= 0 || blows <= 0)) {
            // A skill that is paid for and strikes nothing is a button that
            // takes energy and does nothing - and a player would report it as
            // "my skill does not work" rather than as a broken definition.
            throw new IllegalStateException(where + ": costs energy but never strikes."
                    + " Give it power and blows, or make it passive with cost 0.");
        }
        if (cost == 0 && energyPerRank == 0) {
            throw new IllegalStateException(where + ": is passive but grants nothing."
                    + " Check the spelling of \"energyPerRank\".");
        }

        return new SkillDef(id, name, description, classId, cost,
                atLeast(root, "maxRank", 1, 1, where),
                power, blows,
                root.path("armorIgnored").asDouble(-1),
                energyPerRank);
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
