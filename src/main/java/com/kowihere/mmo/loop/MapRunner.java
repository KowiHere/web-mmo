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
import java.util.UUID;
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
    /** How long a disconnected actor stays in the world before being removed. */
    private static final int GRACE_TICKS = 300;
    /** How many past deltas are kept for reconnect replay. */
    private static final int HISTORY_TICKS = 300;
    private static final int CHAT_COOLDOWN_TICKS = 5;
    private static final int MAX_CHAT_LENGTH = 200;
    private static final int MAX_COMMANDS_PER_TICK = 4_096;

    private final MapDef map;
    private final ObjectMapper json;
    private final AStar pathfinder;
    private final Queue<Command> inbox = new ConcurrentLinkedQueue<>();

    // ---- owned exclusively by the map thread from here down ----
    private final Map<Integer, Actor> actors = new HashMap<>();
    private final Map<String, Actor> byToken = new HashMap<>();
    private final Map<Client, Actor> byClient = new IdentityHashMap<>();
    private final Deque<Delta> history = new ArrayDeque<>();

    private final List<ActorDto> joined = new ArrayList<>();
    private final List<Integer> left = new ArrayList<>();
    private final List<MoveDto> moved = new ArrayList<>();
    private final List<ChatDto> chat = new ArrayList<>();
    private final List<PresenceDto> presence = new ArrayList<>();

    private long version;
    private long tick;
    private int nextActorId = 1;
    private volatile boolean running = true;

    public MapRunner(MapDef map, ObjectMapper json) {
        this.map = map;
        this.json = json;
        this.pathfinder = new AStar(map);
    }

    public String mapId() {
        return map.id();
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
        long nextTickNanos = System.nanoTime();
        while (running) {
            try {
                drainCommands();
                advanceMovement();
                reapExpiredActors();
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
                // Fell behind; drop the backlog rather than spiral trying to catch up.
                nextTickNanos = System.nanoTime();
            }
        }
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
        Actor existing = join.token() == null ? null : byToken.get(join.token());
        if (existing != null && existing.online()) {
            // Same character opened twice. The newcomer wins; the stale socket goes.
            existing.client.disconnect("Session resumed elsewhere");
            byClient.remove(existing.client);
            existing.client = null;
        }

        if (existing != null) {
            existing.client = join.client();
            byClient.put(join.client(), existing);
            presence.add(new PresenceDto(existing.id, true));
            if (!replayFrom(join.since(), join.client())) {
                sendInit(existing, join.client());
            }
            return;
        }

        Actor actor = new Actor(nextActorId++, sanitiseName(join.name()),
                UUID.randomUUID().toString(), map.spawnX(), map.spawnY());
        actor.client = join.client();
        actors.put(actor.id, actor);
        byToken.put(actor.token, actor);
        byClient.put(join.client(), actor);
        joined.add(toDto(actor));
        sendInit(actor, join.client());
    }

    private void handleDetach(Command.Detach detach) {
        Actor actor = byClient.remove(detach.client());
        if (actor == null || actor.client != detach.client()) {
            return; // already replaced by a newer session
        }
        actor.client = null;
        actor.offlineSinceTick = tick;
        actor.path.clear(); // you stop where you stood; you do not keep walking unattended
        presence.add(new PresenceDto(actor.id, false));
    }

    private void handleMove(Command.MoveTo move) {
        Actor actor = byClient.get(move.client());
        if (actor == null) {
            return;
        }
        Deque<int[]> path = pathfinder.findPath(actor.x, actor.y, move.x(), move.y());
        actor.path.clear();
        actor.path.addAll(path);
        // nextStepTick is left alone on purpose: an idle actor's is already in the
        // past and steps at once, while one mid-step finishes the tile it entered
        // before turning. Re-pathing must not let a click buy a free step.
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
            actor.nextStepTick = tick + STEP_TICKS;
            moved.add(new MoveDto(actor.id, actor.fromX, actor.fromY, actor.x, actor.y,
                    actor.dir.name(), STEP_TICKS * TICK_MS));
        }
    }

    private void reapExpiredActors() {
        actors.values().removeIf(actor -> {
            if (actor.online() || tick - actor.offlineSinceTick < GRACE_TICKS) {
                return false;
            }
            byToken.remove(actor.token);
            left.add(actor.id);
            return true;
        });
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
        MapDto mapDto = new MapDto(map.id(), map.name(), map.width(), map.height(),
                map.tileSize(), map.collisionRows());
        String frame = serialise(new ServerMessages.Init(version, mapDto, self.id, self.token, snapshot));
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

    private ActorDto toDto(Actor actor) {
        return new ActorDto(actor.id, actor.name, actor.x, actor.y, actor.dir.name(), actor.online());
    }

    private static String sanitiseName(String raw) {
        if (raw == null) {
            return "Wanderer";
        }
        String cleaned = raw.strip().replaceAll("[^\\p{L}\\p{N} _-]", "");
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 16);
        }
        return cleaned.isBlank() ? "Wanderer" : cleaned;
    }
}
