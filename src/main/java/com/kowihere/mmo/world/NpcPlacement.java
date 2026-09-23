package com.kowihere.mmo.world;

/**
 * Where one NPC stands, and which way it faces. The twin of {@link SpawnPoint},
 * minus everything that makes a spawn a spawn: an NPC does not wander, is not
 * killed and therefore never respawns.
 */
public record NpcPlacement(NpcDef npc, int x, int y, Direction facing) {
}
