package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedSweepPlacementExecutorTest {

    @Test
    void classifiesLeftRightRelativeToLaneDirection() {
        GroundedSweepLane eastLane = lane(0, 64, GroundedLaneDirection.EAST);
        GroundedSweepLane westLane = lane(0, 64, GroundedLaneDirection.WEST);

        assertEquals(GroundedSweepPlacementExecutor.LaneSide.LEFT,
                GroundedSweepPlacementExecutor.classifyLaneSide(eastLane, new BlockPos(10, 64, -1)));
        assertEquals(GroundedSweepPlacementExecutor.LaneSide.RIGHT,
                GroundedSweepPlacementExecutor.classifyLaneSide(eastLane, new BlockPos(10, 64, 1)));

        assertEquals(GroundedSweepPlacementExecutor.LaneSide.LEFT,
                GroundedSweepPlacementExecutor.classifyLaneSide(westLane, new BlockPos(10, 64, 1)));
        assertEquals(GroundedSweepPlacementExecutor.LaneSide.RIGHT,
                GroundedSweepPlacementExecutor.classifyLaneSide(westLane, new BlockPos(10, 64, -1)));
    }

    @Test
    void prioritizesCurrentCrossSectionOverFarAhead() {
        GroundedSweepPlacementExecutor executor = executorFor(
                GroundedLaneDirection.EAST,
                List.of(
                        new BlockPos(2, 0, 0),
                        new BlockPos(3, 0, 0)
                )
        );

        List<GroundedSweepPlacementExecutor.PlacementSlot> selected = executor.selectPlacements(2, 0);

        assertEquals(2, selected.size());
        assertEquals(GroundedSweepPlacementExecutor.ProgressBucket.CURRENT_CROSS_SECTION, selected.get(0).selectionBucket());
        assertEquals(GroundedSweepPlacementExecutor.ProgressBucket.FORWARD_LOOKAHEAD, selected.get(1).selectionBucket());
    }

    @Test
    void appliesSmallForwardLookaheadWindow() {
        GroundedSweepPlacementExecutor executor = executorFor(
                GroundedLaneDirection.EAST,
                List.of(
                        new BlockPos(2, 0, 0),
                        new BlockPos(3, 0, 0),
                        new BlockPos(5, 0, 0)
                )
        );

        List<GroundedSweepPlacementExecutor.PlacementSlot> selected = executor.selectPlacements(2, 0);

        assertEquals(2, selected.size());
        assertTrue(selected.stream().noneMatch(slot -> slot.progressCoordinate() == 5));
    }

    @Test
    void allowsTrivialBehindCleanupWithoutStalling() {
        GroundedSweepPlacementExecutor executor = executorFor(
                GroundedLaneDirection.EAST,
                List.of(
                        new BlockPos(2, 0, 0),
                        new BlockPos(1, 0, 0)
                )
        );

        List<GroundedSweepPlacementExecutor.PlacementSlot> selected = executor.selectPlacements(2, 0);

        assertEquals(2, selected.size());
        assertEquals(GroundedSweepPlacementExecutor.ProgressBucket.CURRENT_CROSS_SECTION, selected.get(0).selectionBucket());
        assertEquals(GroundedSweepPlacementExecutor.ProgressBucket.TRIVIAL_BEHIND_CLEANUP, selected.get(1).selectionBucket());
    }

    @Test
    void appliesGracePeriodForTransientPlacementFailures() {
        GroundedSweepPlacementExecutor executor = executorFor(
                GroundedLaneDirection.EAST,
                List.of(new BlockPos(2, 0, 0))
        );

        int placementIndex = executor.selectPlacements(2, 0).getFirst().placementIndex();
        executor.markPlacementFailed(placementIndex, 5, GroundedSweepPlacementExecutor.FailureKind.TRANSIENT);

        List<GroundedSweepPlacementExecutor.LeftoverRecord> duringGrace = executor.leftovers(8);
        assertEquals(1, duringGrace.size());
        assertEquals(GroundedSweepPlacementExecutor.PlacementStatus.LAG_GRACE_DELAYED, duringGrace.getFirst().status());

        List<GroundedSweepPlacementExecutor.LeftoverRecord> afterGrace = executor.leftovers(20);
        assertEquals(1, afterGrace.size());
        assertEquals(GroundedSweepPlacementExecutor.PlacementStatus.MISSED, afterGrace.getFirst().status());
    }

    @Test
    void recordsStructuredLeftoversForDeferredAndFailedPlacements() {
        GroundedSweepPlacementExecutor executor = executorFor(
                GroundedLaneDirection.EAST,
                List.of(
                        new BlockPos(0, 0, 0),
                        new BlockPos(2, 0, 0)
                )
        );

        List<GroundedSweepPlacementExecutor.PlacementSlot> selected = executor.selectPlacements(2, 0);
        int selectedIndex = selected.stream()
                .filter(slot -> slot.progressCoordinate() == 2)
                .findFirst()
                .orElseThrow()
                .placementIndex();

        executor.markPlacementFailed(selectedIndex, 1, GroundedSweepPlacementExecutor.FailureKind.HARD);

        List<GroundedSweepPlacementExecutor.LeftoverRecord> leftovers = executor.leftovers(1);
        assertEquals(2, leftovers.size());
        assertTrue(leftovers.stream().anyMatch(record -> record.status() == GroundedSweepPlacementExecutor.PlacementStatus.DEFERRED));
        assertTrue(leftovers.stream().anyMatch(record -> record.status() == GroundedSweepPlacementExecutor.PlacementStatus.FAILED));
    }

    private static GroundedSweepPlacementExecutor executorFor(GroundedLaneDirection direction, List<BlockPos> relativePositions) {
        BlockPos origin = new BlockPos(0, 64, 0);
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                origin,
                new BlockPos(0, 64, -2),
                new BlockPos(16, 64, 2)
        );
        GroundedSweepLane lane = lane(0, 64, direction);
        BuildPlan plan = plan(relativePositions);

        return new GroundedSweepPlacementExecutor(
                plan,
                origin,
                bounds,
                lane,
                new GroundedSweepPlacementExecutorSettings(1, 1, 8)
        );
    }

    private static GroundedSweepLane lane(int centerline, int y, GroundedLaneDirection direction) {
        return new GroundedSweepLane(
                0,
                centerline,
                direction,
                new BlockPos(0, y, centerline),
                new BlockPos(16, y, centerline),
                new GroundedLaneCorridorBounds(0, 16, -2, 2),
                1.0
        );
    }

    private static BuildPlan plan(List<BlockPos> relativePositions) {
        List<Placement> placements = relativePositions.stream()
                .map(pos -> new Placement(pos, block()))
                .toList();
        return new BuildPlan("test", Path.of("test"), new Vec3i(17, 1, 5), placements, Map.of(), List.of());
    }

    private static Block block() {
        try {
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            return (Block) unsafe.allocateInstance(Block.class);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to allocate block", exception);
        }
    }
}
