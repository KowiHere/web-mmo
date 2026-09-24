package com.kowihere.mmo.loop;

import com.kowihere.mmo.combat.Attributes;

import java.util.List;

/**
 * An immutable copy of what is worth keeping about one character.
 *
 * <p>This is what leaves the map thread. It is a copy on purpose: handing out
 * the live {@link Actor} would let another thread read fields while the tick is
 * writing them, which is exactly the single-writer rule the loop is built on.
 *
 * @param items everything the character owns, or <strong>null</strong> when
 *              nothing about it has changed since the last save. Null means
 *              "leave the item rows alone": walking across a map writes a
 *              position every save interval, and rewriting a bag nobody touched
 *              would be twenty pointless rows every time.
 * @param skills the same, for what has been learned. Null means unchanged.
 * @param coins  and again for money. Null means unchanged, which is every save
 *               by somebody who has neither killed nor bought anything since.
 * @param accountId whose account this character is on - which the chest shared
 *                  by that account is written against
 * @param deposit the character's own chest, or null when nothing in it moved
 * @param accountDeposit the account's chest, same rule
 */
public record ActorSnapshot(String nameKey, String name, String mapId, int x, int y, String dir,
                            int level, long xp, int hp, long wakesAt,
                            Attributes attributes, int unspentPoints, List<StoredItem> items,
                            String classId, int skillPoints,
                            List<StoredSkill> skills, List<StoredCoin> coins,
                            long accountId, Deposit deposit, Deposit accountDeposit) {
}
