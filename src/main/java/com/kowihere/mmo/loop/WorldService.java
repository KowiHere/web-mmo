package com.kowihere.mmo.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.world.ClassDefLoader;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.ItemDefLoader;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.MobDefLoader;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the running maps and hands out references to them. This is the boundary
 * between Spring and the game: above it there are beans and requests, below it
 * there is a simulation that knows nothing about either.
 *
 * <p>Map threads are platform threads, not virtual ones - each is long-running
 * and CPU-bound between ticks, which is precisely the case virtual threads do
 * not help with.
 */
@Service
public class WorldService {

    private static final Logger log = LoggerFactory.getLogger(WorldService.class);

    private final MapDefLoader loader;
    private final MobDefLoader mobLoader;
    private final ItemDefLoader itemLoader;
    private final ClassDefLoader classLoader;
    private final ObjectMapper json;
    private final WorldPersistence persistence;
    private final Map<String, MapRunner> runners = new LinkedHashMap<>();
    private final List<Thread> threads = new ArrayList<>();

    /**
     * Which map each connected socket's character is on.
     *
     * <p>The one structure here that more than one thread touches: network
     * threads read it to know where to send a command, and a map thread writes
     * it when a character walks through a door. It is not world state - it is a
     * signpost - so a concurrent map is the whole of the synchronisation, and
     * the single-writer rule below it is untouched.
     *
     * <p>Identity, not equality: a {@code Client} is a socket, and two sockets
     * are never the same socket.
     */
    private final Map<Client, MapRunner> whereTheyAre = new ConcurrentHashMap<>();

    private String startingMapId;

    public WorldService(MapDefLoader loader, MobDefLoader mobLoader, ItemDefLoader itemLoader,
                        ClassDefLoader classLoader, ObjectMapper json,
                        WorldPersistence persistence) {
        this.loader = loader;
        this.mobLoader = mobLoader;
        this.itemLoader = itemLoader;
        this.classLoader = classLoader;
        this.json = json;
        this.persistence = persistence;
    }

    @PostConstruct
    void start() {
        Content content = new Content(mobLoader.loadAll(), itemLoader.loadAll(),
                classLoader.loadAll());
        Map<String, MobDef> mobs = content.mobs();
        Map<String, MapDef> defs = loader.loadAll();

        // Settled before a single thread starts. A world with no beginning, or
        // with two, is a world that should not come up at all - and finding
        // that out after three map threads are already running means stopping
        // them again in an exception path nobody ever tests.
        startingMapId = theOneThatStarts(defs.values());

        for (MapDef def : defs.values()) {
            MapRunner runner = new MapRunner(def, json, persistence, content);
            runners.put(def.id(), runner);
            Thread thread = new Thread(runner, "map-" + def.id());
            thread.setDaemon(false);
            threads.add(thread);
            thread.start();
        }
        log.info("World started with {} map(s), {} creature definition(s) and {} item(s);"
                        + " starting map is '{}'",
                runners.size(), mobs.size(), content.items().size(), startingMapId);
        log.info("Content also carries {} class(es) and {} skill(s)",
                content.classes().size(), content.skills().size());
    }

    @PreDestroy
    void stop() {
        runners.values().forEach(MapRunner::stop);
        for (Thread thread : threads) {
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public MapRunner map(String id) {
        MapRunner runner = runners.get(id);
        if (runner == null) {
            throw new IllegalArgumentException("No such map: " + id);
        }
        return runner;
    }

    /**
     * Which map a character with nowhere else to be begins on.
     *
     * <p>Asked here rather than in the loader: whether one file is coherent is
     * the loader's question, and whether this world has a beginning is this
     * one's. A fixture map used by a single test is content too, and has no
     * business declaring where the game starts.
     */
    private static String theOneThatStarts(Iterable<MapDef> defs) {
        String found = null;
        for (MapDef def : defs) {
            if (!def.isStarting()) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("Both '" + found + "' and '" + def.id()
                        + "' declare themselves the starting map, so which one the game begins on"
                        + " would be decided by iteration order - the very accident this field"
                        + " exists to remove.");
            }
            found = def.id();
        }
        if (found == null) {
            throw new IllegalStateException("No map declares itself \"starting\", so there is"
                    + " nowhere for a character with no usable stored map to begin.");
        }
        return found;
    }

    /** Where a character with no usable stored map begins. Declared in content. */
    public MapRunner startingMap() {
        return map(startingMapId);
    }

    /**
     * The map named, or the starting one when content no longer has it.
     *
     * <p>A stored map can disappear between sessions - it is content, and
     * content gets edited. Refusing to let somebody play because the wood they
     * logged out in was renamed would be the wrong answer.
     */
    public MapRunner mapOrStarting(String id) {
        MapRunner runner = id == null ? null : runners.get(id);
        if (runner == null) {
            if (id != null) {
                log.info("No map '{}' any more; starting on '{}' instead", id, startingMapId);
            }
            return startingMap();
        }
        return runner;
    }

    /** Remembers which map this socket's commands should go to from now on. */
    public void nowOn(Client client, MapRunner map) {
        whereTheyAre.put(client, map);
    }

    public void forget(Client client) {
        whereTheyAre.remove(client);
    }

    /**
     * Where this socket's commands belong.
     *
     * <p>Null before the handshake has settled, and for a socket that has been
     * forgotten. A command with nowhere to go is dropped, which is also what
     * happens to one that arrives during the instant a character is between two
     * maps: the source no longer has it and the destination does not have it
     * yet, so whichever map receives the command ignores it. Losing a keystroke
     * to a doorway is not worth a lock across the whole world.
     */
    public MapRunner mapOf(Client client) {
        return whereTheyAre.get(client);
    }
}
