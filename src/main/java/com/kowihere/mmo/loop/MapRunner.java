package com.kowihere.mmo.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.CombatRules;
import com.kowihere.mmo.combat.Fight;
import com.kowihere.mmo.path.AStar;
import com.kowihere.mmo.protocol.ServerMessages;
import com.kowihere.mmo.protocol.ServerMessages.ActorDto;
import com.kowihere.mmo.protocol.ServerMessages.ChatDto;
import com.kowihere.mmo.protocol.ServerMessages.DamageDto;
import com.kowihere.mmo.protocol.ServerMessages.FightDto;
import com.kowihere.mmo.protocol.ServerMessages.Delta;
import com.kowihere.mmo.protocol.ServerMessages.MapDto;
import com.kowihere.mmo.protocol.ServerMessages.MoveDto;
import com.kowihere.mmo.protocol.ServerMessages.PresenceDto;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemSlot;
import com.kowihere.mmo.world.LootEntry;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
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
    /** How far from the player spawn a roaming pack has to keep, in tiles. */
    private static final int SPAWN_CLEARANCE = 8;
    /** 1.5 s between rounds: slow enough to read, fast enough not to be a wait. */
    private static final int ROUND_TICKS = 15;
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
    private final Content content;
    private final Map<String, MobDef> mobDefs;
    private final MobBehaviour brain;
    private final Random random;
    /** Characters owed an own-state frame once this tick's delta has gone out. */
    private final Set<Integer> pendingYou = new LinkedHashSet<>();
    /** The same, for what they are carrying. */
    private final Set<Integer> pendingBag = new LinkedHashSet<>();
    private final Map<String, Long> nextRoamTick = new HashMap<>();
    private final List<Fight> fights = new ArrayList<>();
    private final List<PendingRespawn> respawning = new ArrayList<>();
    private final CombatRules combat;
    private final int graceTicks;
    private final Map<Client, Actor> byClient = new IdentityHashMap<>();
    private final Deque<Delta> history = new ArrayDeque<>();

    private final List<ActorDto> joined = new ArrayList<>();
    private final List<Integer> left = new ArrayList<>();
    private final List<MoveDto> moved = new ArrayList<>();
    private final List<ChatDto> chat = new ArrayList<>();
    private final List<PresenceDto> presence = new ArrayList<>();
    private final List<DamageDto> damage = new ArrayList<>();
    private final List<Integer> died = new ArrayList<>();
    private final List<FightDto> fightChanges = new ArrayList<>();
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
        this(map, json, persistence, Content.EMPTY);
    }

    public MapRunner(MapDef map, ObjectMapper json, WorldPersistence persistence, Content content) {
        this(map, json, persistence, content, DEFAULT_GRACE_TICKS);
    }

    /**
     * @param graceTicks how long a character stays standing in the world after
     *                   its socket drops. Tunable mostly so tests need not wait
     *                   out the real half-minute.
     */
    public MapRunner(MapDef map, ObjectMapper json, WorldPersistence persistence,
                     Content content, int graceTicks) {
        this(map, json, persistence, content, graceTicks, new Random());
    }

    /**
     * @param random every roll this map makes - damage swings, escapes, what a
     *               creature leaves behind, where it wanders. Handed in rather
     *               than made here so a test can decide an outcome instead of
     *               running the same fight a hundred times and hoping to see the
     *               case it is after.
     */
    MapRunner(MapDef map, ObjectMapper json, WorldPersistence persistence,
              Content content, int graceTicks, Random random) {
        this.random = random;
        this.graceTicks = graceTicks;
        this.map = map;
        this.json = json;
        this.persistence = persistence;
        this.content = content;
        this.mobDefs = content.mobs();
        this.pathfinder = new AStar(map);
        // The same pathfinder players use, deliberately: both run on this thread,
        // and sharing it makes pathSearches() the map's true total rather than
        // half of it.
        this.brain = new MobBehaviour(map, pathfinder, random, this::startFight);
        this.combat = new CombatRules(random);
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
                engageApproachingTargets();
                resolveFights();
                respawnTheFallen();
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
                case Command.Attack attack -> handleAttack(attack);
                case Command.Flee flee -> handleFlee(flee);
                case Command.Equip equip -> handleEquip(equip);
                case Command.Unequip unequip -> handleUnequip(unequip);
                case Command.Spend spend -> handleSpend(spend);
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
        sendYou(actor);
        sendBag(actor);
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
        sendYou(actor);
        sendBag(actor);
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
        actor.level = Math.max(1, saved.level());
        actor.xp = Math.max(0, saved.xp());
        actor.weakenedUntil = saved.weakenedUntil();
        actor.attributes = saved.attributes();
        actor.unspentPoints = Math.max(0, saved.unspentPoints());
        actor.inventory.restore(saved.items(), content.items());
        // -1 means "as healthy as this level allows", which is how a new
        // character and every row predating combat is stored.
        actor.hp = saved.hp() < 0 ? actor.maxHp() : Math.min(saved.hp(), actor.maxHp());
        if (actor.hp <= 0) {
            actor.hp = actor.maxHp(); // never let someone log in already dead
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
        if (actor.inFight()) {
            // A fight holds you where you stand. Without this the whole model
            // collapses: you would simply walk away from every losing round.
            return;
        }
        actor.approaching = 0;
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

    private void handleAttack(Command.Attack attack) {
        Actor actor = byClient.get(attack.client());
        Actor target = actors.get(attack.targetId());
        if (actor == null || target == null || actor.inFight() || !target.isAlive()) {
            return;
        }
        if (!target.isMob()) {
            sendError(actor, "Na razie można walczyć tylko z potworami.");
            return;
        }
        // The server walks you there. Making the player line themselves up first
        // would be an interface chore pretending to be a rule of the game.
        actor.approaching = target.id;
        actor.pendingMove = new int[]{target.x, target.y};
        if (!pendingMoves.contains(actor)) {
            pendingMoves.add(actor);
        }
    }

    private void handleFlee(Command.Flee flee) {
        Actor actor = byClient.get(flee.client());
        if (actor != null && actor.inFight()) {
            actor.fight.wantsToFlee(actor.id);
        }
    }

    /**
     * Puts something on.
     *
     * <p>Every refusal below is the server's, not the interface's. A client that
     * never draws an unwearable item is a convenience; a client that cannot be
     * made to send one anyway does not exist.
     */
    private void handleEquip(Command.Equip equip) {
        Actor actor = byClient.get(equip.client());
        if (actor == null) {
            return;
        }
        if (actor.inFight()) {
            sendError(actor, "W walce nie ma czasu na przebieranie się.");
            return;
        }
        ItemStack stack = actor.inventory.inBag(equip.itemId());
        if (stack == null) {
            return; // nothing of that name in the bag; nothing to say about it
        }
        if (actor.level < stack.def().requiresLevel()) {
            sendError(actor, stack.def().name() + " wymaga poziomu "
                    + stack.def().requiresLevel() + ".");
            return;
        }

        int healthBefore = actor.maxHp();
        actor.inventory.wear(stack);
        keepHealthInRange(actor, healthBefore);
        itemsChanged(actor);
    }

    private void handleUnequip(Command.Unequip unequip) {
        Actor actor = byClient.get(unequip.client());
        if (actor == null || unequip.slot() == null) {
            return;
        }
        if (actor.inFight()) {
            sendError(actor, "W walce nie ma czasu na przebieranie się.");
            return;
        }
        if (actor.inventory.wornIn(unequip.slot()) == null) {
            return;
        }
        if (actor.inventory.isFull()) {
            // Refused rather than dropped: an item that disappears because a bag
            // was full is a bug report nobody can reproduce.
            sendError(actor, "Nie ma gdzie tego schować - plecak jest pełny.");
            return;
        }

        int healthBefore = actor.maxHp();
        actor.inventory.takeOff(unequip.slot());
        keepHealthInRange(actor, healthBefore);
        itemsChanged(actor);
    }

    private void handleSpend(Command.Spend spend) {
        Actor actor = byClient.get(spend.client());
        if (actor == null || spend.attribute() == null) {
            return;
        }
        if (actor.inFight()) {
            sendError(actor, "Punkty rozdasz po walce.");
            return;
        }
        if (actor.unspentPoints <= 0) {
            sendError(actor, "Nie masz punktów do rozdania.");
            return;
        }

        int healthBefore = actor.maxHp();
        actor.unspentPoints--;
        actor.attributes = actor.attributes.plus(spend.attribute(), 1);
        // A point in strength is worth health immediately - the alternative is
        // spending a point and seeing nothing happen until the next fight.
        actor.hp += Math.max(0, actor.maxHp() - healthBefore);
        actor.dirty = true;
        sendYou(actor);
    }

    /**
     * Keeps current health sane when the maximum moves under it.
     *
     * <p>Taking off the armour that was holding your health up should not kill
     * you, and putting it back on should not heal you.
     */
    private static void keepHealthInRange(Actor actor, int healthBefore) {
        int now = actor.maxHp();
        if (now < healthBefore) {
            actor.hp = Math.max(1, Math.min(actor.hp, now));
        }
    }

    private void itemsChanged(Actor actor) {
        actor.dirty = true;
        actor.itemsDirty = true;
        sendYou(actor);
        sendBag(actor);
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
    // combat
    // ------------------------------------------------------------------

    /** A creature waiting to be put back where it was killed. */
    private record PendingRespawn(MobDef def, int x, int y, long atTick) {
    }

    /** Anyone who walked to a target and has arrived starts swinging. */
    private void engageApproachingTargets() {
        for (Actor actor : actors.values()) {
            if (actor.approaching == 0 || actor.inFight()) {
                continue;
            }
            Actor target = actors.get(actor.approaching);
            if (target == null || !target.isAlive()) {
                actor.approaching = 0;
                continue;
            }
            if (adjacent(actor, target)) {
                actor.approaching = 0;
                startFight(actor, target);
            }
        }
    }

    /**
     * Puts two actors into a fight, or draws one into a fight already happening.
     * A creature that wanders in joins rather than opening a second fight, so a
     * player never ends up in two at once.
     */
    void startFight(Actor player, Actor mob) {
        if (player.isMob() == mob.isMob()) {
            return; // PvE only, for now
        }
        Actor person = player.isMob() ? mob : player;
        Actor creature = player.isMob() ? player : mob;

        if (person.fight != null) {
            person.fight.addMob(creature.id);
            creature.fight = person.fight;
        } else if (creature.fight != null) {
            creature.fight.addPlayer(person.id);
            person.fight = creature.fight;
        } else {
            Fight fight = new Fight(person.id, creature.id, tick + ROUND_TICKS);
            fights.add(fight);
            person.fight = fight;
            creature.fight = fight;
        }

        person.path.clear();
        creature.path.clear();
        fightChanges.add(new FightDto(person.id, true));
        fightChanges.add(new FightDto(creature.id, true));
        sendYou(person);
    }

    private void resolveFights() {
        for (Fight fight : new ArrayList<>(fights)) {
            if (fight.isRoundDue(tick)) {
                fight.scheduleNextRound(tick + ROUND_TICKS);
                resolveRound(fight);
            }
        }
    }

    private void resolveRound(Fight fight) {
        // Anyone trying to leave settles that first: succeeding means no blow is
        // struck at them this round, failing means they lose their own.
        for (int playerId : new ArrayList<>(fight.players())) {
            if (!fight.isFleeing(playerId)) {
                continue;
            }
            Actor runner = actors.get(playerId);
            if (runner == null) {
                continue;
            }
            // The flag stays up until the round is over: clearing it here would
            // make a failed escape free, and the loop below would let the same
            // actor swing as though it had never tried to run.
            if (combat.escapes()) {
                leaveFight(runner, fight);
                chat.add(new ChatDto(runner.id, runner.name, "* ucieka z walki *"));
            }
        }

        for (int actorId : fight.everyone()) {
            Actor attacker = actors.get(actorId);
            if (attacker == null || !attacker.isAlive() || !fight.has(actorId)) {
                continue;
            }
            if (fight.players().contains(actorId) && fight.isFleeing(actorId)) {
                continue; // spent the round trying to get away
            }
            Actor target = pickTarget(fight, attacker);
            if (target == null) {
                continue;
            }
            strike(attacker, target, fight);
        }

        fight.clearFleeRequests();
        endIfOver(fight);
    }

    private Actor pickTarget(Fight fight, Actor attacker) {
        List<Integer> opponents = attacker.isMob() ? fight.players() : fight.mobs();
        for (int id : opponents) {
            Actor candidate = actors.get(id);
            if (candidate != null && candidate.isAlive()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * One participant's turn: a blow, and sometimes a second one.
     *
     * <p>Agility buys the second. It is not attack speed - the round is still
     * the round, and everybody still gets one turn in it - but it is the part of
     * attack speed that fits a turn: sometimes you get two in.
     */
    private void strike(Actor attacker, Actor target, Fight fight) {
        if (!swing(attacker, target, fight)) {
            return; // the target is down; nothing left to hit
        }
        if (combat.landsSecondBlow(attacker.secondBlowChance())) {
            swing(attacker, target, fight);
        }
    }

    /** @return true if the target is still standing and can be hit again */
    private boolean swing(Actor attacker, Actor target, Fight fight) {
        if (combat.dodges(target.dodgeChance())) {
            // Reported as a blow for zero, so the client can say "0" where it
            // would have said a number. A miss that shows nothing at all looks
            // like the server stopped answering.
            damage.add(new DamageDto(attacker.id, target.id, 0, target.hp));
            return true;
        }

        int dealt = combat.damage(attacker.attack(), target.armor());
        target.hp = Math.max(0, target.hp - dealt);
        damage.add(new DamageDto(attacker.id, target.id, dealt, target.hp));

        if (!target.isMob()) {
            sendYou(target);
        }
        if (target.isAlive()) {
            return true;
        }
        if (target.isMob()) {
            killCreature(target, attacker, fight);
        } else {
            killPlayer(target, fight);
        }
        return false;
    }

    private void killCreature(Actor creature, Actor killer, Fight fight) {
        died.add(creature.id);
        fight.remove(creature.id);
        creature.fight = null;
        actors.remove(creature.id);

        if (creature.respawns) {
            respawning.add(new PendingRespawn(creature.mob, creature.homeX, creature.homeY,
                    tick + secondsToTicks(creature.mob.respawnSeconds())));
        }

        if (killer != null && !killer.isMob()) {
            awardExperience(killer, creature);
            awardLoot(killer, creature);
        }
    }

    /**
     * What a creature leaves behind, rolled once per entry.
     *
     * <p>Straight into the killer's bag. Loot lying on a tile is its own feature
     * - who may pick it up, how long it waits, how it is drawn - and none of
     * those questions have anything to do with whether items work.
     */
    private void awardLoot(Actor killer, Actor creature) {
        for (LootEntry entry : creature.mob.loot()) {
            if (!combat.rolls(entry.chance())) {
                continue;
            }
            ItemDef def = content.items().get(entry.itemId());
            if (def == null) {
                continue; // the loader refuses this at startup; belt and braces
            }
            if (!killer.inventory.add(ItemStack.of(def))) {
                sendError(killer, "Plecak jest pełny - " + def.name() + " przepadł.");
                return;
            }
            chat.add(new ChatDto(killer.id, killer.name, "* znajduje: " + def.name() + " *"));
            killer.dirty = true;
            killer.itemsDirty = true;
        }
        if (killer.itemsDirty) {
            sendBag(killer);
        }
    }

    private void killPlayer(Actor player, Fight fight) {
        died.add(player.id);
        leaveFight(player, fight);

        player.x = map.spawnX();
        player.y = map.spawnY();
        player.fromX = player.x;
        player.fromY = player.y;
        player.path.clear();
        player.hp = player.maxHp();
        player.weakenedUntil = System.currentTimeMillis() + CombatRules.WEAKENED_SECONDS * 1000L;
        player.dirty = true;

        // Everyone needs to see them vanish from where they fell and reappear at
        // the spawn; a plain move would have them walk the whole way back.
        joined.add(toDto(player));
        sendYou(player);
        chat.add(new ChatDto(player.id, player.name, "* ginie *"));
    }

    private void awardExperience(Actor player, Actor creature) {
        long reward = CombatRules.xpReward(creature.mob);
        int before = player.level;
        player.xp += reward;
        player.level = CombatRules.levelForXp(player.xp);
        if (player.level > before) {
            // Points, not statistics. A level is worth what the player decides
            // it is worth, which is the whole reason attributes exist.
            player.unspentPoints += Attributes.POINTS_PER_LEVEL * (player.level - before);
            player.hp = player.maxHp(); // a level is worth a full recovery
            chat.add(new ChatDto(player.id, player.name, "* osiąga poziom " + player.level + " *"));
        }
        player.dirty = true;
        sendYou(player);
    }

    private void leaveFight(Actor actor, Fight fight) {
        fight.remove(actor.id);
        actor.fight = null;
        actor.approaching = 0;
        fightChanges.add(new FightDto(actor.id, false));
        endIfOver(fight);
    }

    private void endIfOver(Fight fight) {
        if (!fight.isOver()) {
            return;
        }
        for (int id : fight.everyone()) {
            Actor actor = actors.get(id);
            if (actor != null) {
                actor.fight = null;
                actor.approaching = 0;
                fightChanges.add(new FightDto(id, false));
            }
        }
        fights.remove(fight);
    }

    /** Puts killed creatures back at their posts once their timer runs out. */
    private void respawnTheFallen() {
        respawning.removeIf(pending -> {
            if (tick < pending.atTick()) {
                return false;
            }
            spawn(pending.def(), pending.x(), pending.y(), true);
            return true;
        });
    }

    private static boolean adjacent(Actor a, Actor b) {
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y)) <= 1;
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
            spawn(def, point.x(), point.y(), true);
        }
        log.info("Map '{}' spawned {} creature(s)", map.id(), actors.size());
    }

    /**
     * @param respawns whether killing it should put another one here later. A
     *                 creature at a marked post comes back; an elite that simply
     *                 turned up does not - the next one arrives by its own roll.
     */
    private Actor spawn(MobDef def, int x, int y, boolean respawns) {
        Actor mob = new Actor(nextActorId++, def, x, y, respawns);
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
        Actor spawned = spawn(elite, tile[0], tile[1], false);
        log.info("Elite '{}' appeared on '{}' at {},{}", elite.name(), map.id(), tile[0], tile[1]);

        MobDef escort = roaming.escortMobId() == null ? null : mobDefs.get(roaming.escortMobId());
        if (escort == null) {
            return;
        }
        // Only the shortfall. An escort that survived the last elite is still a
        // wolf on this map, and counting it is the difference between a pack of
        // a fixed size and a map that grows two wolves every appearance for as
        // long as the server is up.
        int living = countEscorts(escort.id());
        for (int i = living; i < roaming.escortCount(); i++) {
            int[] beside = freeTileNear(spawned.x, spawned.y);
            if (beside != null) {
                spawn(escort, beside[0], beside[1], false).escort = true;
            }
        }
    }

    private int countEscorts(String mobId) {
        int count = 0;
        for (Actor actor : actors.values()) {
            if (actor.escort && actor.isMob() && actor.mob.id().equals(mobId)) {
                count++;
            }
        }
        return count;
    }

    private boolean isAlive(String mobId) {
        for (Actor actor : actors.values()) {
            if (actor.isMob() && actor.mob.id().equals(mobId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A walkable tile well away from where players arrive, or null if a bounded
     * search could not find one.
     *
     * <p>The clearance is not decoration: an elite and its pack placed on the
     * spawn tile means every character that logs in is attacked before it can
     * take a step, and a character that dies there is attacked again the moment
     * it comes back.
     */
    private int[] randomFreeTile() {
        // Two passes, because the clearance is a courtesy and not an invariant:
        // a map smaller than the clearance has no such tile to offer, and
        // refusing to place anything at all would be the worse answer.
        for (int pass = 0; pass < 2; pass++) {
            int clearance = pass == 0 ? clearanceFor(map) : 0;
            for (int attempt = 0; attempt < 64; attempt++) {
                int x = random.nextInt(map.width());
                int y = random.nextInt(map.height());
                if (map.walkable(x, y) && distance(x, y, map.spawnX(), map.spawnY()) >= clearance) {
                    return new int[]{x, y};
                }
            }
        }
        return null;
    }

    private static int distance(int x, int y, int toX, int toY) {
        return Math.abs(x - toX) + Math.abs(y - toY);
    }

    /**
     * A walkable tile within two steps of the given one.
     *
     * <p>No clearance check of its own: an escort is placed beside an elite that
     * already keeps its distance, so it inherits all but those two steps. Making
     * the escort keep the full clearance as well leaves packs short-handed on a
     * small map, where few tiles satisfy both conditions at once.
     */
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

    /** Zero on a map too small to offer the clearance, as in randomFreeTile. */
    private static int clearanceFor(MapDef map) {
        return map.width() + map.height() > SPAWN_CLEARANCE * 2 ? SPAWN_CLEARANCE : 0;
    }

    private static long secondsToTicks(int seconds) {
        return seconds * 1000L / TICK_MS;
    }

    // ------------------------------------------------------------------
    // outbound
    // ------------------------------------------------------------------

    private void flush() {
        if (joined.isEmpty() && left.isEmpty() && moved.isEmpty() && chat.isEmpty()
                && presence.isEmpty() && damage.isEmpty() && died.isEmpty() && fightChanges.isEmpty()) {
            flushPrivateFrames(); // a level-up, or a bag change, on an otherwise still tick
            return;
        }
        version++;
        Delta delta = new Delta(version, List.copyOf(joined), List.copyOf(left),
                List.copyOf(moved), List.copyOf(chat), List.copyOf(presence),
                List.copyOf(damage), List.copyOf(died), List.copyOf(fightChanges));
        joined.clear();
        left.clear();
        moved.clear();
        chat.clear();
        presence.clear();
        damage.clear();
        died.clear();
        fightChanges.clear();

        history.addLast(delta);
        while (history.size() > HISTORY_TICKS) {
            history.removeFirst();
        }

        String frame = serialise(delta);
        if (frame == null) {
            flushPrivateFrames();
            return;
        }
        for (Actor actor : actors.values()) {
            if (actor.client != null) {
                actor.client.send(frame);
            }
        }
        flushPrivateFrames();
    }

    /** Own-character frames, after the delta they belong to and never before it. */
    private void flushPrivateFrames() {
        for (int id : pendingYou) {
            Actor actor = actors.get(id);
            if (actor != null) {
                sendYouNow(actor);
            }
        }
        pendingYou.clear();
        for (int id : pendingBag) {
            Actor actor = actors.get(id);
            if (actor != null) {
                sendBagNow(actor);
            }
        }
        pendingBag.clear();
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
        // Items only when they moved. Null here means "leave those rows alone",
        // which is every save made by somebody simply walking across a map.
        List<StoredItem> items = actor.itemsDirty ? actor.inventory.stored() : null;
        actor.itemsDirty = false;
        // A copy, never the live actor: anything handed across threads must not
        // be something the tick is still writing to.
        persistence.save(new ActorSnapshot(actor.nameKey, actor.name, map.id(),
                actor.x, actor.y, actor.dir.name(),
                actor.level, actor.xp, actor.hp, actor.weakenedUntil,
                actor.attributes, actor.unspentPoints, items));
    }

    private ActorDto toDto(Actor actor) {
        return new ActorDto(actor.id, actor.name, actor.x, actor.y, actor.dir.name(),
                actor.isMob() || actor.online(),
                actor.kind.name(),
                actor.isMob() ? actor.mob.tier().name() : null,
                actor.level, actor.hp, actor.maxHp(), actor.inFight());
    }

    private void sendError(Actor actor, String message) {
        if (actor.client != null) {
            String frame = serialise(new ServerMessages.Error(message));
            if (frame != null) {
                actor.client.send(frame);
            }
        }
    }

    /**
     * The player's own state, to that player alone.
     *
     * <p>Experience deliberately never travels in a delta: deltas go to everyone
     * on the map, and one player's progress is nobody else's business.
     */
    private void sendYou(Actor actor) {
        if (actor.isMob() || actor.client == null) {
            return;
        }
        // Queued rather than sent. A "you" frame written mid-tick overtakes the
        // delta that explains it, so a client learns it is dead and weakened
        // while its character is still standing where it fell.
        pendingYou.add(actor.id);
    }

    private void sendYouNow(Actor actor) {
        if (actor.isMob() || actor.client == null) {
            return;
        }
        long floor = CombatRules.xpForLevel(actor.level);
        long ceiling = CombatRules.xpForLevel(actor.level + 1);
        Attributes total = actor.totalAttributes();
        String frame = serialise(new ServerMessages.You(
                actor.hp, actor.maxHp(),
                // Mana has no spender yet. It is here because intellect has to
                // be worth something a player can see, and because a bar added
                // later would mean a protocol change for a feature that was
                // always going to need it.
                actor.maxMana(), actor.maxMana(),
                actor.level, actor.xp, actor.xp - floor, ceiling - floor,
                actor.weakenedUntil, !actor.isAlive(),
                total.strength(), total.agility(), total.intellect(), actor.unspentPoints,
                actor.attack(), actor.armor(),
                (int) Math.round(actor.dodgeChance() * 100),
                (int) Math.round(actor.secondBlowChance() * 100)));
        if (frame != null) {
            actor.client.send(frame);
        }
    }

    /** Queued like {@code you}, and for the same reason: after the delta. */
    private void sendBag(Actor actor) {
        if (actor.isMob() || actor.client == null) {
            return;
        }
        pendingBag.add(actor.id);
    }

    private void sendBagNow(Actor actor) {
        if (actor.isMob() || actor.client == null) {
            return;
        }
        List<ServerMessages.ItemDto> carried = new ArrayList<>();
        for (ItemStack stack : actor.inventory.bag()) {
            carried.add(toDto(stack, actor));
        }
        List<ServerMessages.ItemDto> worn = new ArrayList<>();
        for (Map.Entry<ItemSlot, ItemStack> entry : actor.inventory.worn().entrySet()) {
            worn.add(toDto(entry.getValue(), actor));
        }
        String frame = serialise(new ServerMessages.Bag(Inventory.CAPACITY, carried, worn));
        if (frame != null) {
            actor.client.send(frame);
        }
    }

    private static ServerMessages.ItemDto toDto(ItemStack stack, Actor owner) {
        ItemDef def = stack.def();
        return new ServerMessages.ItemDto(stack.id(), def.id(), def.name(), def.slot().name(),
                def.requiresLevel(), owner.level >= def.requiresLevel(),
                def.bonuses().strength(), def.bonuses().agility(), def.bonuses().intellect(),
                def.attack(), def.armor());
    }
}
