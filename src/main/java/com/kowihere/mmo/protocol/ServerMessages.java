package com.kowihere.mmo.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Everything the server sends. Two shapes only: a full {@code init} that
 * describes the world from scratch, and a {@code delta} carrying just what
 * changed since version {@code v - 1}.
 *
 * <p>Empty collections are omitted from the wire ({@link JsonInclude}), which
 * keeps an idle tick's delta down to a few dozen bytes.
 */
public final class ServerMessages {

    private ServerMessages() {
    }

    /**
     * @param kind PLAYER, MOB or NPC - the client draws them differently
     * @param tier only a mob has one; null for the rest and omitted from the wire
     * @param npcKind only an NPC has one; the same arrangement
     * @param unconscious knocked out and waiting to come round. On the wire
     *                    because everyone can see it: without it, somebody
     *                    lying at the spawn is indistinguishable from somebody
     *                    whose connection has gone
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActorDto(int id, String name, int x, int y, String dir, boolean online,
                           String kind, String tier, String npcKind, int level, int hp, int maxHp,
                           boolean inFight, boolean unconscious) {
    }

    /** One blow. {@code hp} is the target's health after it, so bars need no arithmetic. */
    public record DamageDto(int attacker, int target, int amount, int hp) {
    }

    public record FightDto(int id, boolean inFight) {
    }

    /** One step in progress: the actor left ({@code fx},{@code fy}) and arrives at ({@code x},{@code y}) in {@code ms}. */
    public record MoveDto(int id, int fx, int fy, int x, int y, String dir, int ms) {
    }

    public record ChatDto(int id, String name, String text) {
    }

    public record PresenceDto(int id, boolean online) {
    }

    /**
     * A tile that leads somewhere else, and what the place on the other side is
     * called.
     *
     * <p>Deliberately says nothing about whether the viewer may use it. One
     * MapDto is built per map and shared by everybody on it, while a threshold
     * is a question about the one asking - so the rule stays on the server, and
     * a door that refuses says why when it is stepped on.
     */
    /**
     * @param takes what stepping through costs, by name, or null when it is
     *              free. A property of the door rather than of whoever is
     *              looking at it, which is why it may live in this shared
     *              object while the thresholds may not
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DoorDto(int x, int y, String name, String takes) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record MapDto(String id, String name, int width, int height, int tileSize,
                         List<String> collision, List<DoorDto> doors) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Init(String type, long v, MapDto map, int selfId, List<ActorDto> actors) {
        public Init(long v, MapDto map, int selfId, List<ActorDto> actors) {
            this("init", v, map, selfId, actors);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Delta(String type, long v, List<ActorDto> joined, List<Integer> left,
                        List<MoveDto> moved, List<ChatDto> chat, List<PresenceDto> presence,
                        List<DamageDto> damage, List<Integer> died, List<FightDto> fights) {
        public Delta(long v, List<ActorDto> joined, List<Integer> left,
                     List<MoveDto> moved, List<ChatDto> chat, List<PresenceDto> presence,
                     List<DamageDto> damage, List<Integer> died, List<FightDto> fights) {
            this("delta", v, joined, left, moved, chat, presence, damage, died, fights);
        }
    }

    /**
     * The state of your own character, sent only to its owner.
     *
     * <p>Deltas go to everyone on the map, so experience cannot travel in one:
     * another player's progress is nobody else's business, and a leak like that
     * is the kind nobody ever files a bug about.
     */
    /**
     * @param energy        what is in hand right now. It belongs to the fight,
     *                      so outside one this is always zero - which is the
     *                      whole difference between it and the mana it replaced
     * @param energyPerRound what a round of fighting is worth, which is what a
     *                      point in regeneration buys
     * @param skillPointPrice what putting a point into a skill costs *here* -
     *                      half of it while standing at this character's own
     *                      master. It rides in "you" rather than in "skills"
     *                      because it changes on walking up to somebody, while
     *                      "skills" goes out a few times an hour; a price in
     *                      there would be a stale answer, which is the same
     *                      mistake "affordable" was
     * @param skillResetPrice what taking every spent point back would cost, or
     *                      zero when there is nothing to take back
     */
    public record You(String type, String classId, String className,
                      int hp, int maxHp, int energy, int maxEnergy, int energyPerRound,
                      int level, long xp,
                      long xpThisLevel, long xpForNextLevel, long wakesAt, boolean dead,
                      int strength, int agility, int intellect, int unspentPoints, int skillPoints,
                      int skillPointPrice, int skillResetPrice,
                      int attack, int armor, int dodgePercent, int secondBlowPercent) {
        public You(String classId, String className,
                   int hp, int maxHp, int energy, int maxEnergy, int energyPerRound,
                   int level, long xp, long xpThisLevel,
                   long xpForNextLevel, long wakesAt, boolean dead,
                   int strength, int agility, int intellect, int unspentPoints, int skillPoints,
                   int skillPointPrice, int skillResetPrice,
                   int attack, int armor, int dodgePercent, int secondBlowPercent) {
            this("you", classId, className, hp, maxHp, energy, maxEnergy, energyPerRound,
                    level, xp, xpThisLevel,
                    xpForNextLevel, wakesAt, dead, strength, agility, intellect,
                    unspentPoints, skillPoints, skillPointPrice, skillResetPrice,
                    attack, armor, dodgePercent, secondBlowPercent);
        }
    }

    /**
     * One skill as its owner needs it: what it is, how far they have taken it,
     * and whether they could use it right now.
     *
     * @param rank    zero for one they may learn but have not
     * @param passive true when it works by being known rather than by being used
     *
     * <p>Deliberately no "can you afford it": that changes every round, and this
     * frame is sent a few times an hour. A field like that would be a stale
     * answer to a question the client can settle for itself, having both the
     * cost here and the energy in every {@code you}.
     */
    public record SkillDto(String id, String name, String description, int rank, int maxRank,
                           int cost, boolean passive) {
    }

