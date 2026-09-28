package com.kowihere.mmo.world;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One blessing: a name, a clock, and several lines at once.
 *
 * <p>Not one effect but a <strong>list</strong> of them, and any of them may be
 * negative - which is what turns a blessing from a present into a bargain. That
 * shape is taken from the original, where a single blessing grants four or five
 * things at a time; the negative lines are ours.
 *
 * <p>A line is flat or a percentage; see {@link BlessingLine}. Nothing here
 * knows the difference beyond keeping the two apart, because what a percentage
 * is a percentage <em>of</em> is a rule of the game and lives with the other
 * rules, on the actor.
 *
 * @param minutes how long it lasts <em>of play</em>. The clock stops when you
 *                log out, so a bottle is worth the same whenever it is drunk
 * @param lines   what it changes, by how much; never empty, never all zeroes
 */
public record BlessingDef(String id, String name, Rarity rarity, int minutes,
                          int requiresLevel, Map<BlessingStat, BlessingLine> lines) {

    public BlessingDef {
        lines = Map.copyOf(lines);
    }

    public long durationMs() {
        return minutes * 60_000L;
    }

    /** Flat points on one statistic; zero when this blessing's line is a share. */
    public int of(BlessingStat stat) {
        BlessingLine line = lines.get(stat);
        return line == null || line.percent() ? 0 : line.amount();
    }

    /** Percentage points on one statistic; zero when the line is flat. */
    public int percentOf(BlessingStat stat) {
        BlessingLine line = lines.get(stat);
        return line == null || !line.percent() ? 0 : line.amount();
    }

    /** Whether anything here takes something away. Worth saying in a tooltip. */
    public boolean costsSomething() {
        return lines.values().stream().anyMatch(BlessingLine::takesSomethingAway);
    }

    /** The lines in the order the statistics are declared, so two tooltips agree. */
    public Map<BlessingStat, BlessingLine> ordered() {
        Map<BlessingStat, BlessingLine> ordered = new LinkedHashMap<>();
        for (BlessingStat stat : BlessingStat.values()) {
            BlessingLine line = lines.get(stat);
            if (line != null && line.amount() != 0) {
                ordered.put(stat, line);
            }
        }
        return ordered;
    }
}
