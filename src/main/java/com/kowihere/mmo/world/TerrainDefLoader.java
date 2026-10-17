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
 * Reads the kinds of ground from classpath JSON, the twin of every other
 * loader here.
 *
 * <p>Strict about the symbols for a reason particular to this one: a map's
 * ground is a picture drawn in characters, and a character nobody declared
 * would be a hole in that picture that only shows up as a tile drawn wrong.
 */
@Component
public class TerrainDefLoader {

    private static final String LOCATION = "classpath:terrain/*.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String location;

    public TerrainDefLoader() {
        this(LOCATION);
    }

    /** Where the definitions live. Content need not sit at the default path. */
    public TerrainDefLoader(String location) {
        this.location = location;
    }

    /** @return every kind of ground, by id, and a second way in by symbol */
    public Map<String, TerrainDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, TerrainDef>();
        var symbols = new LinkedHashMap<Character, String>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                TerrainDef def = parse(resource);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate terrain id: " + def.id());
                }
                String taken = symbols.putIfAbsent(def.mark(), def.id());
                if (taken != null) {
                    // Two kinds of ground behind one character means half a map
                    // is drawn as the wrong one, and which half depends on the
                    // order the files happened to be read in.
                    throw new IllegalStateException("Terrain '" + def.id() + "' and '" + taken
                            + "' both answer to '" + def.mark() + "'");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read terrain from " + location, e);
        }
        return Map.copyOf(loaded);
    }

    private TerrainDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();

        String id = text(root, "id", where);
        String symbol = text(root, "symbol", where);
        if (symbol.length() != 1) {
            throw new IllegalStateException(where + ": '" + id + "' is drawn as \"" + symbol
                    + "\", and a map's ground is one character per tile.");
        }
        if (symbol.charAt(0) == '#' || symbol.charAt(0) == '.') {
            // The collision grid's own two characters. Sharing them would make
            // a terrain grid look like a collision grid at a glance, which is
            // the one mistake nobody would question while reading a file.
            throw new IllegalStateException(where + ": '" + id + "' is drawn as '"
                    + symbol + "', which the collision grid already uses.");
        }
        return new TerrainDef(id, symbol, root.path("name").asText(id));
    }

    private static String text(JsonNode root, String field, String where) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException(where + ": missing required field '" + field + "'");
        }
        return node.asText();
    }
}