    /** What a character has learned, sent only to its owner. */
    public record Skills(String type, int skillPoints, List<SkillDto> skills) {
        public Skills(int skillPoints, List<SkillDto> skills) {
            this("skills", skillPoints, skills);
        }
    }

    /**
     * One item, described well enough to be drawn and compared without the
     * client holding a copy of the item catalogue.
     *
     * @param slot where it is worn; for a bag item this is where it *would* go
     * @param wearable false when the character's level is too low, so the panel
     *                 can say why rather than letting the click be refused
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemDto(String id, String defId, String name, String slot, int requiresLevel,
                          boolean wearable, int strength, int agility, int intellect,
                          int attack, int armor) {
    }

    /**
     * What a character is wearing and carrying, sent only to its owner.
     *
     * <p>Kept out of {@code you} on purpose: {@code you} goes out at every
     * scratch, and a bag that changes a few times an hour has no business riding
     * along with it.
     */
    // Deliberately not NON_EMPTY, unlike the delta: an empty bag is news, and a
    // frame where "carried" is simply missing cannot be told apart from one
    // where it was never sent. The frame goes out a few times an hour, so there
    // is nothing to save by leaving fields out of it.
    public record Bag(String type, int capacity, List<ItemDto> carried, List<ItemDto> worn) {
        public Bag(int capacity, List<ItemDto> carried, List<ItemDto> worn) {
            this("bag", capacity, carried, worn);
        }
    }

    /**
     * One thing the player may say, and the number to send back to say it.
     *
     * <p>An index rather than the node it leads to: the client answers the
     * question it was asked, and cannot invent a different one.
     */
    public record OptionDto(int index, String text) {
    }

    /**
     * What somebody is saying to you, sent only to the person they are saying
     * it to. A frame with a null {@code text} closes the conversation - which
     * happens by walking away as often as by saying goodbye.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Dialogue(String type, int npcId, String name, String text,
                           List<OptionDto> options) {
        public Dialogue(int npcId, String name, String text, List<OptionDto> options) {
            this("dialogue", npcId, name, text, options);
        }

        /** The conversation is over, whoever ended it. */
        public static Dialogue closed(int npcId) {
            return new Dialogue(npcId, null, null, null);
        }
    }

    /**
     * One kind of money and how much of it there is.
     *
     * @param primary the money the game itself charges in, so the interface can
     *                label a price without keeping its own idea of which that is
     */
    public record CoinDto(String id, String name, String shortName, int amount, boolean primary) {
    }

    /**
     * What a character has to spend, sent only to its owner.
     *
     * <p>A list rather than a field per currency: money is plural here, and a
     * frame with a {@code gold} in it would have to be changed the day a second
     * currency appears - which is the same mistake as the column this avoids.
     */
    public record Purse(String type, List<CoinDto> coins) {
        public Purse(List<CoinDto> coins) {
            this("purse", coins);
        }
    }

    /**
     * One thing on a trader's shelf.
     *
     * @param price   what it costs here
     * @param buyback what this trader pays for one, which is a fraction of the
     *                same number - sent so the interface never has to do the
     *                arithmetic and get a different answer from the server
     */
    public record GoodsDto(String defId, String name, String slot, int requiresLevel,
                           int price, int buyback,
                           int strength, int agility, int intellect, int attack, int armor) {
    }

    /**
     * A trader's stall, sent only to whoever opened it. A frame with no goods
     * closes it, the same way an empty dialogue closes a conversation.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Shop(String type, int npcId, String name, String currencyId,
                       String currencyShort, List<GoodsDto> goods) {
        public Shop(int npcId, String name, String currencyId, String currencyShort,
                    List<GoodsDto> goods) {
            this("shop", npcId, name, currencyId, currencyShort, goods);
        }

        public static Shop closed(int npcId) {
            return new Shop(npcId, null, null, null, null);
        }
    }

    /**
     * A passage asking before it opens, sent only to whoever is standing in it.
     *
     * <p>The one question in the game that is not a conversation: a step onto a
     * tile that would burn something. A frame with no {@code takes} closes it,
     * the same way an empty dialogue closes a talk.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Passage(String type, int x, int y, String name, String takes) {
        public Passage(int x, int y, String name, String takes) {
            this("passage", x, y, name, takes);
        }

        public static Passage closed() {
            return new Passage(-1, -1, null, null);
        }
    }

    /** One item lying in a chest, and which tab of it. */
    public record KeptDto(int tab, ItemDto item) {
    }

    /**
     * One chest: how many tabs are open, what is in them, what money is kept
     * there, and what the next tab would cost.
     *
     * @param scope   "character" or "account", so the interface can say whose
     *                this is rather than working it out from its position
     * @param nextTab what the next tab costs, or -1 when there is none left to
     *                sell. The currency is named beside it, because the two
     *                chests are deliberately not paid for in the same money
     */
    public record ChestDto(String scope, int tabs, int tabSize, List<KeptDto> items,
                           List<CoinDto> coins, int nextTab, String currencyId,
                           String currencyShort) {
    }

    /**
     * A storekeeper's chests, sent only to whoever opened them. Like the shop
     * it sits beside: a frame with no chests closes the window.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Storage(String type, int npcId, String name, List<ChestDto> chests) {
        public Storage(int npcId, String name, List<ChestDto> chests) {
            this("storage", npcId, name, chests);
        }

        public static Storage closed(int npcId) {
            return new Storage(npcId, null, null);
        }
    }

    public record Error(String type, String message) {
        public Error(String message) {
            this("error", message);
        }
    }
}
