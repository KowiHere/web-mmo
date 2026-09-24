package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.SkillDef;
import com.kowihere.mmo.world.SkillDefLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one character has learned, and how far.
 *
 * <p>Like the inventory beside it: mutable, unsynchronised, and touched only by
 * the map thread that owns the character. Nothing here decides whether a rank
 * is <em>allowed</em> - a class restriction and a ceiling are rules of the game
 * and live with the other rules. This only keeps count.
 */
final class Skills {

    private final Map<String, Integer> ranks = new LinkedHashMap<>();

    int rankOf(String skillId) {
        return ranks.getOrDefault(skillId, 0);
    }

    boolean knows(String skillId) {
        return rankOf(skillId) > 0;
    }

    void raise(String skillId) {
        ranks.merge(skillId, 1, Integer::sum);
    }

    /** How much faster this character charges, which is one skill's whole job. */
    int energyPerRound(Map<String, SkillDef> definitions) {
        SkillDef regeneration = definitions.get(SkillDefLoader.REGENERATION_ID);
        if (regeneration == null) {
            return 0;
        }
        return regeneration.energyPerRank() * rankOf(SkillDefLoader.REGENERATION_ID);
    }

    /** How far each known skill has been taken - what a reset would give back. */
    List<Integer> ranks() {
        return ranks.values().stream().filter(rank -> rank > 0).toList();
    }

    /** How many points are sitting in skills, which is how many come back. */
    int spent() {
        return ranks.values().stream().filter(rank -> rank > 0).mapToInt(Integer::intValue).sum();
    }

    /** Gives every rank back. The points are the caller's business, not this one's. */
    void forgetEverything() {
        ranks.clear();
    }

    /** Puts back what the database remembered, without applying any rules to it. */
    void restore(List<StoredSkill> stored, Map<String, SkillDef> definitions) {
        for (StoredSkill skill : stored) {
            SkillDef def = definitions.get(skill.skillId());
            if (def == null) {
                // Content was edited under a live database. Forgetting the skill
                // is better than refusing to let somebody play, and the points
                // are not lost: they were spent on something that no longer
                // exists, which is a content problem rather than a player's.
                continue;
            }
            ranks.put(skill.skillId(), Math.min(skill.rank(), def.maxRank()));
        }
    }

    List<StoredSkill> stored() {
        List<StoredSkill> all = new ArrayList<>(ranks.size());
        for (Map.Entry<String, Integer> entry : ranks.entrySet()) {
            if (entry.getValue() > 0) {
                all.add(new StoredSkill(entry.getKey(), entry.getValue()));
            }
        }
        return List.copyOf(all);
    }
}
