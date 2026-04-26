package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.state.BuildSession;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedSweepResumeScannerTest {

    private final GroundedSweepResumeScanner scanner = new GroundedSweepResumeScanner();
    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();

    @Test
    void freshBuildReturnsLaneZeroFreshStart() {
        Fixture fixture = fixture(new Vec3i(10, 1, 11));

        GroundedSweepResumeScanner.ResumeSelection selection = scan(fixture, Vec3d.ofCenter(fixture.origin));

        assertFalse(selection.complete());
        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(0, point.laneIndex());
        assertEquals(GroundedSweepResumePoint.Reason.FRESH_START, point.reason());
    }

    @Test
    void completedLaneZeroResumesToUsefulLaneOnePoint() {
        Fixture fixture = fixture(new Vec3i(10, 1, 11));
        GroundedSweepLane lane0 = fixture.lanes.get(0);
        markLaneComplete(fixture, lane0);

        GroundedSweepResumeScanner.ResumeSelection selection = scan(fixture, Vec3d.ofCenter(fixture.origin));

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(1, point.laneIndex());
        assertEquals(GroundedLaneDirection.WEST, point.laneDirection());
    }

    @Test
    void partialLaneReturnsPartialLaneResumeCoordinate() {
        Fixture fixture = fixture(new Vec3i(11, 1, 5));
        GroundedSweepLane lane0 = fixture.lanes.getFirst();
        int splitProgress = lane0.startPoint().getX() + 5;
        for (Placement placement : fixture.plan.placements()) {
            BlockPos worldPos = fixture.origin.add(placement.relativePos());
            if (worldPos.getX() < splitProgress
                    && worldPos.getZ() >= lane0.corridorBounds().minZ()
                    && worldPos.getZ() <= lane0.corridorBounds().maxZ()) {
                fixture.world.put(worldPos, true);
            }
        }

        GroundedSweepResumeScanner.ResumeSelection selection = scan(fixture, new Vec3d(splitProgress, 65, lane0.centerlineCoordinate()));

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(0, point.laneIndex());
        assertEquals(GroundedSweepResumePoint.Reason.CLOSEST_USEFUL, point.reason());
        assertTrue(point.progressCoordinate() >= splitProgress);
        assertEquals(lane0.direction().yawDegrees(), point.yawDegrees());
    }

    @Test
    void worldStateOverridesSavedProgressAssumptions() {
        Fixture fixture = fixture(new Vec3i(10, 1, 11));
        fixture.session.setCurrentPlacementIndex(999);

        GroundedSweepResumeScanner.ResumeSelection selection = scan(fixture, Vec3d.ofCenter(fixture.origin));

        assertEquals(GroundedSweepResumePoint.Reason.FRESH_START, selection.resumePoint().orElseThrow().reason());
    }

    @Test
    void alreadyCorrectWorldBlocksAreComplete() {
        Fixture fixture = fixture(new Vec3i(6, 1, 6));
        fixture.plan.placements().forEach(placement -> fixture.world.put(fixture.origin.add(placement.relativePos()), true));

        GroundedSweepResumeScanner.ResumeSelection selection = scan(fixture, Vec3d.ofCenter(fixture.origin));

        assertTrue(selection.complete());
        assertTrue(selection.resumePoint().isEmpty());
    }

    @Test
    void noUnfinishedPlacementsReturnsCompleteNoOp() {
        Fixture fixture = fixture(new Vec3i(4, 1, 4));
        fixture.plan.placements().forEach(placement -> fixture.world.put(fixture.origin.add(placement.relativePos()), true));

        GroundedSweepResumeScanner.ResumeSelection selection = scan(fixture, Vec3d.ofCenter(fixture.origin));

        assertTrue(selection.complete());
        assertEquals(0, selection.unfinishedPlacements());
    }

    @Test
    void closestUsefulLaneWinsWhenPlayerNearLaterLane() {
        Fixture fixture = fixture(new Vec3i(10, 1, 11));
        GroundedSweepLane lane0 = fixture.lanes.get(0);
        for (Placement placement : fixture.plan.placements()) {
            BlockPos worldPos = fixture.origin.add(placement.relativePos());
            if (worldPos.getZ() <= lane0.corridorBounds().maxZ()) {
                fixture.world.put(worldPos, true);
            }
        }

        GroundedSweepLane lane1 = fixture.lanes.get(1);
        Vec3d nearLane1 = Vec3d.ofCenter(lane1.startPoint()).add(0, 1, 0);
        GroundedSweepResumeScanner.ResumeSelection selection = scan(fixture, nearLane1);

        assertEquals(1, selection.resumePoint().orElseThrow().laneIndex());
    }

    @Test
    void earlierSweepOrderWinsWhenDistanceIsSimilar() {
        Fixture fixture = fixture(new Vec3i(10, 1, 11));
        GroundedSweepLane lane0 = fixture.lanes.get(0);
        fixture.world.put(fixture.origin, true);
        Vec3d between = Vec3d.ofCenter(new BlockPos(fixture.origin.getX() + 5, fixture.origin.getY() + 1, fixture.origin.getZ() + 5));

        GroundedSweepResumeScanner.ResumeSelection selection = scan(fixture, between);

        assertEquals(0, selection.resumePoint().orElseThrow().laneIndex());
        assertEquals(lane0.direction().yawDegrees(), selection.resumePoint().orElseThrow().yawDegrees());
    }

    @Test
    void scannerRespectsLaneDirectionYawForSelectedPoint() {
        Fixture fixture = fixture(new Vec3i(10, 1, 11));
        GroundedSweepLane lane0 = fixture.lanes.get(0);
        markLaneComplete(fixture, lane0);

        GroundedSweepResumePoint point = scan(fixture, Vec3d.ofCenter(fixture.origin)).resumePoint().orElseThrow();

        assertEquals(GroundedLaneDirection.WEST, point.laneDirection());
        assertEquals(90.0f, point.yawDegrees());
    }

    private GroundedSweepResumeScanner.ResumeSelection scan(Fixture fixture, Vec3d playerPosition) {
        return scanner.selectResumePoint(
                fixture.session,
                fixture.bounds,
                fixture.lanes,
                playerPosition,
                (placement, pos) -> fixture.world.getOrDefault(pos, false)
        );
    }

    private void markLaneComplete(Fixture fixture, GroundedSweepLane lane) {
        for (Placement placement : fixture.plan.placements()) {
            BlockPos worldPos = fixture.origin.add(placement.relativePos());
            if (worldPos.getX() >= lane.corridorBounds().minX() && worldPos.getX() <= lane.corridorBounds().maxX()
                    && worldPos.getZ() >= lane.corridorBounds().minZ() && worldPos.getZ() <= lane.corridorBounds().maxZ()) {
                fixture.world.put(worldPos, true);
            }
        }
    }

    private Fixture fixture(Vec3i dimensions) {
        BlockPos origin = new BlockPos(100, 64, 200);
        BuildPlan plan = plan(dimensions);
        BuildSession session = new BuildSession(plan);
        session.setOrigin(origin);
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, origin);
        List<GroundedSweepLane> lanes = lanePlanner.planLanes(bounds, GroundedSweepSettings.defaults());
        return new Fixture(plan, session, origin, bounds, lanes, new HashMap<>());
    }

    private BuildPlan plan(Vec3i dimensions) {
        List<Placement> placements = new ArrayList<>();
        for (int z = 0; z < dimensions.getZ(); z++) {
            for (int x = 0; x < dimensions.getX(); x++) {
                placements.add(new Placement(new BlockPos(x, 0, z), null));
            }
        }
        return new BuildPlan("test", Path.of("test.schem"), dimensions, placements, Map.of(), List.of());
    }

    private record Fixture(
            BuildPlan plan,
            BuildSession session,
            BlockPos origin,
            GroundedSchematicBounds bounds,
            List<GroundedSweepLane> lanes,
            Map<BlockPos, Boolean> world
    ) {}
}
