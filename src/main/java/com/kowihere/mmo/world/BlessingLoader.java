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

    /**
     * The blessing dying lays on. Named here rather than in the rules because
     * it is content the code cannot do without, exactly like the class every
     * character falls back to: the rule "this one must exist" belongs where
     * content is read, so a missing file stops the server instead of quietly
     * making death free.
     */
    public static final String DEATH_WEAKNESS = "oslabienie-po-smierci";

    /**
     * What a percentage line may say. The lower end is total loss - below it
     * lies a statistic that would come back the other way - and the upper end
     * is simply further than anybody balancing this game meant to go.
     */
    private static final int MIN_PERCENT = -100;
    private static final int MAX_PERCENT = 500;

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
        if (!loaded.containsKey(DEATH_WEAKNESS)) {
            throw new IllegalStateException("No blessing '" + DEATH_WEAKNESS
                    + "'; without it dying costs nothing once the character is back on its feet.");
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

        Map<BlessingStat, BlessingLine> lines = lines(root.path("lines"), where, id);
        return new BlessingDef(id, name, rarity, minutes,
                Math.max(1, root.path("requiresLevel").asInt(1)), lines);
    }

    /**
     * The lines. Every mistake here produces a blessing that reads as though it
     * works - which is the one failure nobody reports, because the player
     * assumes the number was small.
     */
    private static Map<BlessingStat, BlessingLine> lines(JsonNode node, String where, String id) {
        if (!node.isObject() || node.isEmpty()) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has no \"lines\", so it would change nothing for twenty minutes.");
        }
        Map<BlessingStat, BlessingLine> lines = new LinkedHashMap<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String field = names.next();
            BlessingStat stat = BlessingStat.parse(field);
            if (stat == null) {
                throw new IllegalStateException(where + ": '" + id + "' changes '" + field
                        + "', which is not a statistic this game has. Known: " + BlessingStat.known());
            }
            if (lines.put(stat, line(node.path(field), where, id, stat)) != null) {
                throw new IllegalStateException(where + ": '" + id + "' names " + stat.key()
                        + " twice, and only one of the two would apply.");
            }
        }
        if (lines.values().stream().allMatch(line -> line.amount() == 0)) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has lines that are all zero, which looks exactly like a blessing that"
                    + " works and is not one.");
        }
        return lines;
    }

    /**
     * One line. A number is flat points; a string ending in {@code %} is a
     * share of whatever the character already has.
     *
     * <p>A string that is <em>not</em> a percentage is refused rather than read
     * as a number, because {@code "6"} and {@code 6} looking alike in a file is
     * exactly how a percentage ends up being applied flat, or the other way
     * round, with the tooltip reading correctly either way.
     */
    private static BlessingLine line(JsonNode value, String where, String id, BlessingStat stat) {
        if (value.isNumber()) {
            return BlessingLine.flat(value.asInt());
        }
        if (!value.isTextual()) {
            throw new IllegalStateException(where + ": '" + id + "' gives " + stat.key()
                    + " a value that is neither a number nor a percentage like \"-10%\".");
        }
        String written = value.asText().trim();
        if (!written.endsWith("%")) {
            throw new IllegalStateException(where + ": '" + id + "' writes " + stat.key()
                    + " as \"" + written + "\". Flat points are a number, not a string;"
                    + " a share ends in '%'.");
        }
        if (!stat.takesAShare()) {
            throw new IllegalStateException(where + ": '" + id + "' writes " + stat.key()
                    + " as a percentage, but that one is already measured in points"
                    + " - a share of it would mean nothing. Write flat points.");
        }
        int percent;
        try {
            percent = Integer.parseInt(written.substring(0, written.length() - 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(where + ": '" + id + "' writes " + stat.key()
                    + " as \"" + written + "\", which is not a whole percentage.");
        }
        if (percent == 0) {
            throw new IllegalStateException(where + ": '" + id + "' changes " + stat.key()
                    + " by 0%, which reads in the panel exactly like a line that does something.");
        }
        if (percent < MIN_PERCENT || percent > MAX_PERCENT) {
            throw new IllegalStateException(where + ": '" + id + "' changes " + stat.key()
                    + " by " + percent + "%, which is outside " + MIN_PERCENT + "%.."
                    + MAX_PERCENT + "% and is almost certainly a slipped digit.");
        }
        return BlessingLine.percent(percent);
    }

    private static String text(JsonNode root, String field, String where) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException(where + ": missing required field '" + field + "'");
        }
        return node.asText();
    }
}
