package com.kowihere.mmo.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.CombatRules;
import com.kowihere.mmo.combat.Energy;
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
import com.kowihere.mmo.world.ClassDef;
import com.kowihere.mmo.world.Content;
import com.kowihere.mmo.world.ItemDef;
import com.kowihere.mmo.world.ItemSlot;
import com.kowihere.mmo.world.LootEntry;
import com.kowihere.mmo.world.DialogueAction;
import com.kowihere.mmo.combat.SkillPrices;
import com.kowihere.mmo.world.Door;
import com.kowihere.mmo.world.CoinDrop;
import com.kowihere.mmo.world.CurrencyDef;
import com.kowihere.mmo.world.Dialogue;
import com.kowihere.mmo.world.DialogueNode;
import com.kowihere.mmo.world.DialogueOption;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.NpcFunction;
import com.kowihere.mmo.world.Shop;
import com.kowihere.mmo.world.NpcPlacement;
import com.kowihere.mmo.world.SkillDef;
import com.kowihere.mmo.world.SkillDefLoader;
import com.kowihere.mmo.world.RespawnPoint;
import com.kowihere.mmo.world.RoamingSpawn;
import com.kowihere.mmo.world.SpawnPoint;
import com.kowihere.mmo.world.Vault;
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
    /** And for what they have learned. */
    private final Set<Integer> pendingSkills = new LinkedHashSet<>();
    /** And for what they have to spend, which a kill changes. */
    private final Set<Integer> pendingPurse = new LinkedHashSet<>();
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
    /** Characters on their way to another map, handed over at the end of the tick. */
    private final List<Leaving> leaving = new ArrayList<>();

    /**
     * How this map hands somebody to another one.
     *
     * <p>Set once, before the thread starts, rather than taken in a constructor:
     * the world has to build every map before any of them can be told about the
     * others. After that it is read only by this thread.
     */
    private MapTransfers transfers = MapTransfers.NOWHERE;

    private record Leaving(Actor actor, String toMapId, int toX, int toY) {
    }

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
        List<ServerMessages.DoorDto> doors = new ArrayList<>();
        for (Door door : map.doors()) {
            // Where it is, what is on the other side, and what it burns - all
            // properties of the door. Nothing about whether this particular
            // player may use it: one MapDto is shared by everybody on the map,
            // and a threshold is about the one asking.
            doors.add(new ServerMessages.DoorDto(door.x(), door.y(), door.name(),
                    door.takesSomething() ? nameOf(door.consumesItem()) : null));
        }
        this.mapDto = new MapDto(map.id(), map.name(), map.width(), map.height(),
                map.tileSize(), map.collisionRows(), List.copyOf(doors));
    }

    /** Told once, at startup, before this map is running. */
    public void transfersThrough(MapTransfers transfers) {
        this.transfers = transfers;
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
        placeNpcs();
        long nextTickNanos = System.nanoTime();
        while (running) {
            try {
                drainCommands();
                resolvePendingPaths();
                advanceMobs();
                advanceMovement();
                engageApproachingTargets();
                endConversationsOutOfEarshot();
                resolveFights();
                respawnTheFallen();
                wakeTheSleeping();
                reapExpiredActors();
                carryOutTransfers();
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
            if (refusedWhileOut(command)) {
                continue;
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
                case Command.Use use -> handleUse(use);
                case Command.Learn learn -> handleLearn(learn);
                case Command.Talk talk -> handleTalk(talk);
                case Command.Choose choose -> handleChoose(choose);
                case Command.StopTalking stop -> handleStopTalking(stop);
                case Command.Buy buy -> handleBuy(buy);
                case Command.Sell sell -> handleSell(sell);
                case Command.Deposit deposit -> handleDeposit(deposit);
                case Command.Withdraw withdraw -> handleWithdraw(withdraw);
                case Command.DepositCoins put -> handleDepositCoins(put);
                case Command.WithdrawCoins take -> handleWithdrawCoins(take);
                case Command.BuyTab buy -> handleBuyTab(buy);
                case Command.Pass pass -> handlePass(pass);
            }
        }
    }

    /**
     * Everything an unconscious character may not do, which is everything but
     * three things.
     *
     * <p>One gate rather than a check inside each handler. Nine copies of a rule
     * is nine chances for the tenth handler to be written without it, and the
     * tenth handler is always the one somebody finds a way through.
     *
     * <p>{@code Join} and {@code Detach} are not the character acting - they are
     * the socket arriving and leaving, and refusing them would mean a knocked
     * out character could neither be reconnected nor cleaned up. {@code Chat} is
     * allowed on purpose: the character is unconscious, the player is not, and
     * three minutes with no way to say "back shortly" punishes the person rather
     * than the character.
     */
    private boolean refusedWhileOut(Command command) {
        if (command instanceof Command.Join || command instanceof Command.Detach
                || command instanceof Command.Chat) {
            return false;
        }
        Actor actor = byClient.get(command.client());
        if (actor == null || !actor.isUnconscious(System.currentTimeMillis())) {
            return false;
        }
        sendError(actor, "Jeszcze się nie ocknęłaś. Zostało " + secondsUntilWaking(actor) + " s.");
        return true;
    }

    private long secondsUntilWaking(Actor actor) {
        return Math.max(0, (actor.wakesAt - System.currentTimeMillis() + 999) / 1000);
    }

    /**
     * Notices the moment somebody comes round.
     *
     * <p>Nothing here changes what the character may do - that is computed from
     * the stamp every time it is asked. This exists only so the client is told
     * once, rather than sitting behind an overlay until something else happens
     * to send it a frame.
     */
    private void wakeTheSleeping() {
        long now = System.currentTimeMillis();
        for (Actor actor : actors.values()) {
            if (actor.wakesAt > 0 && !actor.isUnconscious(now)) {
                actor.wakesAt = 0;
                actor.dirty = true;
                if (actor.isPlayer()) {
                    joined.add(toDto(actor)); // everyone sees the body get up
                    sendYou(actor);
                }
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
        sendSkills(actor);
        sendPurse(actor);
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
        sendSkills(actor);
        sendPurse(actor);
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
        actor.wakesAt = saved.wakesAt();
        actor.characterClass = content.classOrDefault(saved.classId());
        actor.attributes = saved.attributes();
        actor.unspentPoints = Math.max(0, saved.unspentPoints());
        actor.inventory.restore(saved.items(), content.items());
        actor.skills.restore(saved.skills(), content.skills());
        actor.purse.restore(saved.coins(), content.currencies());
        actor.storage = new Storage(saved.deposit().tabs());
        actor.storage.restore(saved.deposit().items(), content.items());
        actor.storagePurse.restore(saved.deposit().coins(), content.currencies());
        actor.accountStorage = new Storage(saved.accountDeposit().tabs());
        actor.accountStorage.restore(saved.accountDeposit().items(), content.items());
        actor.accountPurse.restore(saved.accountDeposit().coins(), content.currencies());
        actor.skillPoints = Math.max(0, saved.skillPoints());
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
        // Asking to be somewhere else answers the question this tile was
        // asking. Leaving it on screen would be a "yes" about a threshold the
        // character is walking away from.
        closePassage(actor);
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
        if (actor == null || target == null || actor.inFight()) {
            return;
        }
        // Before the check on whether it is alive, not after: an NPC has no
        // health at all, so by that test it is already a corpse and the click
        // would be swallowed in silence rather than answered.
        if (!target.isMob()) {
            sendError(actor, target.isNpc()
                    ? target.name + " nie jest tu po to, żeby się bić."
                    : "Na razie można walczyć tylko z potworami.");
            return;
        }
        if (!target.isAlive()) {
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
        if (!stack.def().slot().isWorn()) {
            sendError(actor, stack.def().name() + " się nie nosi - to się niesie.");
            return;
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

    /**
     * Asks to use a skill in the coming round.
     *
     * <p>Queued rather than resolved here, exactly like fleeing: a round is the
     * unit in which everything in a fight happens, and a skill that landed the
     * instant a key was pressed would be the one thing that did not wait for
     * one.
     */
    private void handleUse(Command.Use use) {
        Actor actor = byClient.get(use.client());
        if (actor == null) {
            return;
        }
        if (!actor.inFight()) {
            sendError(actor, "Umiejętności przydają się dopiero w walce.");
            return;
        }
        SkillDef skill = content.skills().get(use.skillId());
        if (skill == null || !actor.skills.knows(skill.id())) {
            return; // asking for something unlearned says nothing worth answering
        }
        if (skill.isPassive()) {
            sendError(actor, skill.name() + " działa cały czas - nie da się jej użyć.");
            return;
        }
        if (actor.energy < skill.cost()) {
            sendError(actor, "Za mało energii na: " + skill.name()
                    + " (" + actor.energy + "/" + skill.cost() + ").");
            return;
        }
        actor.pendingSkill = skill.id();
    }

    /** Puts a point into a skill. Refused in a fight, like everything else. */
    private void handleLearn(Command.Learn learn) {
        Actor actor = byClient.get(learn.client());
        if (actor == null) {
            return;
        }
        if (actor.inFight()) {
            sendError(actor, "Nauka poczeka do końca walki.");
            return;
        }
        if (actor.skillPoints <= 0) {
            sendError(actor, "Nie masz punktów umiejętności.");
            return;
        }
        SkillDef skill = content.skills().get(learn.skillId());
        if (skill == null) {
            return;
        }
        String classId = actor.characterClass == null ? null : actor.characterClass.id();
        if (!skill.availableTo(classId)) {
            sendError(actor, skill.name() + " nie jest dla tej klasy.");
            return;
        }
        if (actor.skills.rankOf(skill.id()) >= skill.maxRank()) {
            sendError(actor, skill.name() + " jest już na najwyższej randze.");
            return;
        }

        CurrencyDef money = content.primaryCurrency();
        int price = priceToRaise(actor, skill);
        if (!actor.purse.take(money.id(), price)) {
            sendError(actor, "Za mało: " + price + " " + money.shortName()
                    + ", a masz " + actor.purse.amountOf(money.id())
                    + ". U mistrza swojej klasy zapłacisz połowę.");
            return;
        }

        actor.skillPoints--;
        actor.skills.raise(skill.id());
        actor.dirty = true;
        actor.skillsDirty = true;
        if (price > 0) {
            actor.purseDirty = true;
            sendPurse(actor);
        }
        sendYou(actor);
        sendSkills(actor);
    }

    // ------------------------------------------------------------------
    // talking
    // ------------------------------------------------------------------

    private void handleTalk(Command.Talk talk) {
        Actor actor = byClient.get(talk.client());
        Actor npc = actors.get(talk.npcId());
        if (actor == null || npc == null || !npc.isNpc()) {
            return;
        }
        if (actor.inFight()) {
            sendError(actor, "W walce nie ma z kim rozmawiać.");
            return;
        }
        if (!npc.npc.does(NpcFunction.DIALOGUE)) {
            sendError(actor, npc.name + " nie ma ci nic do powiedzenia.");
            return;
        }
        if (!adjacent(actor, npc)) {
            // Checked here and nowhere else in the client's reach. Without it a
            // player could hold a conversation from the far end of the map, and
            // every later function - healing, a shop, a quest - would inherit
            // that as a way of using an NPC without ever going to them.
            sendError(actor, "Musisz podejść bliżej.");
            return;
        }
        Dialogue dialogue = npc.npc.dialogue();
        actor.talkingTo = npc.id;
        actor.atNode = dialogue.startId();
        sendDialogue(actor, npc, dialogue.start());
        // Walking up to your own master halves what a skill point costs, and
        // that price rides in "you" - so arriving is a change to this
        // character's own state exactly as being hit is.
        sendYou(actor);
    }

    private void handleChoose(Command.Choose choose) {
        Actor actor = byClient.get(choose.client());
        if (actor == null || actor.talkingTo == 0) {
            return;
        }
        Actor npc = actors.get(actor.talkingTo);
        if (npc == null || !npc.isNpc() || !adjacent(actor, npc)) {
            endConversation(actor);
            return;
        }
        Dialogue dialogue = npc.npc.dialogue();
        DialogueNode here = dialogue.node(actor.atNode);
        DialogueOption picked = here == null ? null : here.option(choose.option());
        if (picked == null) {
            // Either a client out of step with the server, or one making choices
            // up. Both get the same answer: the conversation is where the server
            // says it is.
            sendError(actor, "Nie ma tu takiej odpowiedzi.");
            return;
        }
        // The deed first, then the reply: the node it leads to is what somebody
        // says about what just happened, so it has to have happened by then.
        doDeed(actor, npc, picked.deed());
        if (!picked.leadsSomewhere()) {
            endConversation(actor);
            return;
        }
        actor.atNode = picked.goTo();
        sendDialogue(actor, npc, dialogue.node(actor.atNode));
    }

    private void doDeed(Actor actor, Actor npc, DialogueAction deed) {
        if (deed == null) {
            return;
        }
        switch (deed) {
            case HEAL -> heal(actor, npc);
            case OPEN_SHOP -> sendShop(actor, npc);
            case RESET_SKILLS -> resetSkills(actor, npc);
            case OPEN_STORAGE -> sendStorage(actor, npc);
            // END is somewhere to go rather than something to do, and never
            // arrives here - the loader refuses it as a deed.
            case END -> { }
        }
    }

    /**
     * The one cure in the game. Nothing regenerates and dying no longer heals,
     * so this is where health comes back - which is also why it is free and why
     * the healer stands within the tiles nothing may attack in.
     *
     * <p>Weakness is deliberately left alone. It is the price of having died,
     * not a wound, and a healer who wiped it would make dying cost nothing.
     */
    private void heal(Actor actor, Actor npc) {
        if (actor.hp >= actor.maxHp()) {
            return; // nothing to do, and nothing worth saying about it
        }
        actor.hp = actor.maxHp();
        actor.dirty = true;
        chat.add(new ChatDto(npc.id, npc.name, "* opatruje rany: " + actor.name + " *"));
        sendYou(actor);
    }

    private void handleStopTalking(Command.StopTalking stop) {
        Actor actor = byClient.get(stop.client());
        if (actor != null) {
            endConversation(actor);
        }
    }

    /**
     * A conversation ends when you walk away from it, and this is the only place
     * that notices. Nothing else would: the player never sends anything, they
     * simply click a tile - and would otherwise be left talking to somebody who
     * is no longer on the screen.
     */
    private void endConversationsOutOfEarshot() {
        for (Actor actor : actors.values()) {
            if (actor.talkingTo == 0) {
                continue;
            }
            Actor npc = actors.get(actor.talkingTo);
            if (npc == null || !adjacent(actor, npc) || actor.inFight()) {
                endConversation(actor);
            }
        }
    }

    private void endConversation(Actor actor) {
        if (actor.talkingTo == 0) {
            return;
        }
        int npcId = actor.talkingTo;
        actor.talkingTo = 0;
        actor.atNode = null;
        sendYou(actor); // and leaving puts the price back up
        if (actor.client != null) {
            String frame = serialise(ServerMessages.Dialogue.closed(npcId));
            if (frame != null) {
                actor.client.send(frame);
            }
            // The stall closes with the conversation it opened from. Leaving it
            // on screen would be a shop the server has already stopped serving.
            String stall = serialise(ServerMessages.Shop.closed(npcId));
            if (stall != null) {
                actor.client.send(stall);
            }
            // And the chest, for the same reason: what is in it may not be
            // touched from across the map, so it may not be looked at either.
            String chest = serialise(ServerMessages.Storage.closed(npcId));
            if (chest != null) {
                actor.client.send(chest);
            }
        }
    }

    /**
     * Sent straight out rather than queued behind the delta, unlike {@code you}:
     * everything it refers to - the NPC and where it stands - was in the frame
     * the player was given on arrival, so there is no later delta for it to
     * overtake.
     */
    private void sendDialogue(Actor actor, Actor npc, DialogueNode node) {
        if (actor.client == null || node == null) {
            return;
        }
        List<ServerMessages.OptionDto> options = new ArrayList<>(node.options().size());
        for (int i = 0; i < node.options().size(); i++) {
            options.add(new ServerMessages.OptionDto(i, node.options().get(i).text()));
        }
        String frame = serialise(
                new ServerMessages.Dialogue(npc.id, npc.name, node.text(), options));
        if (frame != null) {
            actor.client.send(frame);
        }
    }

    /**
     * The master this character is standing in front of, or null.
     *
     * <p>Theirs and nobody else's: a mage at the swordmaster is a mage having a
     * chat. That restriction is the only thing that makes three masters three
     * NPCs rather than one with three descriptions.
     */
    private Actor masterFor(Actor actor) {
        Actor npc = actor.talkingTo == 0 ? null : actors.get(actor.talkingTo);
        if (npc == null || !npc.isNpc() || npc.npc.master() == null) {
            return null;
        }
        String classId = actor.characterClass == null ? null : actor.characterClass.id();
        return npc.npc.master().classId().equals(classId) ? npc : null;
    }

    /** What one more rank of this skill costs where the character is standing. */
    private int priceToRaise(Actor actor, SkillDef skill) {
        int full = SkillPrices.toRaise(actor.skills.rankOf(skill.id()), actor.level);
        return masterFor(actor) == null ? full : SkillPrices.atMaster(full);
    }

    /**
     * What a point costs here, for a skill already begun.
     *
     * <p>One number for the whole panel rather than one per skill: the price
     * does not depend on which skill it goes into, only on the level and on
     * whether the right master is listening. The first rank of anything is
     * free, and the interface says so on the button rather than in this number.
     */
    private int pointPriceHere(Actor actor) {
        int full = SkillPrices.toRaise(1, actor.level);
        return masterFor(actor) == null ? full : SkillPrices.atMaster(full);
    }

    /** What giving every spent point back would cost. Zero when nothing was paid. */
    private int priceToReset(Actor actor) {
        return SkillPrices.toReset(actor.skills.ranks(), actor.level);
    }

    /**
     * Hands back every point spent on a skill.
     *
     * <p>Only a character's own master will do this, which the deed alone does
     * not guarantee: an option carrying RESET_SKILLS can only sit on a master,
     * but nothing stops a warrior from reading the mage master's conversation.
     */
    private void resetSkills(Actor actor, Actor npc) {
        if (masterFor(actor) != npc) {
            sendError(actor, npc.name + " uczy innej klasy.");
            return;
        }
        int points = actor.skills.spent();
        if (points <= 0) {
            sendError(actor, "Nie masz czego cofać.");
            return;
        }
        CurrencyDef money = content.primaryCurrency();
        int price = priceToReset(actor);
        if (!actor.purse.take(money.id(), price)) {
            sendError(actor, "Cofnięcie kosztuje " + price + " " + money.shortName()
                    + ", a masz " + actor.purse.amountOf(money.id()) + ".");
            return;
        }
        actor.skills.forgetEverything();
        actor.skillPoints += points;
        actor.dirty = true;
        actor.skillsDirty = true;
        actor.purseDirty = true;
        chat.add(new ChatDto(npc.id, npc.name, "* zwraca punkty: " + actor.name + " *"));
        sendYou(actor);
        sendSkills(actor);
        sendPurse(actor);
    }

    // ------------------------------------------------------------------
    // trade
    // ------------------------------------------------------------------

    /**
     * The trader a character may deal with right now, or null with the reason
     * already sent.
     *
     * <p>It is whoever they are talking to, and nobody else, and that is the
     * whole of the check. A shop opens from a conversation, so it inherits every
     * door the conversation has - the range, the refusal in a fight, and the
     * ending the moment you walk away.
     *
     * <p>There was a second range check here. It never once fired: walking out
     * of earshot ends the conversation within the same tick as the step, so by
     * the time any command is drained there is nobody being talked to. Deleting
     * a guard that cannot run beats keeping a second copy of a rule that would
     * one day disagree with the first.
     */
    private Actor traderFor(Actor actor) {
        if (actor == null) {
            return null;
        }
        Actor npc = actor.talkingTo == 0 ? null : actors.get(actor.talkingTo);
        if (npc == null || !npc.isNpc() || npc.npc.shop() == null) {
            sendError(actor, "Nie ma tu z kim handlować.");
            return null;
        }
        return npc;
    }

    private void handleBuy(Command.Buy buy) {
        Actor actor = byClient.get(buy.client());
        Actor npc = traderFor(actor);
        if (npc == null) {
            return;
        }
        Shop shop = npc.npc.shop();
        if (!shop.dealsIn(buy.itemDefId())) {
            sendError(actor, "Tego tu nie ma na sprzedaż.");
            return;
        }
        ItemDef def = content.items().get(buy.itemDefId());
        CurrencyDef currency = content.currencies().get(shop.currencyId());
        if (def == null || currency == null) {
            return; // refused at startup; nothing sensible to say at runtime
        }
        if (actor.inventory.isFull()) {
            // Checked before the money moves. Taking payment and then having
            // nowhere to put the goods is the one outcome nobody forgives.
            sendError(actor, "Plecak jest pełny.");
            return;
        }
        if (!actor.purse.take(currency.id(), def.value())) {
            sendError(actor, "Za mało: " + def.value() + " " + currency.shortName()
                    + ", a masz " + actor.purse.amountOf(currency.id()) + ".");
            return;
        }
        actor.inventory.add(ItemStack.of(def));
        actor.dirty = true;
        actor.itemsDirty = true;
        actor.purseDirty = true;
        chat.add(new ChatDto(actor.id, actor.name, "* kupuje: " + def.name() + " *"));
        sendBag(actor);
        sendPurse(actor);
        sendShop(actor, npc);
    }

    private void handleSell(Command.Sell sell) {
        Actor actor = byClient.get(sell.client());
        Actor npc = traderFor(actor);
        if (npc == null) {
            return;
        }
        Shop shop = npc.npc.shop();
        ItemStack stack = actor.inventory.inBag(sell.itemId());
        if (stack == null) {
            // Worn, or already sold, or never owned. All three mean the same
            // thing to the server: it is not in the bag, so it is not for sale.
            sendError(actor, "Tego nie masz w plecaku. Zdejmij, zanim sprzedasz.");
            return;
        }
        if (!shop.dealsIn(stack.def().id())) {
            // A trader buys back what they sell and nothing else - otherwise the
            // chest that deals in fangs would be buying swords with them.
            sendError(actor, npc.name + " tego nie skupuje.");
            return;
        }
        CurrencyDef currency = content.currencies().get(shop.currencyId());
        if (currency == null) {
            return;
        }
        int paid = Shop.buybackPrice(stack.def().value());
        actor.inventory.removeFromBag(stack.id());
        actor.purse.add(currency.id(), paid);
        actor.dirty = true;
        actor.itemsDirty = true;
        actor.purseDirty = true;
        chat.add(new ChatDto(actor.id, actor.name, "* sprzedaje: " + stack.def().name()
                + " za " + paid + " " + currency.shortName() + " *"));
        sendBag(actor);
        sendPurse(actor);
        sendShop(actor, npc);
    }

    /**
     * The storekeeper this character is talking to, or nobody.
     *
     * <p>The twin of {@link #traderFor}, and the whole of the "are you allowed
     * to touch this" question: a chest may only be reached through an open
     * conversation, and walking away ends the conversation in the same tick.
     */
    private Actor keeperFor(Actor actor) {
        if (actor == null) {
            return null;
        }
        Actor npc = actor.talkingTo == 0 ? null : actors.get(actor.talkingTo);
        if (npc == null || !npc.isNpc() || npc.npc.vault() == null) {
            sendError(actor, "Nie ma tu gdzie niczego odłożyć.");
            return null;
        }
        return npc;
    }

    private Storage chestOf(Actor actor, boolean account) {
        return account ? actor.accountStorage : actor.storage;
    }

    private Purse chestPurseOf(Actor actor, boolean account) {
        return account ? actor.accountPurse : actor.storagePurse;
    }

    private void chestChanged(Actor actor, boolean account) {
        actor.dirty = true;
        if (account) {
            actor.accountDepositDirty = true;
        } else {
            actor.depositDirty = true;
        }
    }

    private void handleDeposit(Command.Deposit deposit) {
        Actor actor = byClient.get(deposit.client());
        Actor npc = keeperFor(actor);
        if (npc == null) {
            return;
        }
        ItemStack stack = actor.inventory.inBag(deposit.itemId());
        if (stack == null) {
            // Worn, or already put away, or never owned. The bag is the only
            // place a thing can be handed over from - taking somebody's sword
            // off for them is how they walk into the next fight unarmed.
            sendError(actor, "Tego nie masz w plecaku. Zdejmij, zanim odłożysz.");
            return;
        }
        Storage chest = chestOf(actor, deposit.account());
        if (!chest.hasTab(deposit.tab())) {
            sendError(actor, "Nie masz takiej zakładki.");
            return;
        }
        if (chest.isFull(deposit.tab())) {
            sendError(actor, "Ta zakładka jest pełna.");
            return;
        }
        actor.inventory.removeFromBag(stack.id());
        chest.put(stack, deposit.tab());
        actor.itemsDirty = true;
        chestChanged(actor, deposit.account());
        sendBag(actor);
        sendStorage(actor, npc);
    }

    private void handleWithdraw(Command.Withdraw withdraw) {
        Actor actor = byClient.get(withdraw.client());
        Actor npc = keeperFor(actor);
        if (npc == null) {
            return;
        }
        if (actor.inventory.isFull()) {
            // Asked before anything is moved. An item taken out of a chest with
            // nowhere to go is an item that has stopped existing.
            sendError(actor, "Plecak jest pełny.");
            return;
        }
        Storage chest = chestOf(actor, withdraw.account());
        ItemStack stack = chest.take(withdraw.itemId());
        if (stack == null) {
            sendError(actor, "Tego tu nie ma.");
            return;
        }
        actor.inventory.add(stack);
        actor.itemsDirty = true;
        chestChanged(actor, withdraw.account());
        sendBag(actor);
        sendStorage(actor, npc);
    }

    private void handleDepositCoins(Command.DepositCoins put) {
        Actor actor = byClient.get(put.client());
        Actor npc = keeperFor(actor);
        if (npc == null) {
            return;
        }
        CurrencyDef currency = content.currencies().get(put.currencyId());
        if (currency == null || put.amount() <= 0) {
            sendError(actor, "Nie ma takich pieniędzy.");
            return;
        }
        if (!actor.purse.take(currency.id(), put.amount())) {
            sendError(actor, "Nie masz tyle.");
            return;
        }
        chestPurseOf(actor, put.account()).add(currency.id(), put.amount());
        actor.purseDirty = true;
        chestChanged(actor, put.account());
        sendPurse(actor);
        sendStorage(actor, npc);
    }

    private void handleWithdrawCoins(Command.WithdrawCoins take) {
        Actor actor = byClient.get(take.client());
        Actor npc = keeperFor(actor);
        if (npc == null) {
            return;
        }
        CurrencyDef currency = content.currencies().get(take.currencyId());
        if (currency == null || take.amount() <= 0) {
            sendError(actor, "Nie ma takich pieniędzy.");
            return;
        }
        if (!chestPurseOf(actor, take.account()).take(currency.id(), take.amount())) {
            sendError(actor, "Tyle tu nie leży.");
            return;
        }
        actor.purse.add(currency.id(), take.amount());
        actor.purseDirty = true;
        chestChanged(actor, take.account());
        sendPurse(actor);
        sendStorage(actor, npc);
    }

    /**
     * Buys the next tab of one chest.
     *
     * <p>Paid out of the purse in hand, never out of the chest itself: money
     * put away is money put away, and a keeper who could reach into the chest
     * to pay himself would make the deposit meaningless.
     */
    private void handleBuyTab(Command.BuyTab buy) {
        Actor actor = byClient.get(buy.client());
        Actor npc = keeperFor(actor);
        if (npc == null) {
            return;
        }
        Vault vault = npc.npc.vault();
        Storage chest = chestOf(actor, buy.account());
        int price = vault.priceOfNextTab(chest.tabs(), buy.account());
        CurrencyDef currency = content.currencies()
                .get(buy.account() ? vault.accountCurrencyId() : vault.currencyId());
        if (price < 0 || currency == null) {
            sendError(actor, "Więcej zakładek już nie będzie.");
            return;
        }
        if (!actor.purse.take(currency.id(), price)) {
            sendError(actor, "Za mało: " + price + " " + currency.shortName()
                    + ", a masz " + actor.purse.amountOf(currency.id()) + ".");
            return;
        }
        // Cannot fail: a price list longer than the chest is refused while the
        // content is read, so a priced tab is always a tab there is room for.
        // Asking again here would be the same rule kept in two places.
        chest.openAnotherTab();
        actor.purseDirty = true;
        chestChanged(actor, buy.account());
        chat.add(new ChatDto(actor.id, actor.name, "* wynajmuje zakładkę *"));
        sendPurse(actor);
        sendStorage(actor, npc);
    }

    /**
     * Sent straight out rather than queued, like the dialogue and the stall:
     * the tile it is about is one this client was told of on arrival, so there
     * is no later delta for it to overtake.
     */
    private void sendPassage(Actor actor, Door door) {
        if (actor.client == null) {
            return;
        }
        String frame = serialise(new ServerMessages.Passage(door.x(), door.y(), door.name(),
                nameOf(door.consumesItem())));
        if (frame != null) {
            actor.client.send(frame);
        }
    }

    /** The question goes when the character does - answering it from two tiles
     * away would be answering about somewhere they are not. */
    private void closePassage(Actor actor) {
        if (actor == null || actor.client == null) {
            return;
        }
        String frame = serialise(ServerMessages.Passage.closed());
        if (frame != null) {
            actor.client.send(frame);
        }
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
            actor.dirty = actor.isPlayer(); // only a character's position is worth keeping
            moved.add(new MoveDto(actor.id, actor.fromX, actor.fromY, actor.x, actor.y,
                    actor.dir.name(), actor.stepTicks * TICK_MS));
            arriveOnTile(actor);
        }
    }

    /**
     * What the tile just stepped onto does, if anything.
     *
     * <p>Queued rather than acted on: this runs inside a walk over every actor
     * on the map, and handing one of them away means removing it from that very
     * collection.
     */
    private void arriveOnTile(Actor actor) {
        if (!actor.isPlayer()) {
            return;
        }
        Door door = map.doorAt(actor.x, actor.y);
        if (door == null) {
            return;
        }
        String no = refuseToPass(actor, door);
        if (no != null) {
            // Stopped on the threshold rather than pushed back: being bounced a
            // tile by a rule nobody explained is worse than being told.
            actor.path.clear();
            sendError(actor, no);
            return;
        }
        if (door.takesSomething()) {
            // The one passage that asks first. Everything else in this game
            // that costs something is a click on a price; this is a step, and a
            // step that quietly burns what you are carrying is the kind of rule
            // players learn by losing something.
            actor.path.clear();
            sendPassage(actor, door);
            return;
        }
        leaving.add(new Leaving(actor, door.toMap(), door.toX(), door.toY()));
    }

    /** The thresholds, asked in one place, because two places would drift. */
    private String refuseToPass(Actor actor, Door door) {
        return door.refuse(actor.level,
                carries(actor, door.requiresItem()), carries(actor, door.consumesItem()),
                nameOf(door.requiresItem()), nameOf(door.consumesItem()));
    }

    /**
     * Yes to a passage that takes something.
     *
     * <p>Nothing was remembered about having asked, and nothing needs to be:
     * every condition is asked again here, from the tile the character is
     * actually standing on. A client that says yes without ever being asked has
     * simply taken the step and agreed to it in one go, through exactly these
     * checks.
     */
    private void handlePass(Command.Pass pass) {
        Actor actor = byClient.get(pass.client());
        if (actor == null) {
            return;
        }
        if (alreadyLeaving(actor)) {
            // A second yes arriving in the same tick as the first. The handover
            // happens at the end of the tick, so until then the character is
            // still standing on the door with a ticket in the bag - and the
            // answer would be charged for all over again.
            return;
        }
        Door door = map.doorAt(actor.x, actor.y);
        if (door == null || !door.isAt(pass.x(), pass.y()) || !door.takesSomething()) {
            // Moved off it, or answered about a different tile entirely.
            sendError(actor, "Nie stoisz w tym przejściu.");
            return;
        }
        String no = refuseToPass(actor, door);
        if (no != null) {
            sendError(actor, no);
            return;
        }
        String copy = aCopyInTheBag(actor, door.consumesItem());
        if (copy == null || !actor.inventory.removeFromBag(copy)) {
            // Cannot happen: the refusal above has just seen one. Said out loud
            // rather than transferring anyway, because passing for free through
            // a passage that charges is the one failure nobody would report.
            sendError(actor, "Potrzebujesz: " + nameOf(door.consumesItem()) + ".");
            return;
        }
        actor.dirty = true;
        actor.itemsDirty = true;
        chat.add(new ChatDto(actor.id, actor.name,
                "* zostawia w przejściu: " + nameOf(door.consumesItem()) + " *"));
        sendBag(actor);
        closePassage(actor);
        leaving.add(new Leaving(actor, door.toMap(), door.toX(), door.toY()));
    }

    private boolean alreadyLeaving(Actor actor) {
        for (Leaving move : leaving) {
            if (move.actor() == actor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Which copy of it is handed over. Any of them: an item is its definition,
     * so two torches are the same torch.
     *
     * @return the instance id of one in the bag, or null when there is none
     */
    private String aCopyInTheBag(Actor actor, String itemId) {
        for (ItemStack stack : actor.inventory.bag()) {
            if (stack.def().id().equals(itemId)) {
                return stack.id();
            }
        }
        return null;
    }

    private boolean carries(Actor actor, String itemId) {
        if (itemId == null) {
            return true;
        }
        for (ItemStack stack : actor.inventory.bag()) {
            if (stack.def().id().equals(itemId)) {
                return true;
            }
        }
        return false;
    }

    private String nameOf(String itemId) {
        ItemDef def = itemId == null ? null : content.items().get(itemId);
        return def == null ? String.valueOf(itemId) : def.name();
    }

    /**
     * Hands over everybody who stepped through something this tick.
     *
     * <p>The order matters: the character is written down as already being on
     * the other map <em>before</em> it leaves this one. A crash in between then
     * leaves a row saying where they were going, which is recoverable; the
     * other order leaves a row saying where they no longer are.
     */
    private void carryOutTransfers() {
        for (Leaving move : leaving) {
            Actor actor = move.actor();
            SavedCharacter character = asSavedCharacter(actor, move.toMapId(),
                    move.toX(), move.toY());
            persistence.save(new ActorSnapshot(actor.nameKey, actor.name, move.toMapId(),
                    move.toX(), move.toY(), actor.dir.name(),
                    actor.level, actor.xp, actor.hp, actor.wakesAt,
                    actor.attributes, actor.unspentPoints, actor.inventory.stored(),
                    actor.characterClass == null ? null : actor.characterClass.id(),
                    actor.skillPoints, actor.skills.stored(), actor.purse.stored(),
                    actor.accountId, depositOf(actor), accountDepositOf(actor)));
            actor.dirty = false;
            actor.itemsDirty = false;
            actor.skillsDirty = false;
            actor.purseDirty = false;
            actor.depositDirty = false;
            actor.accountDepositDirty = false;

            endConversation(actor);
            actors.remove(actor.id);
            byNameKey.remove(actor.nameKey);
            byClient.remove(actor.client);
            left.add(actor.id);

            transfers.move(actor.client, move.toMapId(), actor.accountId, character);
        }
        leaving.clear();
    }

    /**
     * Everything worth keeping about a live character, in the immutable shape
     * the database already speaks.
     *
     * <p>This is what crosses between map threads, and the reason crossing is
     * safe: a copy taken by the thread that owns the actor, read by the thread
     * that will own it next, with nothing shared in between.
     */
    private SavedCharacter asSavedCharacter(Actor actor, String mapId, int x, int y) {
        return new SavedCharacter(actor.nameKey, actor.name, mapId, x, y, actor.dir,
                actor.level, actor.xp, actor.hp, actor.wakesAt, actor.attributes,
                actor.unspentPoints, actor.inventory.stored(),
                actor.characterClass == null ? null : actor.characterClass.id(),
                actor.skillPoints, actor.skills.stored(), actor.purse.stored(),
                new Deposit(actor.storage.tabs(), actor.storage.stored(),
                        actor.storagePurse.stored()),
                new Deposit(actor.accountStorage.tabs(), actor.accountStorage.stored(),
                        actor.accountPurse.stored()));
    }

    /** Both chests, whole, for a character that is on its way somewhere else. */
    private Deposit depositOf(Actor actor) {
        return new Deposit(actor.storage.tabs(), actor.storage.stored(),
                actor.storagePurse.stored());
    }

    private Deposit accountDepositOf(Actor actor) {
        return new Deposit(actor.accountStorage.tabs(), actor.accountStorage.stored(),
                actor.accountPurse.stored());
    }

    private void reapExpiredActors() {
        actors.values().removeIf(actor -> {
            // A mob has no socket, so by the player rules it looks permanently
            // disconnected and would be swept away thirty seconds after the map
            // starts. It leaves the world only when something kills it.
            if (!actor.isPlayer() || actor.online() || tick - actor.offlineSinceTick < graceTicks) {
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
                continue;
            }
            // The path was worked out when the attack was ordered, and creatures
            // wander. Without this, walking after something that moves takes you
            // to where it used to be, and leaves you standing on empty ground
            // wondering why nothing happened.
            if (actor.path.isEmpty() && actor.pendingMove == null) {
                actor.pendingMove = new int[]{target.x, target.y};
                if (!pendingMoves.contains(actor)) {
                    pendingMoves.add(actor);
                }
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
        // Every fight starts from nothing. Carrying energy in would make the
        // first round of the second fight worth more than the first round of
        // the first, and the best opening move would be to pick a fight you did
        // not want in order to arrive at the one you did already charged.
        person.energy = 0;
        person.pendingSkill = null;
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
        // Energy first, so the round a character is about to fight is one it has
        // already been paid for. Charging afterwards would mean the last round
        // of every fight topping up an energy bar that is about to be thrown
        // away.
        chargeEnergy(fight);

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

    /**
     * One round's worth of energy for everyone still in the fight.
     *
     * <p>Creatures have none. Giving them energy would mean giving them skills
     * to spend it on, and that is a milestone of its own rather than something
     * to slip in here.
     */
    private void chargeEnergy(Fight fight) {
        for (int playerId : fight.players()) {
            Actor player = actors.get(playerId);
            if (player == null || !player.isPlayer()) {
                continue;
            }
            int before = player.energy;
            player.energy = Energy.charged(player.energy, regenerationRank(player));
            if (player.energy != before) {
                sendYou(player);
            }
        }
    }

    private int regenerationRank(Actor actor) {
        return actor.skills.rankOf(SkillDefLoader.REGENERATION_ID);
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
        SkillDef skill = claimPendingSkill(attacker);
        if (skill != null) {
            // Paid for whether or not a single blow lands. An escape that gave
            // the energy back would make using a skill free against anything
            // quick enough to dodge, and using it every round the obvious play.
            attacker.energy -= skill.cost();
            sendYou(attacker);
            chat.add(new ChatDto(attacker.id, attacker.name, "* " + skill.name() + " *"));
            for (int blow = 0; blow < skill.blows(); blow++) {
                if (!swing(attacker, target, fight, skill)) {
                    return;
                }
            }
            return;
        }

        if (!swing(attacker, target, fight, null)) {
            return; // the target is down; nothing left to hit
        }
        if (combat.landsSecondBlow(attacker.secondBlowChance())) {
            swing(attacker, target, fight, null);
        }
    }

    /**
     * The skill this actor asked for, if it can still afford it, taking the
     * request either way.
     *
     * <p>Cleared even when it cannot be paid for: a request belongs to the round
     * it was made in, and one saved up would go off later, in a round the player
     * was not thinking about.
     */
    private SkillDef claimPendingSkill(Actor actor) {
        String asked = actor.pendingSkill;
        actor.pendingSkill = null;
        if (asked == null) {
            return null;
        }
        SkillDef skill = content.skills().get(asked);
        if (skill == null || skill.isPassive() || actor.energy < skill.cost()) {
            return null;
        }
        return skill;
    }

    /**
     * @param skill what is being struck with, or null for an ordinary blow
     * @return true if the target is still standing and can be hit again
     */
    private boolean swing(Actor attacker, Actor target, Fight fight, SkillDef skill) {
        if (combat.dodges(target.dodgeChance())) {
            // Reported as a blow for zero, so the client can say "0" where it
            // would have said a number. A miss that shows nothing at all looks
            // like the server stopped answering.
            damage.add(new DamageDto(attacker.id, target.id, 0, target.hp));
            return true;
        }

        int attack = attacker.attack();
        double armorIgnored = attacker.armorIgnored();
        if (skill != null) {
            attack = (int) Math.round(attack * skill.power());
            if (skill.overridesArmorIgnored()) {
                armorIgnored = skill.armorIgnored();
            }
        }
        int dealt = combat.damage(attack, target.armor(), armorIgnored);
        target.hp = Math.max(0, target.hp - dealt);
        damage.add(new DamageDto(attacker.id, target.id, dealt, target.hp));

        if (target.isPlayer()) {
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

        if (killer != null && killer.isPlayer()) {
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
        awardCoins(killer, creature);
    }

    /**
     * What a creature was carrying. Separate from the loot above because money
     * drops in an amount rather than dropping or not, and because a purse has
     * no capacity - nothing here can be lost to a full bag.
     */
    private void awardCoins(Actor killer, Actor creature) {
        boolean paid = false;
        for (CoinDrop drop : creature.mob.coins()) {
            if (!combat.rolls(drop.chance())) {
                continue;
            }
            int amount = drop.roll(random);
            CurrencyDef currency = content.currencies().get(drop.currencyId());
            if (currency == null) {
                continue; // the loader refuses this at startup; belt and braces
            }
            killer.purse.add(currency.id(), amount);
            chat.add(new ChatDto(killer.id, killer.name,
                    "* znajduje: " + amount + " " + currency.shortName() + " *"));
            paid = true;
        }
        if (paid) {
            killer.dirty = true;
            killer.purseDirty = true;
            sendPurse(killer);
        }
    }

    private void killPlayer(Actor player, Fight fight) {
        died.add(player.id);
        leaveFight(player, fight);

        RespawnPoint wakeUpAt = map.respawn();
        player.path.clear();
        player.x = wakeUpAt.x();
        player.y = wakeUpAt.y();
        player.fromX = player.x;
        player.fromY = player.y;
        player.hp = CombatRules.HEALTH_AFTER_DEATH;
        // Out of action, for longer the further along they are. Written as the
        // moment they may play again rather than as a countdown, so closing the
        // tab shortens nothing.
        player.wakesAt = System.currentTimeMillis()
                + CombatRules.wakeSeconds(player.level) * 1000L;
        player.dirty = true;

        chat.add(new ChatDto(player.id, player.name, "* ginie *"));
        if (!wakeUpAt.mapId().equals(map.id())) {
            // Killed somewhere that wakes its dead elsewhere. The same handover
            // a door uses, with the character still unconscious when it lands -
            // the waking stamp travels with everything else.
            leaving.add(new Leaving(player, wakeUpAt.mapId(), wakeUpAt.x(), wakeUpAt.y()));
            return;
        }

        // Everyone needs to see them vanish from where they fell and reappear at
        // the spawn; a plain move would have them walk the whole way back.
        joined.add(toDto(player));
        sendYou(player);
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
            player.skillPoints += player.level - before;
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
        actor.energy = 0; // it belongs to the fight, and the fight is over for them
        actor.pendingSkill = null;
        fightChanges.add(new FightDto(actor.id, false));
        if (actor.isPlayer()) {
            sendYou(actor);
        }
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
                actor.energy = 0;
                actor.pendingSkill = null;
                fightChanges.add(new FightDto(id, false));
                if (actor.isPlayer()) {
                    sendYou(actor);
                }
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
    /**
     * Puts the map's people and things where the map says they stand. Separate
     * from the creatures on purpose: a map with the creatures switched off - as
     * most of the loop tests run - still has its signposts.
     */
    private void placeNpcs() {
        for (NpcPlacement placement : map.npcs()) {
            Actor npc = new Actor(nextActorId++, placement.npc(),
                    placement.x(), placement.y(), placement.facing());
            actors.put(npc.id, npc);
            joined.add(toDto(npc));
        }
    }

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
        for (int id : pendingSkills) {
            Actor actor = actors.get(id);
            if (actor != null) {
                sendSkillsNow(actor);
            }
        }
        pendingSkills.clear();
        for (int id : pendingPurse) {
            Actor actor = actors.get(id);
            if (actor != null) {
                sendPurseNow(actor);
            }
        }
        pendingPurse.clear();
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
        if (!actor.isPlayer() || !actor.dirty) {
            return;
        }
        actor.dirty = false;
        // Items only when they moved. Null here means "leave those rows alone",
        // which is every save made by somebody simply walking across a map.
        List<StoredItem> items = actor.itemsDirty ? actor.inventory.stored() : null;
        actor.itemsDirty = false;
        List<StoredSkill> skills = actor.skillsDirty ? actor.skills.stored() : null;
        actor.skillsDirty = false;
        List<StoredCoin> coins = actor.purseDirty ? actor.purse.stored() : null;
        actor.purseDirty = false;
        // Chests move less often than anything else here: null leaves their
        // rows alone, which is every save by everybody not standing at one.
        Deposit deposit = actor.depositDirty ? depositOf(actor) : null;
        actor.depositDirty = false;
        Deposit accountDeposit = actor.accountDepositDirty ? accountDepositOf(actor) : null;
        actor.accountDepositDirty = false;
        // A copy, never the live actor: anything handed across threads must not
        // be something the tick is still writing to.
        persistence.save(new ActorSnapshot(actor.nameKey, actor.name, map.id(),
                actor.x, actor.y, actor.dir.name(),
                actor.level, actor.xp, actor.hp, actor.wakesAt,
                actor.attributes, actor.unspentPoints, items,
                actor.characterClass == null ? null : actor.characterClass.id(),
                actor.skillPoints, skills, coins,
                actor.accountId, deposit, accountDeposit));
    }

    private ActorDto toDto(Actor actor) {
        return new ActorDto(actor.id, actor.name, actor.x, actor.y, actor.dir.name(),
                !actor.isPlayer() || actor.online(),
                actor.kind.name(),
                actor.isMob() ? actor.mob.tier().name() : null,
                actor.isNpc() ? actor.npc.kind().name() : null,
                actor.level, actor.hp, actor.maxHp(), actor.inFight(),
                actor.isUnconscious(System.currentTimeMillis()));
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
        if (!actor.isPlayer() || actor.client == null) {
            return;
        }
        // Queued rather than sent. A "you" frame written mid-tick overtakes the
        // delta that explains it, so a client learns it has been knocked out
        // while its character is still standing where it fell.
        pendingYou.add(actor.id);
    }

    private void sendYouNow(Actor actor) {
        if (!actor.isPlayer() || actor.client == null) {
            return;
        }
        long floor = CombatRules.xpForLevel(actor.level);
        long ceiling = CombatRules.xpForLevel(actor.level + 1);
        Attributes total = actor.totalAttributes();
        ClassDef characterClass = actor.characterClass;
        String frame = serialise(new ServerMessages.You(
                characterClass == null ? null : characterClass.id(),
                characterClass == null ? null : characterClass.name(),
                actor.hp, actor.maxHp(),
                actor.energy, Energy.MAX, Energy.perRound(regenerationRank(actor)),
                actor.level, actor.xp, actor.xp - floor, ceiling - floor,
                actor.wakesAt, !actor.isAlive(),
                total.strength(), total.agility(), total.intellect(),
                actor.unspentPoints, actor.skillPoints,
                // What a point costs where this character is standing, which is
                // why it belongs here and not in the skills frame.
                pointPriceHere(actor),
                priceToReset(actor),
                actor.attack(), actor.armor(),
                (int) Math.round(actor.dodgeChance() * 100),
                (int) Math.round(actor.secondBlowChance() * 100)));
        if (frame != null) {
            actor.client.send(frame);
        }
    }

    private void sendSkills(Actor actor) {
        if (!actor.isPlayer() || actor.client == null) {
            return;
        }
        pendingSkills.add(actor.id);
    }

    private void sendSkillsNow(Actor actor) {
        if (!actor.isPlayer() || actor.client == null) {
            return;
        }
        String classId = actor.characterClass == null ? null : actor.characterClass.id();
        List<ServerMessages.SkillDto> mine = new ArrayList<>();
        for (SkillDef skill : content.skillsFor(classId)) {
            int rank = actor.skills.rankOf(skill.id());
            mine.add(new ServerMessages.SkillDto(skill.id(), skill.name(), skill.description(),
                    rank, skill.maxRank(), skill.cost(), skill.isPassive()));
        }
        String frame = serialise(new ServerMessages.Skills(actor.skillPoints, mine));
        if (frame != null) {
            actor.client.send(frame);
        }
    }

    /** Queued like {@code you}, and for the same reason: after the delta. */
    private void sendBag(Actor actor) {
        if (!actor.isPlayer() || actor.client == null) {
            return;
        }
        pendingBag.add(actor.id);
    }

    private void sendBagNow(Actor actor) {
        if (!actor.isPlayer() || actor.client == null) {
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

    private void sendPurse(Actor actor) {
        if (!actor.isPlayer() || actor.client == null) {
            return;
        }
        pendingPurse.add(actor.id);
    }

    private void sendPurseNow(Actor actor) {
        if (!actor.isPlayer() || actor.client == null) {
            return;
        }
        // Every currency, including the ones at zero. A purse that lists only
        // what you have makes the second currency appear from nowhere the first
        // time a wolf drops a fang, and look like a bug rather than a find.
        List<ServerMessages.CoinDto> coins = new ArrayList<>();
        for (CurrencyDef currency : content.currencies().values()) {
            coins.add(new ServerMessages.CoinDto(currency.id(), currency.name(),
                    currency.shortName(), actor.purse.amountOf(currency.id()),
                    currency.primary()));
        }
        String frame = serialise(new ServerMessages.Purse(coins));
        if (frame != null) {
            actor.client.send(frame);
        }
    }

    /**
     * A trader's shelf, sent straight out like the dialogue it opens from - and
     * for the same reason: everything it refers to was already in a frame this
     * client has.
     */
    private void sendShop(Actor actor, Actor npc) {
        if (actor.client == null || !npc.isNpc() || npc.npc.shop() == null) {
            return;
        }
        Shop shop = npc.npc.shop();
        CurrencyDef currency = content.currencies().get(shop.currencyId());
        if (currency == null) {
            return;
        }
        List<ServerMessages.GoodsDto> goods = new ArrayList<>();
        for (String itemId : shop.sells()) {
            ItemDef def = content.items().get(itemId);
            if (def == null) {
                continue; // refused at startup; belt and braces
            }
            goods.add(new ServerMessages.GoodsDto(def.id(), def.name(), def.slot().name(),
                    def.requiresLevel(), def.value(), Shop.buybackPrice(def.value()),
                    def.bonuses().strength(), def.bonuses().agility(), def.bonuses().intellect(),
                    def.attack(), def.armor()));
        }
        String frame = serialise(new ServerMessages.Shop(npc.id, npc.name, currency.id(),
                currency.shortName(), goods));
        if (frame != null) {
            actor.client.send(frame);
        }
    }

    /**
     * Both chests, sent straight out like the stall beside them and for the
     * same reason: everything in them was already named in a frame this client
     * has.
     */
    private void sendStorage(Actor actor, Actor npc) {
        if (actor.client == null || !npc.isNpc() || npc.npc.vault() == null) {
            return;
        }
        Vault vault = npc.npc.vault();
        List<ServerMessages.ChestDto> chests = List.of(
                chestDto(actor, "character", false, vault),
                chestDto(actor, "account", true, vault));
        String frame = serialise(new ServerMessages.Storage(npc.id, npc.name, chests));
        if (frame != null) {
            actor.client.send(frame);
        }
    }

    private ServerMessages.ChestDto chestDto(Actor actor, String scope, boolean account,
                                             Vault vault) {
        Storage chest = chestOf(actor, account);
        List<ServerMessages.KeptDto> items = new ArrayList<>();
        for (Storage.Kept kept : chest.contents()) {
            items.add(new ServerMessages.KeptDto(kept.tab(), toDto(kept.stack(), actor)));
        }
        // Every currency, at zero as well, exactly as the purse does it: money
        // that appears from nowhere looks like a bug rather than a find.
        List<ServerMessages.CoinDto> coins = new ArrayList<>();
        Purse purse = chestPurseOf(actor, account);
        for (CurrencyDef currency : content.currencies().values()) {
            coins.add(new ServerMessages.CoinDto(currency.id(), currency.name(),
                    currency.shortName(), purse.amountOf(currency.id()), currency.primary()));
        }
        CurrencyDef tabCurrency = content.currencies()
                .get(account ? vault.accountCurrencyId() : vault.currencyId());
        return new ServerMessages.ChestDto(scope, chest.tabs(), Storage.TAB, items, coins,
                vault.priceOfNextTab(chest.tabs(), account),
                tabCurrency == null ? null : tabCurrency.id(),
                tabCurrency == null ? null : tabCurrency.shortName());
    }

    private static ServerMessages.ItemDto toDto(ItemStack stack, Actor owner) {
        ItemDef def = stack.def();
        return new ServerMessages.ItemDto(stack.id(), def.id(), def.name(), def.slot().name(),
                def.requiresLevel(), owner.level >= def.requiresLevel(),
                def.bonuses().strength(), def.bonuses().agility(), def.bonuses().intellect(),
                def.attack(), def.armor());
    }
}
