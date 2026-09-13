package com.kowihere.mmo.path;

import com.kowihere.mmo.world.MapDef;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;

/**
 * A* over the collision grid, run on the server and never on the client: the
 * client asks to stand somewhere, the server decides whether and how that is
 * reachable. Four-way movement means every step costs 1, so the Manhattan
 * heuristic is exact-admissible and the search stays cheap.
 *
 * <p>Instances are not thread-safe and are not meant to be shared. Each map
 * thread keeps its own, which lets it reuse the scratch arrays between searches
 * instead of allocating a few thousand nodes on every click.
 */
public final class AStar {

    /** Guards against a pathological search on a large open map starving the tick. */
    private static final int MAX_EXPANSIONS = 20_000;

    private static final int[] DX = {0, -1, 1, 0};
    private static final int[] DY = {1, 0, 0, -1};

    private final MapDef map;
    private final int[] gScore;
    private final int[] cameFrom;
    private final int[] visitMark;
    private final int[] closedMark;
    private int generation;
    private long searches;

    public AStar(MapDef map) {
        this.map = map;
        int cells = map.width() * map.height();
        this.gScore = new int[cells];
        this.cameFrom = new int[cells];
        this.visitMark = new int[cells];
        this.closedMark = new int[cells];
    }

    /**
     * @return the tiles to walk, excluding the starting tile and ending at the
     *         goal, or an empty deque when the goal is unreachable or equal to
     *         the start. Never null — callers should not have to think about it.
     */
    public Deque<int[]> findPath(int startX, int startY, int goalX, int goalY) {
        searches++;
        Deque<int[]> path = new ArrayDeque<>();
        if (!map.walkable(startX, startY) || !map.walkable(goalX, goalY)) {
            return path;
        }
        if (startX == goalX && startY == goalY) {
            return path;
        }

        generation++;
        int width = map.width();
        int start = startY * width + startX;
        int goal = goalY * width + goalX;

        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        gScore[start] = 0;
        cameFrom[start] = -1;
        visitMark[start] = generation;
        open.add(new long[]{heuristic(startX, startY, goalX, goalY), start});

        int expansions = 0;
        while (!open.isEmpty()) {
            int current = (int) open.poll()[1];
            if (closedMark[current] == generation) {
                continue; // stale duplicate left behind by a cheaper re-discovery
            }
            closedMark[current] = generation;

            if (current == goal) {
                return reconstruct(goal);
            }
            if (++expansions > MAX_EXPANSIONS) {
                return path;
            }

            int cx = current % width;
            int cy = current / width;
            int nextG = gScore[current] + 1;

            for (int i = 0; i < 4; i++) {
                int nx = cx + DX[i];
                int ny = cy + DY[i];
                if (!map.walkable(nx, ny)) {
                    continue;
                }
                int neighbour = ny * width + nx;
                if (closedMark[neighbour] == generation) {
                    continue;
                }
                if (visitMark[neighbour] == generation && gScore[neighbour] <= nextG) {
                    continue;
                }
                visitMark[neighbour] = generation;
                gScore[neighbour] = nextG;
                cameFrom[neighbour] = current;
                open.add(new long[]{nextG + heuristic(nx, ny, goalX, goalY), neighbour});
            }
        }
        return path;
    }

    private Deque<int[]> reconstruct(int goal) {
        Deque<int[]> path = new ArrayDeque<>();
        int width = map.width();
        for (int node = goal; node != -1; node = cameFrom[node]) {
            path.addFirst(new int[]{node % width, node / width});
        }
        path.removeFirst(); // the tile the actor already stands on
        return path;
    }

    /**
     * How many searches this instance has run. Pathfinding is the most expensive
     * thing a client can ask the map thread to do, so the count is worth
     * watching - a number that tracks connected players is healthy, one that
     * tracks their click rate is not.
     */
    public long searches() {
        return searches;
    }

    private static int heuristic(int x, int y, int goalX, int goalY) {
        return Math.abs(x - goalX) + Math.abs(y - goalY);
    }
}
