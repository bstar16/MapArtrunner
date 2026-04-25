package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedSweepPlacementExecutorTest {

    private final GroundedSweepSettings settings = GroundedSweepSettings.defaults();
    private final GroundedSweepLane laneEast = new GroundedSweepLane(
            0,
            10,
            GroundedLaneDirection.EAST,
            new BlockPos(0, 64, 10),
            new BlockPos(20, 64, 10),
            new GroundedLaneCorridorBounds(0, 20, 8, 12),
            1.0
    );

    @Test
    void classifiesLeftRightRelativeToLaneDirection() {
        assertEquals(-2, GroundedSweepPlacementExecutor.laneRelativeOffset(GroundedLaneDirection.EAST, 10, new BlockPos(5, 64, 8)));
        assertEquals(-2, GroundedSweepPlacementExecutor.laneRelativeOffset(GroundedLaneDirection.WEST, 10, new BlockPos(5, 64, 12)));
        assertEquals(-2, GroundedSweepPlacementExecutor.laneRelativeOffset(GroundedLaneDirection.SOUTH, 10, new BlockPos(12, 64, 5)));
        assertEquals(-2, GroundedSweepPlacementExecutor.laneRelativeOffset(GroundedLaneDirection.NORTH, 10, new BlockPos(8, 64, 5)));
    }

    @Test
    void prioritizesCurrentCrossSectionOverFarAhead() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings, 3, 3);

        GroundedSweepPlacementExecutor.GroundedSweepPlacementSelection selection = executor.selectPlacements(
                List.of(
                        new GroundedSweepPlacementExecutor.GroundedPlacementTarget(1, new BlockPos(10, 64, 10)),
                        new GroundedSweepPlacementExecutor.GroundedPlacementTarget(2, new BlockPos(14, 64, 10))
                ),
                laneEast,
                10,
                100
        );

        assertEquals(1, selection.rankedCandidates().size());
        assertEquals(1, selection.rankedCandidates().getFirst().placementIndex());
        assertEquals(1, selection.deferredCandidates().size());
        assertEquals(2, selection.deferredCandidates().getFirst().placementIndex());
    }

    @Test
    void includesSmallForwardLookahead() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings, 3, 3);

        GroundedSweepPlacementExecutor.GroundedSweepPlacementSelection selection = executor.selectPlacements(
                List.of(
                        new GroundedSweepPlacementExecutor.GroundedPlacementTarget(1, new BlockPos(10, 64, 10)),
                        new GroundedSweepPlacementExecutor.GroundedPlacementTarget(2, new BlockPos(11, 64, 10))
                ),
                laneEast,
                10,
                101
        );

        assertEquals(2, selection.rankedCandidates().size());
        assertEquals(1, selection.rankedCandidates().get(0).placementIndex());
        assertEquals(2, selection.rankedCandidates().get(1).placementIndex());
        assertEquals(GroundedSweepPlacementExecutor.GroundedProgressBucket.FORWARD_LOOKAHEAD,
                selection.rankedCandidates().get(1).progressBucket());
    }

    @Test
    void allowsTrivialBehindCleanupWithoutBlockingFlow() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings, 3, 3);

        GroundedSweepPlacementExecutor.GroundedSweepPlacementSelection selection = executor.selectPlacements(
                List.of(
                        new GroundedSweepPlacementExecutor.GroundedPlacementTarget(1, new BlockPos(10, 64, 10)),
                        new GroundedSweepPlacementExecutor.GroundedPlacementTarget(2, new BlockPos(9, 64, 10))
                ),
                laneEast,
                10,
                102
        );

        assertEquals(2, selection.rankedCandidates().size());
        assertEquals(GroundedSweepPlacementExecutor.GroundedProgressBucket.TRIVIAL_BEHIND_CLEANUP,
                selection.rankedCandidates().get(1).progressBucket());
    }

    @Test
    void appliesGracePeriodToDelayedFailures() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings, 3, 2);

        executor.recordPlacementResult(7, GroundedSweepPlacementExecutor.PlacementResultType.RETRY_DELAYED, 100);
        assertTrue(executor.leftovers().stream().anyMatch(record -> record.placementIndex() == 7
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.LAG_GRACE_RETRY_DELAYED)));

        executor.selectPlacements(List.of(), laneEast, 10, 102);
        assertFalse(executor.leftovers().stream().anyMatch(record -> record.placementIndex() == 7
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED)));

        executor.selectPlacements(List.of(), laneEast, 10, 103);
        assertTrue(executor.leftovers().stream().anyMatch(record -> record.placementIndex() == 7
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED)));
    }

    @Test
    void recordsExplicitLeftoverStructure() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings, 3, 2);

        executor.recordPlacementResult(1, GroundedSweepPlacementExecutor.PlacementResultType.DEFERRED, 100);
        executor.recordPlacementResult(2, GroundedSweepPlacementExecutor.PlacementResultType.MISSED, 100);
        executor.recordPlacementResult(3, GroundedSweepPlacementExecutor.PlacementResultType.FAILED, 100);

        List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> leftovers = executor.leftovers();
        assertEquals(3, leftovers.size());
        assertTrue(leftovers.stream().anyMatch(record -> record.placementIndex() == 1
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.DEFERRED)));
        assertTrue(leftovers.stream().anyMatch(record -> record.placementIndex() == 2
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.MISSED)));
        assertTrue(leftovers.stream().anyMatch(record -> record.placementIndex() == 3
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED)));
    }
}
