package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.Attributes;
import com.kowihere.mmo.combat.CombatRules;
import com.kowihere.mmo.combat.Element;
import com.kowihere.mmo.combat.Strikes;
import com.kowihere.mmo.combat.Fight;
import com.kowihere.mmo.world.BlessingStat;
import com.kowihere.mmo.world.ClassDef;
import com.kowihere.mmo.world.Direction;
import com.kowihere.mmo.world.MobDef;
import com.kowihere.mmo.world.NpcDef;

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

    /**
     * What this actor is. They share the loop, the tile grid and the delta, and
     * almost nothing else.
     */
    enum Kind { PLAYER, MOB, NPC }

    final Kind kind;
    /** The creature this is a copy of, or null for a player. */
    final MobDef mob;
    /** The person or thing this is a copy of, or null for anything else. */
    final NpcDef npc;
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
     * What is blessing or cursing this character: the third layer of
     * statistics, after its own attributes and what it is wearing. Every number
     * below is computed with it, so a blessing works in a fight and on a road
     * without either place knowing it exists.
     */
    final Blessings blessings = new Blessings();

    /**
     * What is burning, freezing or poisoning this actor. Lives only as long as
     * the fight does, like {@link #energy}, and for the same reason: it is a
     * fact about a fight and not about a character.
     */
    final Ailments ailments = new Ailments();
    final Skills skills = new Skills();
    final Purse purse = new Purse();

    /**
     * The chest kept for this character, and the one kept for its account.
     *
     * <p>Not final: how many tabs each has is read back with the character, and
     * a chest has to know that before anything is put in it.
     *
     * <p>The account's chest is held here, on one character, which is only safe
     * because one character from an account is in the world at a time. That
     * rule is what stands in place of a lock.
     */
    Storage storage = new Storage(1);
    Storage accountStorage = new Storage(1);
    final Purse storagePurse = new Purse();
    final Purse accountPurse = new Purse();

    /**
     * Energy for skills. Belongs to the fight rather than to the character: it
     * starts every fight at zero and is gone when the fight ends, which is why
     * it is never written down.
     */
    int energy;
    /** The skill this character asked to use in the coming round, or null. */
    String pendingSkill;
    /** Points earned by levelling and not yet put into a skill. */
    int skillPoints;

    /**
     * Set when what this character carries has changed, as opposed to where it
     * stands. Walking about must not rewrite twenty rows of bag every time the
     * save interval comes round.
     */
    boolean itemsDirty;

    /** The same, for what it has learned. */
    boolean skillsDirty;

    /** And for what it has to spend, which changes on every kill and purchase. */
    boolean purseDirty;

    /** And for blessings, which change when one is drunk and when one runs out. */
    boolean blessingsDirty;

    /** And for each chest, which changes only while somebody is standing at one. */
    boolean depositDirty;
    boolean accountDepositDirty;

    /** The fight this actor is locked into, or null. Movement is refused while it is set. */
    Fight fight;

    /** An actor this one is walking towards in order to attack it; 0 for nobody. */
    int approaching;

    /**
     * When this character can be played again, in epoch millis; 0 when it can
     * be played now.
     *
     * <p>A stamp rather than a countdown, so closing the tab shortens nothing
     * and a restart of the server forgets nothing. It is written down for the
     * same reason.
     */
    long wakesAt;

    final int id;
    final String name;
    /** Case-folded name: the character's identity, and its primary key. */
    final String nameKey;
    /** Which account this character belongs to. */
    final long accountId;

    /** Set when this actor has moved since it was last handed to persistence. */
    boolean dirty;


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

    /**
     * Who this character is talking to, and where in that conversation they
     * are. Both live on the server: given node ids, a client could answer a
     * question it was never asked and arrive at whatever is at the bottom of
     * the tree.
     */
    int talkingTo;
    String atNode;

    /** A player. */
    Actor(int id, String name, String nameKey, long accountId, int x, int y, int stepTicks) {
        this(Kind.PLAYER, null, null, id, name, nameKey, accountId, x, y, stepTicks, false);
    }

    /**
     * Somebody, or something, that stands still and is talked to. It has no
     * account, is never saved, never fights and never moves - so of everything
     * above, it uses only a position and a name.
     */
    Actor(int id, NpcDef npc, int x, int y, Direction facing) {
        this(Kind.NPC, null, npc, id, npc.name(), "npc:" + npc.id() + "#" + id, 0, x, y, 1, false);
        this.dir = facing;
    }

    /**
     * A creature. It has no account and no session: it is derived from map data,
     * so it is never saved and never reaped for being offline.
     *
     * <p>One step's worth of ticks, which it will never spend: a creature does
     * not walk. The field is shared with characters, who do.
     */
    Actor(int id, MobDef mob, int x, int y, boolean respawns) {
        this(Kind.MOB, mob, null, id, mob.name(), "mob:" + mob.id() + "#" + id, 0, x, y,
                1, respawns);
        this.level = mob.level();
        this.hp = mob.hp();
    }

    private Actor(Kind kind, MobDef mob, NpcDef npc, int id, String name, String nameKey,
                  long accountId, int x, int y, int stepTicks, boolean respawns) {
        this.respawns = respawns;
        this.kind = kind;
        this.mob = mob;
        this.npc = npc;
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

    /**
     * The other question, and not the negation of the first one once there is a
     * third kind of actor. "Not a creature" and "a character somebody is
     * playing" were the same sentence for as long as there were only two kinds;
     * everything that saves, reaps, pays experience to or sends a private frame
     * to an actor means this one, and would quietly start doing it to
     * signposts if it went on asking the other.
     */
    boolean isPlayer() {
        return kind == Kind.PLAYER;
    }

    boolean isNpc() {
        return kind == Kind.NPC;
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
        if (isMob()) {
            return Attributes.FRESH;
        }
        Attributes total = attributes.plus(inventory.grantedAttributes());
        return new Attributes(
                blessed(total.strength(), BlessingStat.STRENGTH),
                blessed(total.agility(), BlessingStat.AGILITY),
                blessed(total.intellect(), BlessingStat.INTELLECT));
    }

    /**
     * One statistic, after everything blessing or cursing this character.
     *
     * <p>Flat points first, then the percentages on the sum of all of it - so
     * "-10% armour" is a tenth of the armour the character really has, plate
     * included, and not a tenth of the attributes it grew from.
     *
     * <p>Integer division truncates towards zero, which rounds a loss down and
     * a gain down with it: a tenth off fifteen is one point, not two. That is
     * deliberate for the one line the player did not choose. A penalty that
     * bites harder than it promises is how a lesson turns into a grudge.
     *
     * <p>Never below zero: a curse worth more than a character has takes
     * everything it has and stops, rather than turning the statistic into a
     * negative number that every formula would then quietly obey.
     */
    private int blessed(int base, BlessingStat stat) {
        long flat = base + blessings.total(stat);
        long withShare = flat + flat * blessings.percent(stat) / 100;
        return (int) Math.max(0, withShare);
    }

    int maxHp() {
        if (isNpc()) {
            // Nothing can hurt them, so a health bar over their head would be a
            // bar that is always full: decoration that says something untrue.
            return 0;
        }
        if (isMob()) {
            return mob.hp();
        }
        return Math.max(1, blessed(totalAttributes().maxHp()
                + (characterClass == null ? 0 : characterClass.hpBonus()), BlessingStat.MAX_HP));
    }

    int attack() {
        if (isMob()) {
            return mob.attack();
        }
        Attributes total = totalAttributes();
        int fromClass = characterClass == null
                ? total.attack()
                : characterClass.attackFrom(total);
        // Note that a blessing lowering strength has already lowered this once,
        // through the attributes above; a line on attack lowers it again. For
        // the death penalty that doubling is the point - it is meant to reach
        // the sword as well as the arm - but it means the attack a player loses
        // is rather more than the percentage on the line.
        return blessed(fromClass + inventory.grantedAttack(), BlessingStat.ATTACK);
    }

    /** How much of an opponent's armour this actor's blows pass through. */
    double armorIgnored() {
        return characterClass == null ? 0 : characterClass.armorIgnored();
    }

    int armor() {
        return isMob()
                ? mob.armor()
                : blessed(totalAttributes().armor() + inventory.grantedArmor(),
                        BlessingStat.ARMOR);
    }

    /**
     * A creature never evades; only characters have agility.
     *
     * <p>A blessing adds percentage points <em>before</em> the ceiling, which
     * therefore still holds. Letting a bottle past it would undo the rule it
     * exists for: a character nothing can hit is a fight that never ends.
     */
    double dodgeChance() {
        if (isMob() || chilled()) {
            return 0;
        }
        double chance = totalAttributes().dodgeChance()
                + blessings.total(BlessingStat.DODGE_POINTS) / 100.0;
        return Math.max(0, Math.min(Attributes.MAX_DODGE, chance));
    }

    double secondBlowChance() {
        if (isMob() || chilled()) {
            return 0;
        }
        double chance = totalAttributes().secondBlowChance()
                + blessings.total(BlessingStat.SECOND_BLOW_POINTS) / 100.0;
        return Math.max(0, Math.min(Attributes.MAX_SECOND_BLOW, chance));
    }

    /**
     * Health given back once a round, and only in a fight - as in the original.
     *
     * <p>A wound undoes half of it while it is open. That is what makes
     * bleeding worse than the same damage taken all at once, and the reason it
     * is worth being a separate element rather than a second kind of fire.
     */
    int healPerRound() {
        if (isMob()) {
            return 0;
        }
        int mends = Math.max(0, blessings.total(BlessingStat.HEAL_PER_ROUND));
        return ailments.has(Element.BLEED)
                ? mends * (100 - CombatRules.BLEEDING_UNDOES_MENDING) / 100
                : mends;
    }

    /**
     * How much of one element this actor shrugs off, in percentage points,
     * before anything is clamped.
     *
     * <p>Three sources, added: what a creature is made of, what a character is
     * wearing, and what is blessing or cursing it. Exactly the same shape as
     * every other statistic here, and for the same reason - nothing is stored,
     * so nothing can drift.
     */
    int resistanceTo(Element element) {
        if (isMob()) {
            return mob.resists().of(element);
        }
        return inventory.grantedResistance(element)
                + blessings.total(BlessingStat.against(element));
    }

    /** What this actor's blows carry beyond force, or null for plain force. */
    Strikes strikesWith() {
        return isMob() ? mob.strikes() : inventory.strikes();
    }

    /** Whether the cold has taken the quickness out of this one. */
    private boolean chilled() {
        return ailments.has(Element.FROST);
    }

    /**
     * Whether this character is still lying where it was killed.
     *
     * <p>Computed from the stamp every time it is asked rather than kept as a
     * flag, so there is no second copy of the answer to fall out of step - and
     * so a character that was unconscious when the process stopped is still
     * unconscious when it starts again.
     */
    boolean isUnconscious(long nowMillis) {
        return wakesAt > nowMillis;
    }
}
