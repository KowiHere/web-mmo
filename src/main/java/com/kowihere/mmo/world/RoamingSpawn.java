package com.kowihere.mmo.world;

/**
 * An elite that turns up somewhere unpredictable rather than standing at a
 * marked spot. Every {@code everySeconds} the map rolls {@code chance} for it;
 * on success it appears on a random free tile, optionally with escorts.
 *
 * @param escortMobId a mob that accompanies it, or null for a lone elite
 */
public record RoamingSpawn(String mobId, int everySeconds, double chance,
                           String escortMobId, int escortCount) {
}
