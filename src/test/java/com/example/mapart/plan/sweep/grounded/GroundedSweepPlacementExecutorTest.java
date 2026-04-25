package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedSweepPlacementExecutorTest {

    @Test
    void classifiesLeftRightRelativeToLaneDirection() {
        GroundedSweepLane eastLane = lane(GroundedLaneDirection.EAST, 15);
        GroundedSweepLane westLane = lane(GroundedLaneDirection.WEST, 15);
        GroundedSweepLane southLane = lane(GroundedLaneDirection.SOUTH, 15);
        GroundedSweepLane northLane = lane(GroundedLaneDirection.NORTH, 15);

        assertEquals(GroundedSweepPlacementExecutor.LaneRelativeBand.LEFT_ONE,
                GroundedSweepPlacementExecutor.laneRelativeBand(eastLane, new BlockPos(14, 64, 14)));
        assertEquals(GroundedSweepPlacementExecutor.LaneRelativeBand.LEFT_ONE,
                GroundedSweepPlacementExecutor.laneRelativeBand(westLane, new BlockPos(14, 64, 16)));
        assertEquals(GroundedSweepPlacementExecutor.LaneRelativeBand.LEFT_ONE,
                GroundedSweepPlacementExecutor.laneRelativeBand(southLane, new BlockPos(16, 64, 14)));
        assertEquals(GroundedSweepPlacementExecutor.LaneRelativeBand.LEFT_ONE,
                GroundedSweepPlacementExecutor.laneRelativeBand(northLane, new BlockPos(14, 64, 14)));
    }

    @Test
    void prioritizesCurrentCrossSectionOverFarAheadCandidates() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings());

        GroundedSweepPlacementExecutor.SweepSelection selection = executor.select(
                lane(GroundedLaneDirection.EAST, 15),
                bounds(),
                14,
                10,
                List.of(
                        target(1, 14, 64, 15),
                        target(2, 17, 64, 15)
                )
        );

        assertEquals(1, selection.rankedCandidates().size());
        assertEquals(1, selection.rankedCandidates().getFirst().placementIndex());
        assertEquals(1, selection.deferredCandidates().size());
        assertEquals(2, selection.deferredCandidates().getFirst().placementIndex());
    }

    @Test
    void supportsSmallForwardLookahead() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings());

        GroundedSweepPlacementExecutor.SweepSelection selection = executor.select(
                lane(GroundedLaneDirection.EAST, 15),
                bounds(),
                14,
                10,
                List.of(
                        target(1, 14, 64, 15),
                        target(2, 15, 64, 15)
                )
        );

        assertEquals(List.of(1, 2), selection.rankedCandidates().stream()
                .map(GroundedSweepPlacementExecutor.SweepCandidate::placementIndex)
                .toList());
        assertEquals(GroundedSweepPlacementExecutor.ProgressBucket.CURRENT_CROSS_SECTION, selection.rankedCandidates().get(0).bucket());
        assertEquals(GroundedSweepPlacementExecutor.ProgressBucket.SMALL_FORWARD_LOOKAHEAD, selection.rankedCandidates().get(1).bucket());
    }

    @Test
    void supportsTrivialBehindCleanupWithoutStoppingFlow() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings());

        GroundedSweepPlacementExecutor.SweepSelection selection = executor.select(
                lane(GroundedLaneDirection.EAST, 15),
                bounds(),
                14,
                10,
                List.of(
                        target(1, 14, 64, 15),
                        target(2, 13, 64, 15)
                )
        );

        assertEquals(2, selection.rankedCandidates().size());
        assertEquals(GroundedSweepPlacementExecutor.ProgressBucket.CURRENT_CROSS_SECTION, selection.rankedCandidates().get(0).bucket());
        assertEquals(GroundedSweepPlacementExecutor.ProgressBucket.TRIVIAL_BEHIND_CLEANUP, selection.rankedCandidates().get(1).bucket());
    }

    @Test
    void appliesGracePeriodForFailedPlacementsBeforeMarkingFailed() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings());

        executor.recordPlacementResult(7, GroundedSweepPlacementExecutor.PlacementResult.FAILED, 100);
        GroundedSweepPlacementExecutor.SweepSelection duringGrace = executor.select(
                lane(GroundedLaneDirection.EAST, 15),
                bounds(),
                14,
                102,
                List.of(target(7, 14, 64, 15))
        );

        assertTrue(duringGrace.rankedCandidates().isEmpty());
        assertEquals(List.of(GroundedSweepLeftoverTracker.GroundedLeftoverReason.RETRY_DELAYED),
                duringGrace.leftovers().getFirst().reasons());

        executor.recordPlacementResult(7, GroundedSweepPlacementExecutor.PlacementResult.FAILED, 105);
        GroundedSweepPlacementExecutor.SweepSelection afterGrace = executor.select(
                lane(GroundedLaneDirection.EAST, 15),
                bounds(),
                14,
                106,
                List.of(target(7, 14, 64, 15))
        );

        assertTrue(afterGrace.leftovers().getFirst().reasons().contains(
                GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED));
    }

    @Test
    void recordsStructuredLeftoversForDeferredMissedAndFailed() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings());

        executor.select(
                lane(GroundedLaneDirection.EAST, 15),
                bounds(),
                14,
                10,
                List.of(target(2, 18, 64, 15))
        );
        executor.recordPlacementResult(3, GroundedSweepPlacementExecutor.PlacementResult.MISSED, 10);
        executor.recordPlacementResult(4, GroundedSweepPlacementExecutor.PlacementResult.FAILED, 10);
        executor.recordPlacementResult(4, GroundedSweepPlacementExecutor.PlacementResult.FAILED, 20);

        List<GroundedSweepLeftoverTracker.GroundedLeftoverRecord> leftovers = executor.select(
                lane(GroundedLaneDirection.EAST, 15),
                bounds(),
                14,
                21,
                List.of()
        ).leftovers();

        assertTrue(leftovers.stream().anyMatch(record -> record.placementIndex() == 2
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.DEFERRED)));
        assertTrue(leftovers.stream().anyMatch(record -> record.placementIndex() == 3
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.MISSED)));
        assertTrue(leftovers.stream().anyMatch(record -> record.placementIndex() == 4
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED)
                && record.reasons().contains(GroundedSweepLeftoverTracker.GroundedLeftoverReason.RETRY_DELAYED)));
    }

    private static GroundedSweepPlacementExecutorSettings settings() {
        return new GroundedSweepPlacementExecutorSettings(2, 1, 1, 4);
    }

    private static GroundedSchematicBounds bounds() {
        return new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(20, 64, 20)
        );
    }

    private static GroundedSweepLane lane(GroundedLaneDirection direction, int centerline) {
        return new GroundedSweepLane(
                0,
                centerline,
                direction,
                new BlockPos(10, 64, centerline),
                new BlockPos(20, 64, centerline),
                new GroundedLaneCorridorBounds(10, 20, 13, 17),
                1.0
        );
    }

    private static GroundedSweepPlacementExecutor.PlacementTarget target(int index, int x, int y, int z) {
        return new GroundedSweepPlacementExecutor.PlacementTarget(index, new BlockPos(x, y, z));
    }
}
