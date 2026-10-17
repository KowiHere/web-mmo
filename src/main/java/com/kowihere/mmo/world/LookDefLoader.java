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

/** Reads the looks from classpath JSON. The smallest loader here, and the plainest. */
@Component
public class LookDefLoader {

    private static final String LOCATION = "classpath:looks/*.json";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String location;

    public LookDefLoader() {
        this(LOCATION);
    }

    public LookDefLoader(String location) {
        this.location = location;
    }

    public Map<String, LookDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, LookDef>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                LookDef def = parse(resource);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate look id: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read looks from " + location, e);
        }
        return Map.copyOf(loaded);
    }

    private LookDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();
        JsonNode id = root.get("id");
        if (id == null || id.isNull() || id.asText().isBlank()) {
            throw new IllegalStateException(where + ": missing required field 'id'");
        }
        return new LookDef(id.asText(), root.path("name").asText(id.asText()));
    }
}
