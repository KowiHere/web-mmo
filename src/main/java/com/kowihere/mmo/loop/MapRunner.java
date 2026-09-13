package com.kowihere.mmo.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.path.AStar;
import com.kowihere.mmo.protocol.ServerMessages;
import com.kowihere.mmo.protocol.ServerMessages.ActorDto;
import com.kowihere.mmo.protocol.ServerMessages.ChatDto;
import com.kowihere.mmo.protocol.ServerMessages.Delta;
import com.kowihere.mmo.protocol.ServerMessages.MapDto;
import com.kowihere.mmo.protocol.ServerMessages.MoveDto;
import com.kowihere.mmo.protocol.ServerMessages.PresenceDto;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.RoamingSpawn;
import com.kowihere.mmo.world.SpawnPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * One map, one thread, one writer. Everything below runs on that thread and on
 * no other: commands arrive through a lock-free queue, the world advances on a
 * fixed tick, and the only thing that leaves is a serialised delta.
 *
 * <p>Deltas carry a monotonically increasing version. A client that drops and
 * reconnects within {@link #HISTORY_TICKS} names the last version it saw and
 * gets the gap replayed instead of a full reload — which is also why a
 * disconnected player's actor lingers in the world for {@link #GRACE_TICKS}
 * rather than vanishing the instant the socket closes.
 */
public final class MapRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MapRunner.class);

    /** 10 ticks per second. Fast enough that a step looks continuous, slow enough to be nearly free. */
    static final int TICK_MS = 100;
    /** Ticks to cross one tile, i.e. 300 ms per step. */
    static final int STEP_TICKS = 3;
    /** Default time a disconnected character stays in the world before being removed. */
    private static final int DEFAULT_GRACE_TICKS = 300;
    /** How many past deltas are kept for reconnect replay. */
    private static final int HISTORY_TICKS = 300;
    private static final int CHAT_COOLDOWN_TICKS = 5;
    /** How often a moving character's position is handed to persistence. */
    private static final int SAVE_INTERVAL_TICKS = 150;
    /**
     * How many paths every mob on this map may collectively buy in one tick.
     *
     * The Etap 0 fix capped what one PLAYER could spend on pathfinding; mobs sit
     * outside that limit and can spend the same budget between them, so thirty
     * chasers would mean thirty A* searches per tick. Past this cap the rest
     * wander instead - a crowd should slow down, not freeze.
     */
    private static final int MOB_PATHS_PER_TICK = 4;
    private static final int MAX_CHAT_LENGTH = 200;
    private static final int MAX_COMMANDS_PER_TICK = 4_096;
    /** At most one overrun warning per 10 s, so an overloaded map does not flood the log. */
    private static final int OVERRUN_WARNING_INTERVAL_TICKS = 100;

    private final MapDef map;
    private final ObjectMapper json;
    private final WorldPersistence persistence;
    private final AStar pathfinder;
    private final Queue<Command> inbox = new ConcurrentLinkedQueue<>();
    private final MapDto mapDto;

    // ---- owned exclusively by the map thread from here down ----
    private final Map<Integer, Actor> actors = new HashMap<>();
    private final Map<String, Actor> byNameKey = new HashMap<>();
    private final Map<String, MobDef> mobDefs;
    private final MobBehaviour brain;
    private final Random random = new Random();
    private final Map<String, Long> nextRoamTick = new HashMap<>();
    private final int graceTicks;
    private final Map<Client, Actor> byClient = new IdentityHashMap<>();
    private final Deque<Delta> history = new ArrayDeque<>();

    private final List<ActorDto> joined = new ArrayList<>();
    private final List<Integer> left = new ArrayList<>();
    private final List<MoveDto> moved = new ArrayList<>();
    private final List<ChatDto> chat = new ArrayList<>();
    private final List<PresenceDto> presence = new ArrayList<>();
    private final List<Actor> pendingMoves = new ArrayList<>();

    private long version;
    private long tick;
    private int nextActorId = 1;
    private volatile boolean running = true;
    private long lastOverrunWarningTick = Long.MIN_VALUE / 2;

    public MapRunner(MapDef map, ObjectMapper json) {
        this(map, json, WorldPersistence.NONE);
    }

    public MapRunner(MapDef map, ObjectMapper json, WorldPersistence persistence) {
        this(map, json, persistence, Map.of());
    }

    public MapRunner(MapDef map, ObjectMapper json, WorldPersistence persistence,
                     Map<String, MobDef> mobDefs) {
        this(map, json, persistence, mobDefs, DEFAULT_GRACE_TICKS);
    }

    /**
     * @param graceTicks how long a character stays standing in the world after
     *                   its socket drops. Tunable mostly so tests need not wait
     *                   out the real half-minute.
     */
    public MapRunner(MapDef map, ObjectMapper json, WorldPersistence persistence,
                     Map<String, MobDef> mobDefs, int graceTicks) {
        this.graceTicks = graceTicks;
        this.map = map;
        this.json = json;
        this.persistence = persistence;
        this.mobDefs = mobDefs;
        this.pathfinder = new AStar(map);
        // The same pathfinder players use, deliberately: both run on this thread,
        // and sharing it makes pathSearches() the map's true total rather than
        // half of it.
        this.brain = new MobBehaviour(map, pathfinder, random);
        // MapDef is immutable, so this never changes - build it once instead of
        // rebuilding the whole collision grid on every player's arrival.
        this.mapDto = new MapDto(map.id(), map.name(), map.width(), map.height(),
                map.tileSize(), map.collisionRows());
    }

    public String mapId() {
        return map.id();
    }

    public int spawnX() {
        return map.spawnX();
    }

    public int spawnY() {
        return map.spawnY();
    }

    /** How many A* searches this map has run since it started. See {@link AStar#searches()}. */
    public long pathSearches() {
        return pathfinder.searches();
    }

    /** Called from network threads. The only public way to affect this map. */
    public void submit(Command command) {
        inbox.add(command);
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        log.info("Map '{}' running: {}x{} tiles, {} ms tick", map.id(), map.width(), map.height(), TICK_MS);
        spawnFixedMobs();
        long nextTickNanos = System.nanoTime();
        while (running) {
            try {
                drainCommands();
                resolvePendingPaths();
                advanceMobs();
                advanceMovement();
                reapExpiredActors();
                saveDirtyActors();
                flush();
            } catch (RuntimeException e) {
                // A single bad command must never take the whole map down with it.
                log.error("Tick {} on map '{}' failed", tick, map.id(), e);
            }

            tick++;
            nextTickNanos += TICK_MS * 1_000_000L;
            long sleep = nextTickNanos - System.nanoTime();
            if (sleep > 0) {
                LockSupport.parkNanos(sleep);
            } else {
                // Fell behind. Drop the backlog rather than spiral trying to catch
                // up - but say so, because a world that silently runs slow just
                // looks like everyone's connection got worse.
                warnAboutOverrun(-sleep);
                nextTickNanos = System.nanoTime();
            }
        }
        saveEveryone();
        log.info("Map '{}' stopped after {} ticks", map.id(), tick);
    }

    // ------------------------------------------------------------------
    // commands
    // ------------------------------------------------------------------

    private void drainCommands() {
        for (int i = 0; i < MAX_COMMANDS_PER_TICK; i++) {
            Command command = inbox.poll();
            if (command == null) {
                return;
            }
            switch (command) {
                case Command.Join join -> handleJoin(join);
                case Command.Detach detach -> handleDetach(detach);
                case Command.MoveTo move -> handleMove(move);
                case Command.Chat message -> handleChat(message);
            }
        }
    }

    private void handleJoin(Command.Join join) {
        if (byClient.containsKey(join.client())) {
            // This socket already has a character. Honouring a second hello would
            // hand it another one and strand the first with a live client, so
            // online() would stay true and the reaper would never collect it.
            log.debug("Ignoring repeated hello from {}", join.client().describe());
            return;
        }

        // Already in the world: either still standing here inside the grace
        // period, or being opened a second time. Both are the same character
        // coming back, because the handshake proved whose it is.
        Actor live = byNameKey.get(join.character().nameKey());
        if (live != null) {
            attach(live, join);
            return;
        }

        Actor actor = placeCharacter(join.accountId(), join.character());
        actor.client = join.client();
        actors.put(actor.id, actor);
        byNameKey.put(actor.nameKey, actor);
        byClient.put(join.client(), actor);
        joined.add(toDto(actor));
        sendInit(actor, join.client());
    }

    private void attach(Actor actor, Command.Join join) {
        if (actor.online() && actor.client != join.client()) {
            // The same character opened twice. The newcomer wins; the stale
            // socket goes.
            actor.client.disconnect("Session resumed elsewhere");
            byClient.remove(actor.client);
        }
        actor.client = join.client();
        byClient.put(join.client(), actor);
        presence.add(new PresenceDto(actor.id, true));
        if (!replayFrom(join.since(), join.client())) {
            sendInit(actor, join.client());
        }
    }

    /**
     * Puts a returning character back where it stood, or a new one at the spawn.
     *
     * <p>A stored position is only honoured if it is still somewhere a player
     * can stand: a map can be edited between sessions, and waking up inside a
     * wall would leave someone permanently stuck.
     */
    private Actor placeCharacter(long accountId, SavedCharacter saved) {
        boolean usable = map.id().equals(saved.mapId()) && map.walkable(saved.x(), saved.y());
        if (!usable) {
            log.info("Stored position {},{} for '{}' is not usable on '{}'; starting at the spawn",
                    saved.x(), saved.y(), saved.name(), map.id());
        }

        Actor actor = new Actor(nextActorId++, saved.name(), saved.nameKey(), accountId,
                usable ? saved.x() : map.spawnX(),
                usable ? saved.y() : map.spawnY(),
                STEP_TICKS);
        if (usable) {
            actor.dir = saved.dir();
        }
        return actor;
    }

    private void handleDetach(Command.Detach detach) {
        Actor actor = byClient.remove(detach.client());
        if (actor == null || actor.client != detach.client()) {
            return; // already replaced by a newer session
        }
        actor.client = null;
        actor.offlineSinceTick = tick;
        actor.path.clear(); // you stop where you stood; you do not keep walking unattended
        actor.pendingMove = null;
        presence.add(new PresenceDto(actor.id, false));
        persist(actor); // leaving is exactly when a position is worth keeping
    }

    private void handleMove(Command.MoveTo move) {
        Actor actor = byClient.get(move.client());
        if (actor == null) {
            return;
        }
        // Remember the request; do not search yet. Pathfinding is the most
        // expensive thing a client can ask for, and resolving once per tick caps
        // what one socket can spend no matter how fast it clicks.
        if (actor.pendingMove == null) {
            pendingMoves.add(actor);
        }
        actor.pendingMove = new int[]{move.x(), move.y()};
    }

    /** Turns at most one move request per actor per tick into an actual path. */
    private void resolvePendingPaths() {
        for (Actor actor : pendingMoves) {
            int[] target = actor.pendingMove;
            actor.pendingMove = null;
            if (target == null) {
                continue;
            }
            actor.path.clear();
            actor.path.addAll(pathfinder.findPath(actor.x, actor.y, target[0], target[1]));
            // nextStepTick is left alone on purpose: an idle actor's is already in
            // the past and steps at once, while one mid-step finishes the tile it
            // entered before turning. Re-pathing must not buy a free step.
        }
        pendingMoves.clear();
    }

    private void handleChat(Command.Chat message) {
        Actor actor = byClient.get(message.client());
        if (actor == null || message.text() == null) {
            return;
        }
        if (tick < actor.nextChatTick) {
            return;
        }
        String text = message.text().strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_CHAT_LENGTH) {
            text = text.substring(0, MAX_CHAT_LENGTH);
        }
        actor.nextChatTick = tick + CHAT_COOLDOWN_TICKS;
        chat.add(new ChatDto(actor.id, actor.name, text));
    }

    // ------------------------------------------------------------------
    // simulation
    // ------------------------------------------------------------------

    private void advanceMovement() {
        for (Actor actor : actors.values()) {
            if (actor.path.isEmpty() || tick < actor.nextStepTick) {
                continue;
            }
            int[] next = actor.path.poll();
            if (!map.walkable(next[0], next[1])) {
                actor.path.clear();
                continue;
            }
            actor.fromX = actor.x;
            actor.fromY = actor.y;
            actor.dir = Direction.between(actor.x, actor.y, next[0], next[1]);
            actor.x = next[0];
            actor.y = next[1];
            actor.nextStepTick = tick + actor.stepTicks;
            actor.dirty = !actor.isMob(); // a mob's position is never worth keeping
            moved.add(new MoveDto(actor.id, actor.fromX, actor.fromY, actor.x, actor.y,
                    actor.dir.name(), actor.stepTicks * TICK_MS));
        }
    }

    private void reapExpiredActors() {
        actors.values().removeIf(actor -> {
            // A mob has no socket, so by the player rules it looks permanently
            // disconnected and would be swept away thirty seconds after the map
            // starts. It leaves the world only when something kills it.
            if (actor.isMob() || actor.online() || tick - actor.offlineSinceTick < graceTicks) {
                return false;
            }
            byNameKey.remove(actor.nameKey);
            left.add(actor.id);
            return true;
        });
    }

    // ------------------------------------------------------------------
    // creatures
    // ------------------------------------------------------------------

    /**
     * Puts the map's marked population in place, once, before the first tick.
     *
     * <p>An empty registry means creatures are switched off deliberately - the
     * loop tests run that way so wandering mobs cannot make an assertion about
     * player movement pass by accident. A registry that merely lacks one id is a
     * different matter and says so loudly.
     */
    private void spawnFixedMobs() {
        if (mobDefs.isEmpty()) {
            log.info("Map '{}' running without creatures: no definitions supplied", map.id());
            return;
        }
        for (SpawnPoint point : map.spawns()) {
            MobDef def = mobDefs.get(point.mobId());
            if (def == null) {
                // The loader checks this, so reaching here means the two got out
                // of step - worth a loud line rather than a silent empty map.
                log.error("Map '{}' wants unknown mob '{}'", map.id(), point.mobId());
                continue;
            }
            spawn(def, point.x(), point.y());
        }
        log.info("Map '{}' spawned {} creature(s)", map.id(), actors.size());
    }

    private Actor spawn(MobDef def, int x, int y) {
        Actor mob = new Actor(nextActorId++, def, x, y);
        actors.put(mob.id, mob);
        joined.add(toDto(mob));
        return mob;
    }

    /**
     * Lets every creature decide, within one shared pathfinding budget.
     *
     * <p>The budget is the point: a mob that cannot afford a path this tick
     * wanders instead of standing still, so a crowd of chasers degrades into
     * milling about rather than stalling the map for everyone on it.
     */
    private void advanceMobs() {
        rollForRoamingElites();

        int budget = MOB_PATHS_PER_TICK;
        for (Actor actor : actors.values()) {
            if (!actor.isMob()) {
                continue;
            }
            budget -= brain.think(actor, actors.values(), tick, budget > 0);
        }
    }

    /**
     * Elites are not placed on the map; they turn up. Each entry rolls on its own
     * schedule, and only while the previous one is gone - otherwise a long enough
     * session would carpet the map in them.
     */
    private void rollForRoamingElites() {
        for (RoamingSpawn roaming : map.roaming()) {
            // The first roll waits a full interval rather than firing at tick
            // zero: an elite that is simply there when the map starts is part of
            // the furniture, not an event worth noticing.
            long due = nextRoamTick.computeIfAbsent(roaming.mobId(),
                    id -> secondsToTicks(roaming.everySeconds()));
            if (tick < due) {
                continue;
            }
            nextRoamTick.put(roaming.mobId(), tick + secondsToTicks(roaming.everySeconds()));

            if (isAlive(roaming.mobId()) || random.nextDouble() > roaming.chance()) {
                continue;
            }
            spawnRoaming(roaming);
        }
    }

    private void spawnRoaming(RoamingSpawn roaming) {
        MobDef elite = mobDefs.get(roaming.mobId());
        int[] tile = randomFreeTile();
        if (elite == null || tile == null) {
            return;
        }
        Actor spawned = spawn(elite, tile[0], tile[1]);
        log.info("Elite '{}' appeared on '{}' at {},{}", elite.name(), map.id(), tile[0], tile[1]);

        MobDef escort = roaming.escortMobId() == null ? null : mobDefs.get(roaming.escortMobId());
        if (escort == null) {
            return;
        }
        for (int i = 0; i < roaming.escortCount(); i++) {
            int[] beside = freeTileNear(spawned.x, spawned.y);
            if (beside != null) {
                spawn(escort, beside[0], beside[1]);
            }
        }
    }

    private boolean isAlive(String mobId) {
        for (Actor actor : actors.values()) {
            if (actor.isMob() && actor.mob.id().equals(mobId)) {
                return true;
            }
        }
        return false;
    }

    /** @return a walkable tile, or null if a bounded search could not find one. */
    private int[] randomFreeTile() {
        for (int attempt = 0; attempt < 64; attempt++) {
            int x = random.nextInt(map.width());
            int y = random.nextInt(map.height());
            if (map.walkable(x, y)) {
                return new int[]{x, y};
            }
        }
        return null;
    }

    private int[] freeTileNear(int x, int y) {
        for (int attempt = 0; attempt < 16; attempt++) {
            int nx = x + random.nextInt(5) - 2;
            int ny = y + random.nextInt(5) - 2;
            if (map.walkable(nx, ny)) {
                return new int[]{nx, ny};
            }
        }
        return null;
    }

    private static long secondsToTicks(int seconds) {
        return seconds * 1000L / TICK_MS;
    }

    // ------------------------------------------------------------------
    // outbound
    // ------------------------------------------------------------------

    private void flush() {
        if (joined.isEmpty() && left.isEmpty() && moved.isEmpty() && chat.isEmpty() && presence.isEmpty()) {
            return;
        }
        version++;
        Delta delta = new Delta(version, List.copyOf(joined), List.copyOf(left),
                List.copyOf(moved), List.copyOf(chat), List.copyOf(presence));
        joined.clear();
        left.clear();
        moved.clear();
        chat.clear();
        presence.clear();

        history.addLast(delta);
        while (history.size() > HISTORY_TICKS) {
            history.removeFirst();
        }

        String frame = serialise(delta);
        if (frame == null) {
            return;
        }
        for (Actor actor : actors.values()) {
            if (actor.client != null) {
                actor.client.send(frame);
            }
        }
    }

    private void sendInit(Actor self, Client client) {
        List<ActorDto> snapshot = new ArrayList<>(actors.size());
        for (Actor actor : actors.values()) {
            snapshot.add(toDto(actor));
        }
        String frame = serialise(new ServerMessages.Init(version, mapDto, self.id, snapshot));
        if (frame != null) {
            client.send(frame);
        }
    }

    /**
     * @return true when the whole gap since {@code since} was replayed, false
     *         when the client has been away too long and needs a full init.
     */
    private boolean replayFrom(long since, Client client) {
        if (since <= 0 || since > version) {
            return false;
        }
        if (since < version && (history.isEmpty() || history.peekFirst().v() > since + 1)) {
            return false;
        }
        for (Delta delta : history) {
            if (delta.v() > since) {
                String frame = serialise(delta);
                if (frame == null) {
                    return false;
                }
                client.send(frame);
            }
        }
        return true;
    }

    private String serialise(Object message) {
        try {
            return json.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("Could not serialise {} on map '{}'", message.getClass().getSimpleName(), map.id(), e);
            return null;
        }
    }

    /** Rate-limited so a sustained overload logs steadily instead of flooding. */
    private void warnAboutOverrun(long overrunNanos) {
        if (tick - lastOverrunWarningTick < OVERRUN_WARNING_INTERVAL_TICKS) {
            return;
        }
        lastOverrunWarningTick = tick;
        log.warn("Map '{}' missed its {} ms tick by {} ms - the world is running slow",
                map.id(), TICK_MS, overrunNanos / 1_000_000L);
    }

    private void saveDirtyActors() {
        if (tick % SAVE_INTERVAL_TICKS != 0) {
            return;
        }
        for (Actor actor : actors.values()) {
            persist(actor);
        }
    }

    private void saveEveryone() {
        for (Actor actor : actors.values()) {
            actor.dirty = true;
            persist(actor);
        }
    }

    private void persist(Actor actor) {
        // Creatures come from the map definition, so they respawn by themselves
        // after a restart. Saving one would also mean writing a row with a
        // foreign key to an account it does not have.
        if (actor.isMob() || !actor.dirty) {
            return;
        }
        actor.dirty = false;
        // A copy, never the live actor: anything handed across threads must not
        // be something the tick is still writing to.
        persistence.save(new ActorSnapshot(actor.nameKey, actor.name, map.id(),
                actor.x, actor.y, actor.dir.name()));
    }

    private ActorDto toDto(Actor actor) {
        return new ActorDto(actor.id, actor.name, actor.x, actor.y, actor.dir.name(),
                actor.isMob() || actor.online(),
                actor.kind.name(),
                actor.isMob() ? actor.mob.tier().name() : null);
    }
}
