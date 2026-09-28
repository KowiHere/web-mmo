package com.kowihere.mmo.loop;

/**
 * One blessing as it is written down: which one, and how much of it is left.
 *
 * <p>How much is <em>left</em>, not when it ends. The clock stops while nobody
 * is playing the character, so an absolute moment would quietly spend a bottle
 * somebody paid for while they were asleep. The knockout after death is the
 * other way round on purpose: that one is a penalty, and a penalty you can wait
 * out by logging off is not one.
 */
public record StoredBlessing(String defId, long remainingMs) {
}
