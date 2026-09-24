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

    /**
     * Which socket each account is playing on, and which account each socket
     * belongs to.
     *
     * <p>One character from an account may be in the world at a time. That is a
     * rule of the game, but it is also what keeps the account's own storage
     * honest: there is then exactly one live copy of it, loaded with the
     * character that holds it, so nothing shared is ever written from two map
     * threads at once. The alternative is a lock across accounts, and the whole
     * design of this world is that there is no such lock.
     *
     * <p>Two maps rather than one so that a closing socket can give back its
     * claim without the caller having to remember whose it was.
     */
    private final Map<Long, Client> accountsInTheWorld = new ConcurrentHashMap<>();
    private final Map<Client, Long> whoseSocket = new ConcurrentHashMap<>();

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
            runner.transfersThrough(this::handOver);
            runners.put(def.id(), runner);
        }
        // Every map built before any of them runs: a map that started early
        // could hand somebody to one that does not exist yet.
        for (MapRunner runner : runners.values()) {
            Thread thread = new Thread(runner, "map-" + runner.mapId());
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

    /**
     * Takes a character one map is giving up and gives it to another.
     *
     * <p>Called on the giving map's thread. Everything it receives is either
     * immutable or a socket, and what it does with them is to put an ordinary
     * {@link Command.Join} in the other map's inbox - so a character arriving
     * through a door and one arriving through the front door take exactly the
     * same path into the world.
     */
    private void handOver(Client client, String toMapId, long accountId,
                          SavedCharacter character) {
        MapRunner destination = mapOrStarting(toMapId);
        // Pointed at the new map before the join is queued. A command arriving
        // in between goes to a map that does not have this character, and is
        // ignored there - which costs a keystroke and saves a lock on the world.
        whereTheyAre.put(client, destination);
        destination.submit(new Command.Join(client, accountId, character, 0L));
    }

    /**
     * Reserves an account for one socket.
     *
     * <p>Atomic, because two sockets of the same account can arrive on two
     * container threads within the same millisecond, and "check then put" would
     * let both of them through.
     *
     * @return true when this socket now holds the account; false when somebody
     *         else already does
     */
    public boolean claim(long accountId, Client client) {
        Client holder = accountsInTheWorld.putIfAbsent(accountId, client);
        if (holder != null && holder != client) {
            return false;
        }
        whoseSocket.put(client, accountId);
        return true;
    }

    /** Remembers which map this socket's commands should go to from now on. */
    public void nowOn(Client client, MapRunner map) {
        whereTheyAre.put(client, map);
    }

    /**
     * Forgets a socket entirely: where it was, and what it was holding.
     *
     * <p>A socket turned away because its account was busy has nothing to give
     * back: a refused claim records nothing, so closing it - which the handler
     * does immediately - cannot take the account away from whoever is actually
     * playing it.
     */
    public void forget(Client client) {
        whereTheyAre.remove(client);
        Long accountId = whoseSocket.remove(client);
        if (accountId != null) {
            accountsInTheWorld.remove(accountId);
        }
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
