package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.CombatRules;
import com.kowihere.mmo.combat.Fight;
import com.kowihere.mmo.world.ClassDef;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.MobDef;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A player inside a running map. Mutable and completely unsynchronised by
 * design: only the owning map thread ever touches one.
 *
 * <p>{@code x}/{@code y} are authoritative the moment a step begins, not when
 * it visually finishes. The client animates from {@code fromX}/{@code fromY}
 * towards them, so the render always trails the truth by less than one step
 * instead of racing ahead of it.
 */
final class Actor {

    /** What this actor is. Players and creatures share the loop but little else. */
    enum Kind { PLAYER, MOB }

    final Kind kind;
    /** The creature this is a copy of, or null for a player. */
    final MobDef mob;
    /** Where a creature came from, and where it returns to when it gives up a chase. */
    final int homeX;
    final int homeY;
    /** Ticks this actor takes to cross one tile. */
    final int stepTicks;
    /** Whether killing this creature should put another one at its post later. */
    final boolean respawns;

    /** Tick from which this creature may next decide what to do. Throttles pathfinding. */
    long nextThinkTick;

    // ---- combat ----------------------------------------------------------

    int level = 1;
    long xp;
    int hp;

    /**
     * What this character is made of. A creature has none: its numbers come from
     * its definition, and giving it attributes would mean two ways of saying the
     * same thing, which is one too many.
     */
    Attributes attributes = Attributes.FRESH;
    /**
     * What kind of character this is. Null for a creature: a class decides
     * which attribute a character's blows are made of, and a creature's blows
     * are simply what its definition says.
     */
    ClassDef characterClass;
    /** Points earned by levelling and not yet spent. */
    int unspentPoints;

    final Inventory inventory = new Inventory();

    /**
     * Set when what this character carries has changed, as opposed to where it
     * stands. Walking about must not rewrite twenty rows of bag every time the
     * save interval comes round.
     */
    boolean itemsDirty;

    /** The fight this actor is locked into, or null. Movement is refused while it is set. */
    Fight fight;

    /** An actor this one is walking towards in order to attack it; 0 for nobody. */
    int approaching;

    /** When the penalty for dying wears off, in epoch millis; 0 when unpenalised. */
    long weakenedUntil;

    final int id;
    final String name;
    /** Case-folded name: the character's identity, and its primary key. */
    final String nameKey;
    /** Which account this character belongs to. */
    final long accountId;

    /** Set when this actor has moved since it was last handed to persistence. */
    boolean dirty;

    /**
     * Set on a creature that arrived as an elite's escort. Escorts outlive the
     * elite they came with, so without a mark on them every appearance would
     * leave two more wolves behind and the map would silently fill up.
     */
    boolean escort;

    int x;
    int y;
    int fromX;
    int fromY;
    Direction dir = Direction.DOWN;

    final Deque<int[]> path = new ArrayDeque<>();
    long nextStepTick;

    /**
     * Where this actor has been asked to walk, not yet turned into a path.
     * Requests are collected during the drain and resolved once per tick, so a
     * client cannot buy more pathfinding than a tick's worth no matter how fast
     * it clicks. Null when there is nothing pending.
     */
    int[] pendingMove;

    Client client;
    long offlineSinceTick;
    /**
     * Tick from which this actor may speak again. A deadline rather than a
     * timestamp of the last message: "how long ago" would have to be computed
     * against a sentinel, and subtracting one from a growing tick overflows.
     */
    long nextChatTick;

    /** A player. */
    Actor(int id, String name, String nameKey, long accountId, int x, int y, int stepTicks) {
        this(Kind.PLAYER, null, id, name, nameKey, accountId, x, y, stepTicks, false);
    }

    /**
     * A creature. It has no account and no session: it is derived from map data,
     * so it is never saved and never reaped for being offline.
     */
    Actor(int id, MobDef mob, int x, int y, boolean respawns) {
        this(Kind.MOB, mob, id, mob.name(), "mob:" + mob.id() + "#" + id, 0, x, y,
                mob.stepTicks(), respawns);
        this.level = mob.level();
        this.hp = mob.hp();
    }

    private Actor(Kind kind, MobDef mob, int id, String name, String nameKey, long accountId,
                  int x, int y, int stepTicks, boolean respawns) {
        this.respawns = respawns;
        this.kind = kind;
        this.mob = mob;
        this.homeX = x;
        this.homeY = y;
        this.stepTicks = stepTicks;
        this.id = id;
        this.name = name;
        this.nameKey = nameKey;
        this.accountId = accountId;
        this.x = x;
        this.y = y;
        this.fromX = x;
        this.fromY = y;
    }

    boolean online() {
        return client != null;
    }

    boolean isMob() {
        return kind == Kind.MOB;
    }

    boolean isAlive() {
        return hp > 0;
    }

    boolean inFight() {
        return fight != null;
    }

    /**
     * A creature's numbers come from its definition; a character's come from its
     * attributes and what it is wearing. Neither is stored anywhere, so neither
     * can fall out of step with what it was computed from.
     */
    Attributes totalAttributes() {
        return isMob() ? Attributes.FRESH : attributes.plus(inventory.grantedAttributes());
    }

    int maxHp() {
        if (isMob()) {
            return mob.hp();
        }
        return totalAttributes().maxHp() + (characterClass == null ? 0 : characterClass.hpBonus());
    }

    int attack() {
        if (isMob()) {
            return applyWeakness(mob.attack());
        }
        Attributes total = totalAttributes();
        int fromClass = characterClass == null
                ? total.attack()
                : characterClass.attackFrom(total);
        return applyWeakness(fromClass + inventory.grantedAttack());
    }

    /** How much of an opponent's armour this actor's blows pass through. */
    double armorIgnored() {
        return characterClass == null ? 0 : characterClass.armorIgnored();
    }

    int armor() {
        return applyWeakness(isMob()
                ? mob.armor()
                : totalAttributes().armor() + inventory.grantedArmor());
    }

    int maxMana() {
        return isMob() ? 0 : totalAttributes().maxMana();
    }

    /** A creature never evades; only characters have agility. */
    double dodgeChance() {
        return isMob() ? 0 : totalAttributes().dodgeChance();
    }

    double secondBlowChance() {
        return isMob() ? 0 : totalAttributes().secondBlowChance();
    }

    boolean isWeakened(long nowMillis) {
        return weakenedUntil > nowMillis;
    }

    private int applyWeakness(int value) {
        return isWeakened(System.currentTimeMillis()) ? CombatRules.weakened(value) : value;
    }
}
