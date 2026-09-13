package com.kowihere.mmo.world;

/** A fixed place where one creature stands, and returns to after wandering. */
public record SpawnPoint(String mobId, int x, int y) {
}
