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
     * @param kind PLAYER or MOB - the client draws them differently
     * @param tier only a mob has one; null for players and omitted from the wire
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActorDto(int id, String name, int x, int y, String dir, boolean online,
                           String kind, String tier, int level, int hp, int maxHp,
                           boolean inFight) {
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

    public record MapDto(String id, String name, int width, int height, int tileSize, List<String> collision) {
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
     */
    public record You(String type, String classId, String className,
                      int hp, int maxHp, int energy, int maxEnergy, int energyPerRound,
                      int level, long xp,
                      long xpThisLevel, long xpForNextLevel, long weakenedUntil, boolean dead,
                      int strength, int agility, int intellect, int unspentPoints, int skillPoints,
                      int attack, int armor, int dodgePercent, int secondBlowPercent) {
        public You(String classId, String className,
                   int hp, int maxHp, int energy, int maxEnergy, int energyPerRound,
                   int level, long xp, long xpThisLevel,
                   long xpForNextLevel, long weakenedUntil, boolean dead,
                   int strength, int agility, int intellect, int unspentPoints, int skillPoints,
                   int attack, int armor, int dodgePercent, int secondBlowPercent) {
            this("you", classId, className, hp, maxHp, energy, maxEnergy, energyPerRound,
                    level, xp, xpThisLevel,
                    xpForNextLevel, weakenedUntil, dead, strength, agility, intellect,
                    unspentPoints, skillPoints, attack, armor, dodgePercent, secondBlowPercent);
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

    public record Error(String type, String message) {
        public Error(String message) {
            this("error", message);
        }
    }
}
