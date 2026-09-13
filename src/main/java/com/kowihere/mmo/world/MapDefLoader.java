package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads map definitions from classpath JSON at startup. Maps are content, not
 * rows in a table: they are versioned with the code, cacheable, and editable in
 * a text editor without a migration.
 */
@Component
public class MapDefLoader {

    private static final String LOCATION = "classpath:maps/*.json";

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, MapDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, MapDef>();
        try {
            for (Resource resource : resolver.getResources(LOCATION)) {
                MapDef def = parse(resource);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate map id: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read map definitions from " + LOCATION, e);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("No map definitions found at " + LOCATION);
        }
        return Map.copyOf(loaded);
    }

    private MapDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();
        String id = required(root, "id", where).asText();
        String name = root.path("name").asText(id);
        int tileSize = root.path("tileSize").asInt(32);

        JsonNode rowsNode = required(root, "collision", where);
        List<String> rows = mapper.convertValue(rowsNode, mapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        if (rows.isEmpty()) {
            throw new IllegalStateException(where + ": collision must have at least one row");
        }

        int height = rows.size();
        int width = rows.get(0).length();
        BitSet blocked = new BitSet(width * height);
        for (int y = 0; y < height; y++) {
            String row = rows.get(y);
            if (row.length() != width) {
                throw new IllegalStateException(
                        where + ": collision row " + y + " is " + row.length() + " chars, expected " + width);
            }
            for (int x = 0; x < width; x++) {
                if (row.charAt(x) == '#') {
                    blocked.set(y * width + x);
                }
            }
        }

        JsonNode spawn = required(root, "spawn", where);
        int spawnX = spawn.get(0).asInt();
        int spawnY = spawn.get(1).asInt();
        if (spawnX < 0 || spawnY < 0 || spawnX >= width || spawnY >= height) {
            throw new IllegalStateException(where + ": spawn " + spawnX + "," + spawnY + " is outside the map");
        }
        if (blocked.get(spawnY * width + spawnX)) {
            throw new IllegalStateException(where + ": spawn " + spawnX + "," + spawnY + " is on a blocked tile");
        }

        return new MapDef(id, name, width, height, tileSize, spawnX, spawnY, blocked, rows);
    }

    private static JsonNode required(JsonNode root, String field, String where) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalStateException(where + ": missing required field '" + field + "'");
        }
        return node;
    }
}
