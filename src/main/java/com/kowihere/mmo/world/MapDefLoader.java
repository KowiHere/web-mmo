package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
    private final MobDefLoader mobs;
    private final NpcDefLoader npcs;
    private final String location;
    private final Map<String, ItemDef> items;

    public MapDefLoader() {
        this(new MobDefLoader());
    }

    public MapDefLoader(MobDefLoader mobs) {
        this(mobs, LOCATION);
    }

    /** Where the maps live. Content need not sit at the default path. */
    public MapDefLoader(MobDefLoader mobs, String location) {
        this(mobs, new NpcDefLoader(), location);
    }

    public MapDefLoader(MobDefLoader mobs, NpcDefLoader npcs, String location) {
        this(mobs, npcs, location, new ItemDefLoader().loadAll());
    }

    /**
     * @param items what a door is allowed to ask for as a key, checked while
     *              loading. A door demanding something that does not exist is a
     *              door nobody can ever open, and nothing at runtime would say
     *              so - it would simply refuse everybody for ever.
     */
    public MapDefLoader(MobDefLoader mobs, NpcDefLoader npcs, String location,
                        Map<String, ItemDef> items) {
        this.mobs = mobs;
        this.npcs = npcs;
        this.location = location;
        this.items = items;
    }

    public Map<String, MapDef> loadAll() {
        Map<String, MobDef> creatures = mobs.loadAll();
        Map<String, NpcDef> people = npcs.loadAll();
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, MapDef>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                MapDef def = parse(resource, creatures, people);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate map id: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read map definitions from " + location, e);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("No map definitions found at " + location);
        }
        checkTheDoorsLeadSomewhere(loaded);
        return Map.copyOf(loaded);
    }

    /**
     * The checks that need every map at once, and so cannot be made while one
     * file is being read.
     *
     * <p>All of them are about a door that looks fine on its own page: it names
     * a map, a tile and sometimes a key, and each of those is only right or
     * wrong in the company of the others.
     */
    private void checkTheDoorsLeadSomewhere(Map<String, MapDef> maps) {
        for (MapDef from : maps.values()) {
            for (Door door : from.doors()) {
                String where = from.id() + " (" + door.x() + "," + door.y() + ")";
                MapDef to = maps.get(door.toMap());
                if (to == null) {
                    throw new IllegalStateException(where + ": leads to '" + door.toMap()
                            + "', which is not a map. Known: " + maps.keySet());
                }
                if (!to.walkable(door.toX(), door.toY())) {
                    throw new IllegalStateException(where + ": leads onto " + door.toX() + ","
                            + door.toY() + " of '" + to.id()
                            + "', which is not a tile anything can stand on");
                }
                if (to.doorAt(door.toX(), door.toY()) != null) {
                    // Arriving on a door would take the same step twice: through,
                    // and straight back. Whoever walked in would be thrown
                    // between two maps until they closed the tab.
                    throw new IllegalStateException(where + ": leads onto another door on '"
                            + to.id() + "', so walking in would bounce straight back out");
                }
                if (door.requiresItem() != null && !items.containsKey(door.requiresItem())) {
                    throw new IllegalStateException(where + ": asks for '" + door.requiresItem()
                            + "', which is not an item, so nobody could ever open it");
                }
                if (door.consumesItem() != null && !items.containsKey(door.consumesItem())) {
                    throw new IllegalStateException(where + ": takes '" + door.consumesItem()
                            + "', which is not an item, so nobody could ever pay it");
                }
            }
            RespawnPoint respawn = from.respawn();
            MapDef wakesOn = maps.get(respawn.mapId());
            if (wakesOn == null) {
                throw new IllegalStateException(from.id() + ": wakes its dead on '"
                        + respawn.mapId() + "', which is not a map");
            }
            if (!wakesOn.walkable(respawn.x(), respawn.y())) {
                throw new IllegalStateException(from.id() + ": wakes its dead at " + respawn.x()
                        + "," + respawn.y() + " of '" + wakesOn.id()
                        + "', which is not a tile anything can stand on");
            }
        }
    }

    private MapDef parse(Resource resource, Map<String, MobDef> creatures,
                         Map<String, NpcDef> people) throws IOException {
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

        // Built once without its creatures purely so the spawn validation below
        // can ask walkable() instead of re-deriving collision from the bitset.
        MapDef map = new MapDef(id, name, width, height, tileSize, spawnX, spawnY, blocked, rows,
                List.of(), List.of(), List.of(), false, List.of(), null);
        return new MapDef(id, name, width, height, tileSize, spawnX, spawnY, blocked, rows,
                spawnPoints(root, map, creatures, where),
                roamingSpawns(root, creatures, where),
                npcPlacements(root, map, people, where),
                root.path("starting").asBoolean(false),
                doors(root, map, where),
                respawn(root, where));
    }

    /**
     * Creatures at marked places. A spawn inside a wall is refused rather than
     * nudged aside: a creature that cannot be reached is content that looks
     * present and is not, which is worse than a server that will not start.
     */
    private static List<SpawnPoint> spawnPoints(JsonNode root, MapDef map,
                                                Map<String, MobDef> creatures, String where) {
        List<SpawnPoint> points = new ArrayList<>();
        for (JsonNode node : root.path("spawns")) {
            String mobId = requireKnownMob(node, "mob", creatures, where);
            int x = node.path("x").asInt(-1);
            int y = node.path("y").asInt(-1);
            if (!map.walkable(x, y)) {
                throw new IllegalStateException(where + ": spawn for '" + mobId + "' at " + x + ","
                        + y + " is not a tile anything can stand on");
            }
            points.add(new SpawnPoint(mobId, x, y));
        }
        return points;
    }

    private static List<RoamingSpawn> roamingSpawns(JsonNode root, Map<String, MobDef> creatures,
                                                    String where) {
        List<RoamingSpawn> roaming = new ArrayList<>();
        for (JsonNode node : root.path("roaming")) {
            String mobId = requireKnownMob(node, "mob", creatures, where);
            int everySeconds = node.path("everySeconds").asInt(300);
            double chance = node.path("chance").asDouble(1.0);
            if (everySeconds < 1) {
                throw new IllegalStateException(where + ": everySeconds for '" + mobId + "' must be positive");
            }
            if (chance <= 0 || chance > 1) {
                throw new IllegalStateException(
                        where + ": chance for '" + mobId + "' must be between 0 (exclusive) and 1");
            }

            JsonNode escort = node.path("escort");
            String escortId = escort.isMissingNode() || escort.isNull()
                    ? null : requireKnownMob(escort, "mob", creatures, where);
            int escortCount = escortId == null ? 0 : Math.max(0, escort.path("count").asInt(0));
            roaming.add(new RoamingSpawn(mobId, everySeconds, chance, escortId, escortCount));
        }
        return roaming;
    }

    /**
     * People and things, placed by hand. Validated exactly as a spawn is - an
     * NPC inside a wall is somebody the player can see and never reach, which
     * is a worse bug than a server that refuses to start.
     */
    private static List<NpcPlacement> npcPlacements(JsonNode root, MapDef map,
                                                    Map<String, NpcDef> people, String where) {
        List<NpcPlacement> placements = new ArrayList<>();
        for (JsonNode node : root.path("npcs")) {
            String npcId = node.path("npc").asText(null);
            NpcDef def = npcId == null ? null : people.get(npcId);
            if (def == null) {
                throw new IllegalStateException(where + ": unknown NPC '" + npcId
                        + "'. Known: " + people.keySet());
            }
            int x = node.path("x").asInt(-1);
            int y = node.path("y").asInt(-1);
            if (!map.walkable(x, y)) {
                throw new IllegalStateException(where + ": '" + npcId + "' stands at " + x + ","
                        + y + ", which is not a tile anything can stand on");
            }
            if (x == map.spawnX() && y == map.spawnY()) {
                // Everybody arrives on that tile, and two actors on one tile is
                // a rendering question nobody has answered yet.
                throw new IllegalStateException(where + ": '" + npcId
                        + "' stands on the tile players arrive at");
            }
            String rawFacing = node.path("dir").asText("DOWN");
            Direction facing = Direction.parse(rawFacing);
            if (facing == null) {
                throw new IllegalStateException(
                        where + ": '" + npcId + "' faces '" + rawFacing + "', which is not a direction");
            }
            placements.add(new NpcPlacement(def, x, y, facing));
        }
        return placements;
    }

    /**
     * Tiles that lead somewhere else. Everything here can be judged from this
     * one file; where the door <em>arrives</em> needs the other map, and is
     * checked once they are all read.
     */
    private static List<Door> doors(JsonNode root, MapDef map, String where) {
        List<Door> doors = new ArrayList<>();
        for (JsonNode node : root.path("doors")) {
            int x = node.path("x").asInt(-1);
            int y = node.path("y").asInt(-1);
            String toMap = node.path("to").asText(null);
            if (toMap == null || toMap.isBlank()) {
                throw new IllegalStateException(where + ": a door at " + x + "," + y
                        + " does not say where it leads");
            }
            if (!map.walkable(x, y)) {
                throw new IllegalStateException(where + ": a door at " + x + "," + y
                        + " is not on a tile anything can stand on");
            }
            if (x == map.spawnX() && y == map.spawnY()) {
                // Everybody arrives there, so everybody would be sent straight
                // out again - including somebody who has just woken up dead.
                throw new IllegalStateException(where + ": a door sits on the tile players"
                        + " arrive at");
            }
            if (doors.stream().anyMatch(other -> other.isAt(x, y))) {
                throw new IllegalStateException(where + ": two doors on " + x + "," + y
                        + "; which one a step takes would be whichever was read first");
            }
            int fromLevel = atLeastZero(node, "fromLevel", where);
            int untilLevel = atLeastZero(node, "untilLevel", where);
            if (fromLevel > 0 && untilLevel > 0 && untilLevel < fromLevel) {
                throw new IllegalStateException(where + ": a door at " + x + "," + y
                        + " opens from level " + fromLevel + " and closes above " + untilLevel
                        + ", so nobody is ever the right level for it");
            }
            String key = blankToNull(node.path("requiresItem").asText(null));
            String ticket = blankToNull(node.path("consumesItem").asText(null));
            if (key != null && key.equals(ticket)) {
                // It would be taken by passing and demanded by the same passing,
                // so the door would open exactly once and then refuse its own
                // owner for ever.
                throw new IllegalStateException(where + ": a door at " + x + "," + y
                        + " both keeps and consumes '" + key + "'");
            }
            doors.add(new Door(x, y, toMap, node.path("toX").asInt(-1), node.path("toY").asInt(-1),
                    node.path("name").asText(toMap), fromLevel, untilLevel, key, ticket));
        }
        return doors;
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static RespawnPoint respawn(JsonNode root, String where) {
        JsonNode node = root.get("respawn");
        if (node == null || node.isNull()) {
            return null; // this map's own spawn, which is what MapDef answers
        }
        String mapId = node.path("map").asText(null);
        if (mapId == null || mapId.isBlank()) {
            throw new IllegalStateException(where + ": \"respawn\" does not say which map");
        }
        return new RespawnPoint(mapId, node.path("x").asInt(-1), node.path("y").asInt(-1));
    }

    private static int atLeastZero(JsonNode node, String field, String where) {
        int value = node.path(field).asInt(0);
        if (value < 0) {
            throw new IllegalStateException(where + ": " + field + " cannot be negative");
        }
        return value;
    }

    private static String requireKnownMob(JsonNode node, String field,
                                          Map<String, MobDef> creatures, String where) {
        String mobId = node.path(field).asText(null);
        if (mobId == null || !creatures.containsKey(mobId)) {
            throw new IllegalStateException(where + ": unknown mob '" + mobId
                    + "'. Known: " + creatures.keySet());
        }
        return mobId;
    }

    private static JsonNode required(JsonNode root, String field, String where) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalStateException(where + ": missing required field '" + field + "'");
        }
        return node;
    }
}
