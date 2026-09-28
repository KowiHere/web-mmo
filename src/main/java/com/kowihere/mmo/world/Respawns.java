package com.kowihere.mmo.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every place in the world where the dead wake up, and which of them is nearest
 * to wherever somebody was killed.
 *
 * <p>The author marks the towns - a {@code "respawnPoint"} on the map that has
 * one - and the world works out the rest. Naming the right town again on every
 * map around it is the same fact written a dozen times, and the dozenth copy is
 * always the one nobody updates when a new town is built.
 *
 * <p>Nearest means <strong>fewest passages</strong> first: a town two maps away
 * beats one three maps away, whatever the tiles say. Only between towns that
 * are equally many passages off does it come down to walking distance, and that
 * last part is measured by the map somebody died on, which is the only map that
 * knows where the body is. This class answers with every town at the smallest
 * number of passages and the door to leave by for each - the tie is broken
 * there.
 *
 * <p>Passages are counted as though every one of them were open. A locked door
 * is a rule about walking through it, and nobody walks here: dying is a
 * teleport, and a character killed behind a locked door would otherwise have
 * nowhere at all to wake up.
 *
 * <p>Built once at startup, then read from every map thread: immutable.
 */
public final class Respawns {

    /**
     * One way home: the place, how many passages away it is, and the tile on
     * the map somebody died on where that road begins.
     *
     * <p>For a waking place on that same map the road begins at the waking
     * place itself, and is nought passages long.
     */
    public record Way(RespawnPoint point, int passages, int leaveByX, int leaveByY) {
    }

    /** One map reached by the search, and how the road to it started. */
    private record Step(String mapId, int passages, int leaveByX, int leaveByY) {

        /** What has already been tried: this map, entered by that door. */
        String road() {
            return mapId + "#" + leaveByX + "," + leaveByY;
        }
    }

    private final Map<String, List<Way>> byMap;

    private Respawns(Map<String, List<Way>> byMap) {
        this.byMap = Map.copyOf(byMap);
    }

    /** A world that knows of no waking places: every map answers for itself. */
    public static final Respawns NONE = new Respawns(Map.of());

    /**
     * Works out, for every map, which waking places are nearest to it.
     *
     * <p>A breadth-first walk of the passages, outwards from each map. There
     * are dozens of maps and this runs once at startup, so the obvious
     * algorithm is the right one.
     */
    public static Respawns of(Map<String, MapDef> maps) {
        Map<String, List<Way>> byMap = new HashMap<>();
        for (MapDef from : maps.values()) {
            List<Way> ways = nearestTo(from, maps);
            if (!ways.isEmpty()) {
                byMap.put(from.id(), ways);
            }
        }
        return new Respawns(byMap);
    }

    /**
     * Every waking place at the smallest number of passages from this map, and
     * for each, the door on <em>this</em> map that starts a shortest road to
     * it.
     *
     * <p>Two doors leading the same distance to the same place are both kept.
     * They are two different walks for whoever died, and which of them is
     * shorter depends on where the body is - a question only the map that
     * holds the body can answer.
     */
    private static List<Way> nearestTo(MapDef from, Map<String, MapDef> maps) {
        if (from.offersRespawn() != null) {
            // Killed in the town itself. Nothing beats nought passages, so the
            // search stops before it starts.
            RespawnPoint here = from.offersRespawn();
            return List.of(new Way(here, 0, here.x(), here.y()));
        }

        List<Way> found = new ArrayList<>();
        // Roads already walked, each one a map together with the door out of
        // here that reached it. The door is part of the key on purpose: two
        // doors to the same place are two different roads, and the shorter of
        // them depends on where the body is.
        //
        // Breadth first, so the first time a road is walked is also the
        // shortest it will ever be - which is why seeing it again is enough to
        // drop it, with no distance to compare.
        Set<String> walked = new HashSet<>();
        Deque<Step> queue = new ArrayDeque<>();
        for (Door door : from.doors()) {
            enqueue(queue, walked, new Step(door.toMap(), 1, door.x(), door.y()));
        }

        int best = Integer.MAX_VALUE;
        while (!queue.isEmpty()) {
            Step step = queue.poll();
            if (step.passages() > best) {
                // A breadth-first queue only ever grows outwards, so everything
                // left in it is at least this far away.
                break;
            }
            MapDef reached = maps.get(step.mapId());
            if (reached == null) {
                continue; // a door to nowhere; the loader refuses those
            }
            if (reached.offersRespawn() != null) {
                best = step.passages();
                found.add(new Way(reached.offersRespawn(), step.passages(),
                        step.leaveByX(), step.leaveByY()));
                continue;
            }
            for (Door door : reached.doors()) {
                enqueue(queue, walked, new Step(door.toMap(), step.passages() + 1,
                        step.leaveByX(), step.leaveByY()));
            }
        }
        return List.copyOf(found);
    }

    private static void enqueue(Deque<Step> queue, Set<String> walked, Step step) {
        if (walked.add(step.road())) {
            queue.add(step);
        }
    }

    /**
     * The nearest waking places for somebody killed on this map, or an empty
     * list when the world has none it can reach - in which case the map's own
     * answer stands, as it did before any of this existed.
     */
    public List<Way> from(String mapId) {
        return byMap.getOrDefault(mapId, List.of());
    }
}
