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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GroundedSweepResumeScannerTest {
    private final GroundedSweepResumeScanner scanner = new GroundedSweepResumeScanner();
    private final GroundedSweepLanePlanner lanePlanner = new GroundedSweepLanePlanner();

    @Test
    void freshBuildReturnsDeterministicLaneZeroStart() {
        ScanFixture fixture = fixture();

        GroundedSweepResumeSelection selection = scan(fixture, Set.of(), new Vec3d(0.5, 65.0, 0.5));

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(0, point.laneIndex());
        assertEquals(GroundedSweepResumePoint.Reason.FRESH_START, point.reason());
    }

    @Test
    void completedLaneZeroSelectsNextUsefulLane() {
        ScanFixture fixture = fixture();
        Set<BlockPos> completed = new HashSet<>(laneWorldPositions(fixture, fixture.lanes.get(0)));
        GroundedSweepLane lane1 = fixture.lanes.get(1);

        GroundedSweepResumeSelection selection = scan(
                fixture,
                completed,
                new Vec3d(lane1.startPoint().getX() + 0.5, 65.0, lane1.centerlineCoordinate() + 0.5)
        );

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(1, point.laneIndex());
    }

    @Test
    void partialLaneUsesPartialLaneResumePoint() {
        ScanFixture fixture = fixture();
        GroundedSweepLane lane0 = fixture.lanes.get(0);
        Set<BlockPos> completed = new HashSet<>(fixture.allWorldPositions);
        BlockPos unfinished = new BlockPos(lane0.startPoint().getX() + 3, 64, lane0.centerlineCoordinate());
        completed.remove(unfinished);

        GroundedSweepResumeSelection selection = scan(fixture, completed, new Vec3d(unfinished.getX() + 0.5, 65.0, unfinished.getZ() + 0.5));

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(0, point.laneIndex());
        assertEquals(GroundedSweepResumePoint.Reason.PARTIAL_LANE, point.reason());
        assertEquals(unfinished.getX(), point.progressCoordinate());
    }

    @Test
    void worldStateOverridesSavedProgressAssumptions() {
        ScanFixture fixture = fixture();
        fixture.session.setCurrentPlacementIndex(999);

        GroundedSweepResumeSelection selection = scan(fixture, Set.of(), new Vec3d(0.5, 65.0, 0.5));

        assertEquals(GroundedSweepResumePoint.Reason.FRESH_START, selection.resumePoint().orElseThrow().reason());
    }

    @Test
    void correctWorldBlocksAreNotCountedUnfinished() {
        ScanFixture fixture = fixture();
        Set<BlockPos> completed = new HashSet<>(fixture.allWorldPositions);
        BlockPos onlyUnfinished = laneWorldPositionsBySweepOrder(fixture, fixture.lanes.getLast()).getFirst();
        completed.remove(onlyUnfinished);

        GroundedSweepResumeSelection selection = scan(fixture, completed, new Vec3d(10.5, 65.0, 8.5));

        assertEquals(1, selection.unfinishedPlacementCount());
        assertTrue(selection.resumePoint().isPresent());
    }

    @Test
    void noUnfinishedPlacementsReturnsCompleteNoOp() {
        ScanFixture fixture = fixture();

        GroundedSweepResumeSelection selection = scan(fixture, new HashSet<>(fixture.allWorldPositions), new Vec3d(0.5, 65.0, 0.5));

        assertTrue(selection.buildComplete());
        assertTrue(selection.resumePoint().isEmpty());
    }

    @Test
    void closestUsefulLaneWinsForPartialBuild() {
        ScanFixture fixture = fixture();
        Set<BlockPos> completed = new HashSet<>(laneWorldPositions(fixture, fixture.lanes.get(0)));
        GroundedSweepLane lastLane = fixture.lanes.getLast();
        Vec3d nearLastLane = new Vec3d(lastLane.startPoint().getX() + 0.5, 65.0, lastLane.centerlineCoordinate() + 0.5);

        GroundedSweepResumeSelection selection = scan(fixture, completed, nearLastLane);

        assertEquals(lastLane.laneIndex(), selection.resumePoint().orElseThrow().laneIndex());
    }

    @Test
    void earlierSweepOrderWinsWhenDistanceSimilar() {
        ScanFixture fixture = fixture();
        Set<BlockPos> completed = new HashSet<>(laneWorldPositions(fixture, fixture.lanes.get(0)));

        GroundedSweepResumeSelection selection = scan(fixture, completed, new Vec3d(15.5, 65.0, 9.5));

        assertEquals(1, selection.resumePoint().orElseThrow().laneIndex());
    }

    @Test
    void scannerUsesLaneDirectionYaw() {
        ScanFixture fixture = fixture();
        Set<BlockPos> completed = new HashSet<>(laneWorldPositions(fixture, fixture.lanes.get(0)));
        GroundedSweepLane lane1 = fixture.lanes.get(1);

        GroundedSweepResumeSelection selection = scan(
                fixture,
                completed,
                new Vec3d(lane1.startPoint().getX() + 0.5, 65.0, lane1.centerlineCoordinate() + 0.5)
        );

        GroundedSweepResumePoint point = selection.resumePoint().orElseThrow();
        assertEquals(lane1.direction().yawDegrees(), point.yawDegrees());
    }

    private GroundedSweepResumeSelection scan(ScanFixture fixture, Set<BlockPos> completedPositions, Vec3d playerPosition) {
        WorldBlockLookup lookup = (worldPos, expectedBlock) -> completedPositions.contains(worldPos);
        return scanner.scan(fixture.session, fixture.bounds, fixture.lanes, playerPosition, fixture.settings.sweepHalfWidth(), lookup);
    }

    private ScanFixture fixture() {
        List<Placement> placements = new ArrayList<>();
        List<BlockPos> worldPositions = new ArrayList<>();
        for (int z = 0; z <= 30; z++) {
            for (int x = 0; x <= 30; x++) {
                placements.add(new Placement(new BlockPos(x, 0, z), null));
                worldPositions.add(new BlockPos(x, 64, z));
            }
        }

        BuildPlan plan = new BuildPlan("test", Path.of("test"), new Vec3i(31, 1, 31), placements, Map.of(), List.of());
        BuildSession session = new BuildSession(plan);
        session.setOrigin(new BlockPos(0, 64, 0));

        GroundedSweepSettings settings = GroundedSweepSettings.defaults();
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, session.getOrigin());
        List<GroundedSweepLane> lanes = lanePlanner.planLanes(bounds, settings);
        return new ScanFixture(session, bounds, lanes, settings, worldPositions);
    }

    private List<BlockPos> laneWorldPositions(ScanFixture fixture, GroundedSweepLane lane) {
        return fixture.allWorldPositions.stream()
                .filter(pos -> pos.getX() >= lane.corridorBounds().minX() && pos.getX() <= lane.corridorBounds().maxX())
                .filter(pos -> pos.getZ() >= lane.corridorBounds().minZ() && pos.getZ() <= lane.corridorBounds().maxZ())
                .filter(pos -> {
                    int lateral = lane.direction().alongX() ? pos.getZ() : pos.getX();
                    return Math.abs(lateral - lane.centerlineCoordinate()) <= fixture.settings.sweepHalfWidth();
                })
                .toList();
    }

    private List<BlockPos> laneWorldPositionsBySweepOrder(ScanFixture fixture, GroundedSweepLane lane) {
        return laneWorldPositions(fixture, lane).stream()
                .sorted((a, b) -> Integer.compare(progress(a, lane.direction()) * lane.direction().forwardSign(), progress(b, lane.direction()) * lane.direction().forwardSign()))
                .toList();
    }

    private int progress(BlockPos pos, GroundedLaneDirection direction) {
        return direction.alongX() ? pos.getX() : pos.getZ();
    }

    private record ScanFixture(
            BuildSession session,
            GroundedSchematicBounds bounds,
            List<GroundedSweepLane> lanes,
            GroundedSweepSettings settings,
            List<BlockPos> allWorldPositions
    ) {
    }
}
