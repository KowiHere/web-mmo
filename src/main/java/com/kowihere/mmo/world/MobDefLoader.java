package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final Map<String, ItemDef> items;
    private final Map<String, CurrencyDef> currencies;

    public MobDefLoader() {
        this(LOCATION);
    }

    /** Where the definitions live. Content need not sit at the default path. */
    public MobDefLoader(String location) {
        this(location, new ItemDefLoader().loadAll());
    }

    /**
     * @param items what a creature is allowed to drop. Checked while loading, so
     *              a misspelled reward stops the server instead of becoming a
     *              creature that silently drops nothing.
     */
    public MobDefLoader(String location, Map<String, ItemDef> items) {
        this(location, items, new CurrencyDefLoader().loadAll());
    }

    public MobDefLoader(String location, Map<String, ItemDef> items,
                        Map<String, CurrencyDef> currencies) {
        this.location = location;
        this.items = items;
        this.currencies = currencies;
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
                positive(root, "respawnSeconds", 30, where),
                loot(root, where),
                coins(root, where));
    }

    /**
     * What this creature is carrying. Validated as hard as the loot table: a
     * currency that does not exist would be money nobody could ever spend, and
     * the only sign of it would be a purse that never grows.
     */
    private List<CoinDrop> coins(JsonNode root, String where) {
        List<CoinDrop> drops = new ArrayList<>();
        for (JsonNode entry : root.path("coins")) {
            String currencyId = text(entry, "currency", where);
            if (!currencies.containsKey(currencyId)) {
                throw new IllegalStateException(where + ": drops '" + currencyId
                        + "', which is not a currency. Known: " + currencies.keySet());
            }
            int min = entry.path("min").asInt(0);
            int max = entry.path("max").asInt(min);
            if (min < 1) {
                throw new IllegalStateException(where + ": '" + currencyId
                        + "' drops a minimum of " + min + "; a drop of nothing is not a drop");
            }
            if (max < min) {
                throw new IllegalStateException(where + ": '" + currencyId + "' drops between "
                        + min + " and " + max + ", which is backwards");
            }
            double chance = entry.path("chance").asDouble(1.0);
            if (chance <= 0 || chance > 1) {
                throw new IllegalStateException(where + ": chance for '" + currencyId
                        + "' must be above 0 and at most 1, got " + chance);
            }
            drops.add(new CoinDrop(currencyId, min, max, chance));
        }
        return drops;
    }

    private List<LootEntry> loot(JsonNode root, String where) {
        List<LootEntry> entries = new ArrayList<>();
        for (JsonNode entry : root.path("loot")) {
            String itemId = text(entry, "item", where);
            if (!items.containsKey(itemId)) {
                throw new IllegalStateException(where + ": drops '" + itemId
                        + "', which is not an item. Refusing to start rather than loading a"
                        + " creature whose reward could never be given.");
            }
            double chance = entry.path("chance").asDouble(1.0);
            if (chance <= 0 || chance > 1) {
                throw new IllegalStateException(where + ": chance for '" + itemId
                        + "' must be above 0 and at most 1, got " + chance);
            }
            entries.add(new LootEntry(itemId, chance));
        }
        return List.copyOf(entries);
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
