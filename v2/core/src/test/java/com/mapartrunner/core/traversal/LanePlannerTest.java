package com.mapartrunner.core.traversal;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.BlockSpec;
import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.model.BuildPlan.GridSize;
import com.mapartrunner.core.model.BuildPlan.Target;
import com.mapartrunner.core.traversal.LanePlanner.Corridor;
import com.mapartrunner.core.traversal.LanePlanner.Direction;
import com.mapartrunner.core.traversal.LanePlanner.ExpectedFloor;
import com.mapartrunner.core.traversal.LanePlanner.Lane;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LanePlannerTest {
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final ExpectedFloor EMPTY_FLOOR = new ExpectedFloor(Map.of());
    private final LanePlanner planner = new LanePlanner();

    @ParameterizedTest
    @CsvSource({"1,0", "2,1", "3,2", "4,2", "5,2", "9,2:6", "10,2:7", "11,2:7:8", "15,2:7:12"})
    void exactDefaultCentersCorrectNarrowBacktrackingAndPreserveFinalOverlap(int depth, String centers) {
        assertEquals(centers, planner.plan(plan(20, depth), 2, 5).stream()
                .map(lane -> Integer.toString(lane.start().z())).reduce((a, b) -> a + ":" + b).orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"3,15", "15,3", "11,11"})
    void startsAtNorthWestAndAlwaysSweepsEastWestRegardlessOfAspectRatio(int width, int depth) {
        var lanes = planner.plan(plan(width, depth), 2, 5);
        assertEquals(new GridPos(0, 0, Math.min(2, depth - 1)), lanes.getFirst().start());
        for (Lane lane : lanes) {
            assertTrue(lane.direction() == Direction.EAST || lane.direction() == Direction.WEST);
            assertEquals(width - 1, Math.max(lane.start().x(), lane.end().x()));
            assertEquals(0, Math.min(lane.start().x(), lane.end().x()));
        }
    }

    @Test
    void fullSourceDimensionsIncludingAirBordersDefineSerpentineCorridors() {
        var plan = new BuildPlan("air-borders", new GridSize(13, 8, 11), List.of(target(91, 6, 3, 5)));
        var lanes = planner.plan(plan, 2, 5);
        assertEquals(List.of(0, 1, 2), lanes.stream().map(Lane::id).toList());
        assertEquals(List.of(Direction.EAST, Direction.WEST, Direction.EAST), lanes.stream().map(Lane::direction).toList());
        assertEquals(List.of(new GridPos(0, 3, 2), new GridPos(12, 3, 7), new GridPos(0, 3, 8)), lanes.stream().map(Lane::start).toList());
        assertEquals(List.of(new GridPos(12, 3, 2), new GridPos(0, 3, 7), new GridPos(12, 3, 8)), lanes.stream().map(Lane::end).toList());
        assertEquals(List.of(new Corridor(0, 12, 0, 4), new Corridor(0, 12, 5, 9), new Corridor(0, 12, 6, 10)), lanes.stream().map(Lane::corridor).toList());
    }

    @Test
    void widthOneAllowsIdenticalEndpointsInBothDirections() {
        var lanes = planner.plan(plan(1, 11), 2, 5);
        assertEquals(3, lanes.size());
        for (Lane lane : lanes) assertEquals(lane.start(), lane.end());
        assertEquals(Direction.WEST, lanes.get(1).direction());
    }

    @Test
    void zeroHalfWidthWithUnitStrideCoversEverySourceRow() {
        var lanes = planner.plan(plan(4, 4), 0, 1);
        assertEquals(List.of(0, 1, 2, 3), lanes.stream().map(lane -> lane.start().z()).toList());
        for (Lane lane : lanes) assertEquals(lane.corridor().minZ(), lane.corridor().maxZ());
    }

    @Test
    void smallerStrideOverlapsWithoutDuplicateOrBackwardCenters() {
        var lanes = planner.plan(plan(4, 11), 2, 3);
        assertEquals(List.of(2, 5, 8), lanes.stream().map(lane -> lane.start().z()).toList());
        assertTrue(lanes.get(0).corridor().maxZ() >= lanes.get(1).corridor().minZ());
    }

    @Test
    void validStridesCoverAllRowsWithStrictlyIncreasingCentersAndStableIds() {
        for (int halfWidth = 0; halfWidth <= 4; halfWidth++) {
            for (int stride = 1; stride <= 2 * halfWidth + 1; stride++) {
                for (int depth = 1; depth <= 35; depth++) {
                    var lanes = planner.plan(plan(3, depth), halfWidth, stride);
                    var covered = new HashSet<Integer>();
                    int previousCenter = -1;
                    for (int i = 0; i < lanes.size(); i++) {
                        Lane lane = lanes.get(i);
                        assertEquals(i, lane.id());
                        assertTrue(lane.start().z() > previousCenter);
                        previousCenter = lane.start().z();
                        assertTrue(lane.corridor().minZ() >= 0);
                        assertTrue(lane.corridor().maxZ() < depth);
                        for (int z = lane.corridor().minZ(); z <= lane.corridor().maxZ(); z++) covered.add(z);
                    }
                    assertEquals(depth, covered.size());
                }
            }
        }
    }

    @Test
    void geometryArithmeticDoesNotOverflowAtIntegerLimits() {
        var lanes = planner.plan(plan(Integer.MAX_VALUE, Integer.MAX_VALUE), 1_000_000_000, 2_000_000_001);
        assertEquals(List.of(1_000_000_000, 1_147_483_646), lanes.stream().map(lane -> lane.start().z()).toList());
        assertEquals(new Corridor(0, Integer.MAX_VALUE - 1, 147_483_646, Integer.MAX_VALUE - 1), lanes.getLast().corridor());
        var wide = planner.plan(plan(1, Integer.MAX_VALUE), Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(1, wide.size());
        assertEquals(Integer.MAX_VALUE - 1, wide.getFirst().start().z());
        assertEquals(new Corridor(0, 0, 0, Integer.MAX_VALUE - 1), wide.getFirst().corridor());
    }

    @ParameterizedTest
    @CsvSource({"-1,1", "2,0", "2,-1", "2,6", "0,2"})
    void rejectsInvalidSettingsEvenWhenTargetsAreEmpty(int halfWidth, int stride) {
        for (BuildPlan plan : List.of(plan(3, 3), new BuildPlan("empty", new GridSize(3, 3, 3), List.of()))) {
            var error = assertThrows(IllegalArgumentException.class, () -> planner.plan(plan, halfWidth, stride));
            assertFalse(error.getMessage().isBlank());
        }
    }

    @Test
    void emptyPlanReturnsImmutableEmptyListAndNullPlanFails() {
        var lanes = planner.plan(new BuildPlan("empty", new GridSize(13, 8, 11), List.of()), 2, 5);
        assertTrue(lanes.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> lanes.add(directLane(Direction.EAST)));
        assertThrows(NullPointerException.class, () -> planner.plan(null, 2, 5));
    }

    @Test
    void multipleOccupiedYLevelsAreRejectedDespiteValidSourceContainment() {
        var plan = new BuildPlan("layers", new GridSize(3, 8, 3), List.of(target(2, 1, 2, 1), target(7, 1, 5, 1)));
        var error = assertThrows(IllegalArgumentException.class, () -> planner.plan(plan, 2, 5));
        assertTrue(error.getMessage().contains("Y"));
    }

    @Test
    void everyLaneSharesOneWholePlanIndexWithOriginalSparseIdsAndBlockProperties() {
        var log = new Target(700, new GridPos(7, 4, 10), new BlockSpec("minecraft:oak_log", Map.of("axis", "x")));
        var targets = List.of(target(2, 1, 4, 0), log, target(Integer.MAX_VALUE, 5, 4, 5));
        var lanes = planner.plan(new BuildPlan("sparse", new GridSize(9, 8, 11), targets), 2, 5);
        ExpectedFloor floor = lanes.getFirst().expectedFloor();
        assertEquals(3, floor.targetsByPosition().size());
        for (Target target : targets) {
            assertEquals(target, floor.targetsByPosition().get(target.relativePos()));
            assertTrue(floor.containsTargetId(target.id()));
        }
        assertFalse(floor.containsTargetId(0));
        assertFalse(floor.containsTargetId(-1));
        assertEquals(Map.of("axis", "x"), floor.targetsByPosition().get(log.relativePos()).block().properties());
        for (Lane lane : lanes) assertSame(floor, lane.expectedFloor());
        assertThrows(UnsupportedOperationException.class, () -> floor.targetsByPosition().clear());
        assertThrows(UnsupportedOperationException.class, lanes::clear);
    }

    @Test
    void independentEqualPlansAndReorderedTargetsHaveEqualGeometryAndFloorValues() {
        var first = target(2, 1, 0, 0);
        var second = target(900, 5, 0, 10);
        var dimensions = new GridSize(9, 4, 11);
        var a = planner.plan(new BuildPlan("a", dimensions, List.of(first, second)), 2, 5);
        var b = planner.plan(new BuildPlan("b", dimensions, List.of(second, first)), 2, 5);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotSame(a.getFirst().expectedFloor(), b.getFirst().expectedFloor());
        assertEquals(a.getFirst().expectedFloor().hashCode(), b.getFirst().expectedFloor().hashCode());
    }

    @Test
    void expectedFloorCopiesMapAndDerivesIdMembershipFromItsSnapshot() {
        Target target = target(44, -3, 7, 8);
        var source = new HashMap<>(Map.of(target.relativePos(), target));
        var floor = new ExpectedFloor(source);
        source.clear();
        source.put(new GridPos(0, 0, 0), target(88, 0, 0, 0));
        assertEquals(Map.of(target.relativePos(), target), floor.targetsByPosition());
        assertTrue(floor.containsTargetId(44));
        assertFalse(floor.containsTargetId(88));
        assertEquals(new ExpectedFloor(Map.of(target.relativePos(), target)), floor);
        assertNotEquals(EMPTY_FLOOR, floor);
        assertNotEquals(null, floor);
        assertNotEquals(Map.of(target.relativePos(), target), floor);
    }

    @Test
    void floorValueEqualityIncludesIdsPositionsAndFullBlockProperties() {
        var position = new GridPos(1, 0, 2);
        var log = new BlockSpec("minecraft:oak_log", Map.of("axis", "x"));
        var floor = new ExpectedFloor(Map.of(position, new Target(7, position, log)));
        assertNotEquals(floor, new ExpectedFloor(Map.of(position, new Target(8, position, log))));
        var otherPosition = new GridPos(2, 0, 2);
        assertNotEquals(floor, new ExpectedFloor(Map.of(otherPosition, new Target(7, otherPosition, log))));
        assertNotEquals(floor, new ExpectedFloor(Map.of(position, new Target(7, position,
                new BlockSpec("minecraft:oak_log", Map.of("axis", "z"))))));
    }

    @Test
    void expectedFloorRejectsMalformedMaps() {
        assertThrows(NullPointerException.class, () -> new ExpectedFloor(null));
        var nullKey = new HashMap<GridPos, Target>();
        nullKey.put(null, target(1, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new ExpectedFloor(nullKey));
        var nullValue = new HashMap<GridPos, Target>();
        nullValue.put(new GridPos(0, 0, 0), null);
        assertThrows(NullPointerException.class, () -> new ExpectedFloor(nullValue));
        assertThrows(IllegalArgumentException.class, () -> new ExpectedFloor(Map.of(new GridPos(1, 0, 0), target(1, 0, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new ExpectedFloor(Map.of(new GridPos(0, 0, 0), target(1, 0, 0, 0), new GridPos(1, 0, 0), target(1, 1, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new ExpectedFloor(Map.of(new GridPos(0, 0, 0), target(1, 0, 0, 0), new GridPos(1, 1, 0), target(2, 1, 1, 0))));
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void directLanesAcceptCardinalGeometryNonzeroIdsAndEmptyFloor(Direction direction) {
        Lane lane = directLane(direction);
        assertEquals(37, lane.id());
        assertSame(EMPTY_FLOOR, lane.expectedFloor());
        assertDoesNotThrow(() -> new Lane(9, direction, lane.start(), lane.start(), lane.corridor(), EMPTY_FLOOR));
    }

    @ParameterizedTest
    @CsvSource({"EAST,-90,true,1,1", "WEST,90,true,-1,-1", "SOUTH,0,false,1,-1", "NORTH,180,false,-1,1"})
    void directionsExposeFixedYawAxesAndRelativeSigns(Direction direction, double yaw, boolean alongX, int forward, int lateral) {
        assertEquals(yaw, direction.yawDegrees());
        assertEquals(alongX, direction.alongX());
        assertEquals(forward, direction.forwardSign());
        assertEquals(lateral, direction.lateralStrafeSign());
        assertEquals(alongX ? -2.5 : 7.25, direction.progressCoordinate(-2.5, 7.25));
        assertEquals(alongX ? 7.25 : -2.5, direction.lateralCoordinate(-2.5, 7.25));
    }

    @Test
    void corridorRequiresOrderedInclusiveBounds() {
        assertDoesNotThrow(() -> new Corridor(-1, -1, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new Corridor(2, 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Corridor(0, 0, 2, 1));
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void laneRejectsReverseAndNonCardinalEndpoints(Direction direction) {
        Lane good = directLane(direction);
        assertThrows(IllegalArgumentException.class, () -> new Lane(0, direction, good.end(), good.start(), good.corridor(), EMPTY_FLOOR));
        assertThrows(IllegalArgumentException.class, () -> new Lane(0, direction, new GridPos(0, 0, 0), new GridPos(1, 0, 1), good.corridor(), EMPTY_FLOOR));
    }

    @Test
    void laneRejectsNegativeIdNullPartsYDisagreementAndEndpointsOutsideCorridor() {
        var a = new GridPos(0, 0, 0);
        var b = new GridPos(2, 0, 0);
        var corridor = new Corridor(0, 2, 0, 2);
        assertThrows(IllegalArgumentException.class, () -> new Lane(-1, Direction.EAST, a, b, corridor, EMPTY_FLOOR));
        assertThrows(NullPointerException.class, () -> new Lane(0, null, a, b, corridor, EMPTY_FLOOR));
        assertThrows(NullPointerException.class, () -> new Lane(0, Direction.EAST, null, b, corridor, EMPTY_FLOOR));
        assertThrows(NullPointerException.class, () -> new Lane(0, Direction.EAST, a, null, corridor, EMPTY_FLOOR));
        assertThrows(NullPointerException.class, () -> new Lane(0, Direction.EAST, a, b, null, EMPTY_FLOOR));
        assertThrows(NullPointerException.class, () -> new Lane(0, Direction.EAST, a, b, corridor, null));
        assertThrows(IllegalArgumentException.class, () -> new Lane(0, Direction.EAST, a, new GridPos(2, 1, 0), corridor, EMPTY_FLOOR));
        assertThrows(IllegalArgumentException.class, () -> new Lane(0, Direction.EAST, new GridPos(-1, 0, 0), b, corridor, EMPTY_FLOOR));
        assertThrows(IllegalArgumentException.class, () -> new Lane(0, Direction.EAST, a, new GridPos(3, 0, 0), corridor, EMPTY_FLOOR));
        var floor = new ExpectedFloor(Map.of(new GridPos(1, 4, 1), target(99, 1, 4, 1)));
        assertThrows(IllegalArgumentException.class, () -> new Lane(0, Direction.EAST, a, b, corridor, floor));
        assertDoesNotThrow(() -> new Lane(0, Direction.EAST, new GridPos(0, 4, 0), new GridPos(2, 4, 0), corridor, floor));
    }

    @Test
    void directLaneDirectionValidationDoesNotOverflowAcrossExtremeCoordinates() {
        var corridor = new Corridor(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0);
        var min = new GridPos(Integer.MIN_VALUE, 0, 0);
        var max = new GridPos(Integer.MAX_VALUE, 0, 0);
        assertDoesNotThrow(() -> new Lane(0, Direction.EAST, min, max, corridor, EMPTY_FLOOR));
        assertDoesNotThrow(() -> new Lane(0, Direction.WEST, max, min, corridor, EMPTY_FLOOR));
        assertThrows(IllegalArgumentException.class, () -> new Lane(0, Direction.EAST, max, min, corridor, EMPTY_FLOOR));
    }

    private static BuildPlan plan(int width, int depth) {
        return new BuildPlan("geometry", new GridSize(width, 1, depth), List.of(target(7, 0, 0, 0)));
    }

    private static Target target(int id, int x, int y, int z) {
        return new Target(id, new GridPos(x, y, z), STONE);
    }

    private static Lane directLane(Direction direction) {
        GridPos start = switch (direction) {
            case EAST, SOUTH -> new GridPos(0, 0, 0);
            case WEST -> new GridPos(2, 0, 0);
            case NORTH -> new GridPos(0, 0, 2);
        };
        GridPos end = switch (direction) {
            case EAST -> new GridPos(2, 0, 0);
            case SOUTH -> new GridPos(0, 0, 2);
            case WEST, NORTH -> new GridPos(0, 0, 0);
        };
        return new Lane(37, direction, start, end, new Corridor(0, 2, 0, 2), EMPTY_FLOOR);
    }
}
