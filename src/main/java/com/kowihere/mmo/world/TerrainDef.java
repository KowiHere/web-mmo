package com.kowihere.mmo.world;

/**
 * One kind of ground, as far as the server is concerned: a name and the
 * character that stands for it in a map file.
 *
 * <p>Deliberately no colour, no texture, no picture. What ground <em>looks
 * like</em> belongs to whoever is drawing it, and the server has never had an
 * opinion about that - it says "MOB" and "HEALER" and lets the client decide
 * what those look like. This is the same boundary, one step further into
 * content.
 *
 * <p>It says nothing about walking either. Whether a tile can be stood on is
 * the collision grid's business and stays there, so water is not unwalkable
 * because it is water - it is unwalkable because somebody drew a wall there.
 * The two grids answer different questions and must be able to disagree: that
 * is what a bridge is.
 *
 * @param symbol the one character a map file uses for it
 */
public record TerrainDef(String id, String symbol, String name) {

    public char mark() {
        return symbol.charAt(0);
    }
}
