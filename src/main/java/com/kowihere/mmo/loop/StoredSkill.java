package com.kowihere.mmo.loop;

/**
 * One learned skill as it is written down: which one, and how far.
 *
 * <p>What it costs, what it does and who may learn it all live in the content
 * files. Storing them here would be a second copy of the truth, and the two
 * would disagree the first time a skill is rebalanced.
 */
public record StoredSkill(String skillId, int rank) {
}
