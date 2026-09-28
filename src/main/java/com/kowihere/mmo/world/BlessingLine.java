package com.kowihere.mmo.world;

/**
 * One line of a blessing: how much, and whether that much is a flat number or a
 * share of what the character already has.
 *
 * <p>Two forms rather than one because they answer different questions. A
 * bottle bought at a stall is worth the same to everybody, so it is flat; a
 * penalty meant to sting the same at level two and level twenty has to be a
 * share, or it is either nothing at the top or ruinous at the bottom.
 *
 * <p>Content writes a number for the first form and a string ending in
 * {@code %} for the second: {@code "armor": -3} against {@code "armor": "-10%"}.
 *
 * @param amount flat points, or percentage points when {@code percent}
 */
public record BlessingLine(int amount, boolean percent) {

    public static BlessingLine flat(int amount) {
        return new BlessingLine(amount, false);
    }

    public static BlessingLine percent(int amount) {
        return new BlessingLine(amount, true);
    }

    public boolean takesSomethingAway() {
        return amount < 0;
    }

    /** What a tooltip says, after the sign: "6", "10%". */
    public String written() {
        return (amount > 0 ? "+" : "") + amount + (percent ? "%" : "");
    }
}
