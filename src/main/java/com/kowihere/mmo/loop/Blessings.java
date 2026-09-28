package com.kowihere.mmo.loop;

import com.kowihere.mmo.world.BlessingDef;
import com.kowihere.mmo.world.BlessingStat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What is currently blessing (or cursing) one character.
 *
 * <p>The third layer of statistics, after the character's own attributes and
 * what it is wearing. Like everything else under {@code loop} it is mutable and
 * unsynchronised: one map thread owns it.
 *
 * <p>Decides nothing. Whether a bottle may be drunk, how many may be held at
 * once and what a line is worth are rules of the game and live with the other
 * rules, in the map runner.
 */
final class Blessings {

    /** How many at once. Beyond this the panel stops fitting and so does the balance. */
    static final int MAX_ACTIVE = 3;

    /** One blessing and what is left of it. */
    static final class Active {
        final BlessingDef def;
        long remainingMs;

        Active(BlessingDef def, long remainingMs) {
            this.def = def;
            this.remainingMs = remainingMs;
        }
    }

    private final List<Active> active = new ArrayList<>();

    int size() {
        return active.size();
    }

    boolean isFull() {
        return active.size() >= MAX_ACTIVE;
    }

    boolean has(String defId) {
        return find(defId) != null;
    }

    private Active find(String defId) {
        for (Active one : active) {
            if (one.def.id().equals(defId)) {
                return one;
            }
        }
        return null;
    }

    /**
     * Lays one on, or sets the clock back on one already there.
     *
     * <p>Refreshing rather than stacking, so that five bottles of the same brew
     * are five times the time and never five times the strength.
     *
     * @return false when there was no room for another; the caller must say so
     */
    boolean lay(BlessingDef def) {
        Active already = find(def.id());
        if (already != null) {
            already.remainingMs = def.durationMs();
            return true;
        }
        if (isFull()) {
            return false;
        }
        active.add(new Active(def, def.durationMs()));
        return true;
    }

    /**
     * Counts down by however long a tick lasted.
     *
     * <p>Played time, not wall-clock time: this only ever runs while a map is
     * running the character, so logging out stops the clock without anybody
     * having to remember when it stopped.
     *
     * @return the ones that ran out, so the world can say so
     */
    List<BlessingDef> tick(long elapsedMs) {
        if (active.isEmpty()) {
            return List.of();
        }
        List<BlessingDef> gone = new ArrayList<>();
        for (Active one : List.copyOf(active)) {
            one.remainingMs -= elapsedMs;
            if (one.remainingMs <= 0) {
                active.remove(one);
                gone.add(one.def);
            }
        }
        return gone;
    }

    /** What every blessing together is worth on one statistic. */
    int total(BlessingStat stat) {
        int sum = 0;
        for (Active one : active) {
            sum += one.def.of(stat);
        }
        return sum;
    }

    List<Active> all() {
        return List.copyOf(active);
    }

    /** Puts back what the database remembered, without applying any rules to it. */
    void restore(List<StoredBlessing> stored, Map<String, BlessingDef> definitions) {
        for (StoredBlessing one : stored) {
            BlessingDef def = definitions.get(one.defId());
            if (def == null) {
                // Content was edited under a live database. Losing a blessing
                // is bad; refusing to let somebody play is worse.
                continue;
            }
            if (one.remainingMs() <= 0 || active.size() >= MAX_ACTIVE || has(def.id())) {
                continue;
            }
            active.add(new Active(def, Math.min(one.remainingMs(), def.durationMs())));
        }
    }

    List<StoredBlessing> stored() {
        List<StoredBlessing> all = new ArrayList<>(active.size());
        for (Active one : active) {
            all.add(new StoredBlessing(one.def.id(), one.remainingMs));
        }
        return List.copyOf(all);
    }
}
