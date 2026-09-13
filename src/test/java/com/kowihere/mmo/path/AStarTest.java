package com.kowihere.mmo.path;

import com.kowihere.mmo.world.MapDef;
import com.kowihere.mmo.world.MapDefLoader;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;

class AStarTest {

    private static final MapDef MAP = new MapDefLoader().loadAll().get("starter");

    @Test
    void walksStraightAcrossOpenGround() {
        Deque<int[]> path = new AStar(MAP).findPath(1, 1, 5, 1);

        assertThat(path).hasSize(4);
        assertThat(path.getLast()).containsExactly(5, 1);
    }

    @Test
    void routesAroundAWallInsteadOfThroughIt() {
        // (8,8)..(17,8) is a solid wall; getting from above it to below it must
        // cost more than the straight-line distance, and must never step on it.
        Deque<int[]> path = new AStar(MAP).findPath(12, 7, 12, 9);

        assertThat(path).isNotEmpty();
        assertThat(path.getLast()).containsExactly(12, 9);
        assertThat(path.size()).isGreaterThan(2);
        assertThat(path).allSatisfy(step -> assertThat(MAP.walkable(step[0], step[1])).isTrue());
    }

    @Test
    void everyStepIsAdjacentToTheOneBefore() {
        Deque<int[]> path = new AStar(MAP).findPath(2, 1, 25, 18);

        int[] previous = {2, 1};
        for (int[] step : path) {
            int distance = Math.abs(step[0] - previous[0]) + Math.abs(step[1] - previous[1]);
            assertThat(distance).as("step %s,%s follows %s,%s", step[0], step[1], previous[0], previous[1])
                    .isEqualTo(1);
            previous = step;
        }
        assertThat(previous).containsExactly(25, 18);
    }

    @Test
    void returnsNothingForAnUnreachableOrBlockedGoal() {
        AStar pathfinder = new AStar(MAP);

        assertThat(pathfinder.findPath(1, 1, 0, 0)).isEmpty();          // goal is a wall
        assertThat(pathfinder.findPath(1, 1, 999, 999)).isEmpty();      // goal is off the map
        assertThat(pathfinder.findPath(1, 1, 1, 1)).isEmpty();          // already there
    }

    @Test
    void reusesScratchStateAcrossSearches() {
        // The pathfinder keeps its arrays between calls; a stale generation marker
        // would make the second search return a corrupted path or none at all.
        AStar pathfinder = new AStar(MAP);
        pathfinder.findPath(1, 1, 25, 18);
        Deque<int[]> second = pathfinder.findPath(1, 1, 5, 1);

        assertThat(second).hasSize(4);
        assertThat(second.getLast()).containsExactly(5, 1);
    }
}
