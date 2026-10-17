package com.kowihere.mmo.world;

/**
 * What something in the world is <em>called</em>, visually: one name that a
 * wolf, a boar and a bear each have their own of.
 *
 * <p>No colour and no picture here either, for the same reason as
 * {@link TerrainDef}: this is the identity, and the drawing is the client's.
 * Content's job is to say "this creature looks like a wolf"; deciding what a
 * wolf looks like is a decision about pixels, and pixels have never crossed
 * this boundary.
 *
 * <p>It exists so that a typo cannot pass. Without a registry, a creature
 * whose look is spelt wrongly would simply be drawn as the generic one, which
 * reads in the file exactly like a creature that was given a look.
 */
public record LookDef(String id, String name) {
}
