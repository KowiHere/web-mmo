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
 * @param minutes how long it lasts <em>of play</em>. The clock stops when you
 *                log out, so a bottle is worth the same whenever it is drunk
 * @param lines   what it changes, by how much; never empty, never all zeroes
 */
public record BlessingDef(String id, String name, Rarity rarity, int minutes,
                          int requiresLevel, Map<BlessingStat, Integer> lines) {

    public BlessingDef {
        lines = Map.copyOf(lines);
    }

    public long durationMs() {
        return minutes * 60_000L;
    }

    public int of(BlessingStat stat) {
        return lines.getOrDefault(stat, 0);
    }

    /** Whether anything here takes something away. Worth saying in a tooltip. */
    public boolean costsSomething() {
        return lines.values().stream().anyMatch(value -> value < 0);
    }

    /** The lines in the order the statistics are declared, so two tooltips agree. */
    public Map<BlessingStat, Integer> ordered() {
        Map<BlessingStat, Integer> ordered = new LinkedHashMap<>();
        for (BlessingStat stat : BlessingStat.values()) {
            Integer value = lines.get(stat);
            if (value != null && value != 0) {
                ordered.put(stat, value);
            }
        }
        return ordered;
    }
}
