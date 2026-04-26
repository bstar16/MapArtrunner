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

        assertEquals(List.of(GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED),
                afterGrace.leftovers().getFirst().reasons());
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
                && record.reasons().equals(List.of(GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED))));
    }

    @Test
    void successfulPlacementClearsLeftoverReasonsAndGraceTracking() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings());

        executor.recordPlacementResult(9, GroundedSweepPlacementExecutor.PlacementResult.FAILED, 100);
        assertEquals(List.of(GroundedSweepLeftoverTracker.GroundedLeftoverReason.RETRY_DELAYED),
                executor.select(
                        lane(GroundedLaneDirection.EAST, 15),
                        bounds(),
                        14,
                        101,
                        List.of(target(9, 14, 64, 15))
                ).leftovers().getFirst().reasons());

        executor.recordPlacementResult(9, GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, 102);
        GroundedSweepPlacementExecutor.SweepSelection afterSuccess = executor.select(
                lane(GroundedLaneDirection.EAST, 15),
                bounds(),
                14,
                103,
                List.of(target(9, 14, 64, 15))
        );

        assertTrue(afterSuccess.leftovers().stream().noneMatch(record -> record.placementIndex() == 9));
        assertEquals(List.of(9), afterSuccess.rankedCandidates().stream()
                .map(GroundedSweepPlacementExecutor.SweepCandidate::placementIndex)
                .toList());
    }

    @Test
    void finalFailureBypassesGraceAndMarksFailedImmediately() {
        GroundedSweepPlacementExecutor executor = new GroundedSweepPlacementExecutor(settings());

        executor.recordPlacementResult(11, GroundedSweepPlacementExecutor.PlacementResult.FAILED, 100);
        assertEquals(List.of(GroundedSweepLeftoverTracker.GroundedLeftoverReason.RETRY_DELAYED),
                executor.select(
                        lane(GroundedLaneDirection.EAST, 15),
                        bounds(),
                        14,
                        101,
                        List.of(target(11, 14, 64, 15))
                ).leftovers().getFirst().reasons());

        executor.recordFinalFailure(11);
        GroundedSweepPlacementExecutor.SweepSelection finalized = executor.select(
                lane(GroundedLaneDirection.EAST, 15),
                bounds(),
                14,
                102,
                List.of(target(11, 14, 64, 15))
        );

        assertEquals(List.of(GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED),
                finalized.leftovers().getFirst().reasons());
        assertEquals(List.of(11), finalized.rankedCandidates().stream()
                .map(GroundedSweepPlacementExecutor.SweepCandidate::placementIndex)
                .toList());
    }

    @Test
    void mapsExecutorSettingsFromGroundedSweepSettings() {
        GroundedSweepSettings groundedSettings = new GroundedSweepSettings(
                false,
                3,
                7,
                5,
                2,
                4,
                true,
                false,
                1.0
        );

        GroundedSweepPlacementExecutorSettings executorSettings =
                GroundedSweepPlacementExecutorSettings.fromGroundedSweepSettings(groundedSettings);

        assertEquals(3, executorSettings.corridorHalfWidth());
        assertEquals(2, executorSettings.forwardLookaheadSteps());
        assertEquals(4, executorSettings.trivialBehindCleanupSteps());
        assertEquals(4, executorSettings.placementFailureGraceTicks());
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
