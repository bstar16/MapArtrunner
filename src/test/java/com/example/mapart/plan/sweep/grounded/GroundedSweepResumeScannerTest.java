package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedSweepResumeScannerTest {

    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();
    private final GroundedSweepResumeScanner scanner = new GroundedSweepResumeScanner();

    @Test
    void freshBuildReturnsDeterministicLaneZeroStart() {
        Fixture fixture = fixture(new Vec3i(11, 1, 11), new BlockPos(0, 64, 0));

        GroundedSweepResumeSelection selection = fixture.scan(java.util.Set.of(), new Vec3d(0.5, 65, 0.5));

        assertFalse(selection.buildComplete());
        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(0, point.laneIndex());
        assertEquals(GroundedSweepResumePoint.ResumeReason.FRESH_START, point.reason());
    }

    @Test
    void completedFirstLaneSelectsNextUsefulLane() {
        Fixture fixture = fixture(new Vec3i(11, 1, 11), new BlockPos(0, 64, 0));
        java.util.Set<BlockPos> world = new java.util.HashSet<>();
        markLaneComplete(fixture, fixture.lanes.get(0), world);

        GroundedSweepResumeSelection selection = fixture.scan(world, new Vec3d(7.5, 65, 7.5));

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(1, point.laneIndex());
        assertTrue(point.reason() == GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE
                || point.reason() == GroundedSweepResumePoint.ResumeReason.CLOSEST_USEFUL);
    }

    @Test
    void partialLaneProducesPartialResumePoint() {
        Fixture fixture = fixture(new Vec3i(11, 1, 11), new BlockPos(0, 64, 0));
        GroundedSweepLane lane0 = fixture.lanes.get(0);
        java.util.Set<BlockPos> world = new java.util.HashSet<>();

        int startX = lane0.startPoint().getX();
        int z = lane0.centerlineCoordinate();
        for (int dz = -fixture.settings.sweepHalfWidth(); dz <= fixture.settings.sweepHalfWidth(); dz++) {
            world.add(new BlockPos(startX, 64, z + dz));
            world.add(new BlockPos(startX + 1, 64, z + dz));
        }

        GroundedSweepResumeSelection selection = fixture.scan(world, new Vec3d(2.5, 65, z + 0.5));

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(0, point.laneIndex());
        assertEquals(GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE, point.reason());
        assertEquals(startX + 2, point.progressCoordinate());
    }

    @Test
    void worldStateOverridesSavedAssumptionsAndCorrectBlocksAreComplete() {
        Fixture fixture = fixture(new Vec3i(6, 1, 6), new BlockPos(0, 64, 0));
        java.util.Set<BlockPos> world = new java.util.HashSet<>();

        Placement placement = fixture.plan.placements().getFirst();
        BlockPos worldPos = fixture.origin.add(placement.relativePos());
        world.add(worldPos);

        GroundedSweepResumeSelection selection = fixture.scan(world, Vec3d.ofBottomCenter(new BlockPos(0, 65, 0)));

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertTrue(point.unfinishedPlacementCount() < fixture.plan.placements().size());
    }

    @Test
    void noUnfinishedPlacementsReturnsCompleteSelection() {
        Fixture fixture = fixture(new Vec3i(6, 1, 6), new BlockPos(0, 64, 0));
        java.util.Set<BlockPos> world = new java.util.HashSet<>();
        for (Placement placement : fixture.plan.placements()) {
            world.add(fixture.origin.add(placement.relativePos()));
        }

        GroundedSweepResumeSelection selection = fixture.scan(world, new Vec3d(0.5, 65, 0.5));

        assertTrue(selection.buildComplete());
        assertTrue(selection.resumePoint().isEmpty());
    }

    @Test
    void closestUsefulPointCanWinWhenMuchCloser() {
        Fixture fixture = fixture(new Vec3i(21, 1, 11), new BlockPos(0, 64, 0));
        java.util.Set<BlockPos> world = new java.util.HashSet<>();
        markLaneComplete(fixture, fixture.lanes.get(0), world);
        markLaneComplete(fixture, fixture.lanes.get(1), world);

        GroundedSweepLane laterLane = fixture.lanes.get(2);
        Vec3d nearLaterLane = Vec3d.ofBottomCenter(new BlockPos(laterLane.startPoint().getX(), 65, laterLane.centerlineCoordinate()));

        GroundedSweepResumeSelection selection = fixture.scan(world, nearLaterLane);

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(2, point.laneIndex());
        assertEquals(GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE, point.reason());
    }

    @Test
    void earlierSweepOrderWinsWhenDistanceIsSimilar() {
        Fixture fixture = fixture(new Vec3i(21, 1, 11), new BlockPos(0, 64, 0));
        java.util.Set<BlockPos> world = new java.util.HashSet<>();
        markLaneComplete(fixture, fixture.lanes.get(0), world);

        Vec3d midpoint = new Vec3d(10.5, 65, 5.5);
        GroundedSweepResumeSelection selection = fixture.scan(world, midpoint);

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(1, point.laneIndex());
    }

    @Test
    void scannerRespectsLaneYawAndDirection() {
        Fixture fixture = fixture(new Vec3i(6, 1, 16), new BlockPos(0, 64, 0));

        GroundedSweepResumeSelection selection = fixture.scan(java.util.Set.of(), new Vec3d(0.5, 65, 0.5));
        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();

        assertEquals(point.laneDirection().yawDegrees(), point.yaw());
        assertEquals(GroundedLaneDirection.EAST, point.laneDirection());
    }

    private static void markLaneComplete(Fixture fixture, GroundedSweepLane lane, java.util.Set<BlockPos> world) {
        for (Placement placement : fixture.plan.placements()) {
            BlockPos worldPos = fixture.origin.add(placement.relativePos());
            if (worldPos.getX() < lane.corridorBounds().minX() || worldPos.getX() > lane.corridorBounds().maxX()) {
                continue;
            }
            if (worldPos.getZ() < lane.corridorBounds().minZ() || worldPos.getZ() > lane.corridorBounds().maxZ()) {
                continue;
            }
            int lateral = lane.direction().alongX() ? worldPos.getZ() : worldPos.getX();
            if (Math.abs(lateral - lane.centerlineCoordinate()) <= fixture.settings.sweepHalfWidth()) {
                world.add(worldPos);
            }
        }
    }

    private Fixture fixture(Vec3i dimensions, BlockPos origin) {
        BuildPlan plan = rectangularPlan(dimensions);
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, origin);
        List<GroundedSweepLane> lanes = lanePlanner.planLanes(bounds, settings);
        return new Fixture(plan, origin, settings, bounds, lanes);
    }

    private static BuildPlan rectangularPlan(Vec3i dimensions) {
        List<Placement> placements = new java.util.ArrayList<>();
        for (int y = 0; y < dimensions.getY(); y++) {
            for (int z = 0; z < dimensions.getZ(); z++) {
                for (int x = 0; x < dimensions.getX(); x++) {
                    placements.add(new Placement(new BlockPos(x, y, z), null));
                }
            }
        }
        return new BuildPlan("test", Path.of("fixture.schem"), dimensions, placements, Map.of(), List.of());
    }

    private record Fixture(
            BuildPlan plan,
            BlockPos origin,
            GroundedSweepSettings settings,
            GroundedSchematicBounds bounds,
            List<GroundedSweepLane> lanes
    ) {
        private GroundedSweepResumeSelection scan(java.util.Set<BlockPos> completedPositions, Vec3d playerPosition) {
            PlacementCompletionLookup lookup = (worldPos, expectedBlock) -> completedPositions.contains(worldPos);
            return new GroundedSweepResumeScanner().scan(plan, origin, bounds, lanes, settings.sweepHalfWidth(), playerPosition, lookup);
        }
    }
}
