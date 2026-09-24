package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the currencies from classpath JSON at startup - the sixth twin of
 * {@link ItemDefLoader}.
 *
 * <p>Hands them back in the order content asked for, which is the order the
 * interface shows them in. That is why this one sorts and the others do not:
 * everything else is looked up by id, and a purse is read left to right.
 */
@Component
public class CurrencyDefLoader {

    private static final String LOCATION = "classpath:currencies/*.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String location;

    public CurrencyDefLoader() {
        this(LOCATION);
    }

    /** Where the definitions live. Content need not sit at the default path. */
    public CurrencyDefLoader(String location) {
        this.location = location;
    }

    public Map<String, CurrencyDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        List<CurrencyDef> found = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                found.add(parse(resource));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read currencies from " + location, e);
        }
        found.sort(Comparator.comparingInt(CurrencyDef::order).thenComparing(CurrencyDef::id));

        var loaded = new LinkedHashMap<String, CurrencyDef>();
        for (CurrencyDef def : found) {
            if (loaded.putIfAbsent(def.id(), def) != null) {
                throw new IllegalStateException("Duplicate currency id: " + def.id());
            }
        }
        long primaries = found.stream().filter(CurrencyDef::primary).count();
        if (!found.isEmpty() && primaries != 1) {
            throw new IllegalStateException("Exactly one currency at " + location
                    + " must be \"primary\" - the one the game charges in when no trader is"
                    + " involved - and " + primaries + " are marked. Without it nothing outside"
                    + " a shop can have a price; with two, the same thing has two.");
        }
        return java.util.Collections.unmodifiableMap(loaded);
    }

    private CurrencyDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();
        String id = text(root, "id", where);
        return new CurrencyDef(id, root.path("name").asText(id),
                text(root, "short", where),
                root.path("order").asInt(Integer.MAX_VALUE),
                root.path("primary").asBoolean(false));
    }

    private static String text(JsonNode root, String field, String where) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException(where + ": missing required field '" + field + "'");
        }
        return node.asText();
    }
}
