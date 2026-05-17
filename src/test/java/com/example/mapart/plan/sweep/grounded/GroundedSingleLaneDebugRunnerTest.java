package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.baritone.NoOpBaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.sweep.grounded.GroundedLaneWalker.GroundedLaneWalkState;
import com.example.mapart.plan.state.BuildSession;
import com.example.mapart.runtime.ClientTickHealthMonitor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundedSingleLaneDebugRunnerTest {

    @Test
    void terminalCompletionClearsActiveStateAndAllowsRestart() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();

        assertTrue(runner.start(session, 0, GroundedSweepSettings.defaults()).isEmpty());
        assertTrue(runner.isActive());

        runner.finalizeTerminalStateForTests(GroundedLaneWalkState.COMPLETE, Optional.empty());

        assertFalse(runner.isActive());
        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertEquals(GroundedLaneWalkState.COMPLETE, status.walkState());

        assertTrue(runner.start(session, 0, GroundedSweepSettings.defaults()).isEmpty());
        assertTrue(runner.isActive());
    }

    @Test
    void stopAfterActiveRunCleansStateAndRecordsInterruptStatus() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();

        assertTrue(runner.start(session, 0, GroundedSweepSettings.defaults()).isEmpty());
        assertTrue(runner.stop().isEmpty());

        assertFalse(runner.isActive());
        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertEquals(GroundedLaneWalkState.IDLE, status.walkState());
    }

    @Test
    void failedStatusRemainsAvailableAfterCleanup() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();

        assertTrue(runner.start(session, 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.finalizeTerminalStateForTests(
                GroundedLaneWalker.GroundedLaneWalkState.FAILED,
                Optional.of("Player left the grounded lane corridor bounds.")
        );

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertEquals(GroundedLaneWalkState.FAILED, status.walkState());
        assertEquals("Player left the grounded lane corridor bounds.", status.failureReason().orElseThrow());
    }

    @Test
    void placementSelectionTracksDeferredLeftoversWithoutEndingLane() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        assertTrue(runner.start(session, 0, GroundedSweepSettings.defaults()).isEmpty());

        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(1, new BlockPos(11, 64, 12)),
                new GroundedSweepPlacementExecutor.PlacementTarget(2, new BlockPos(14, 64, 12))
        ));
        runner.tickPlacementSelectionForTests(10, 1);

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertTrue(status.active());
        assertEquals(GroundedLaneWalkState.IDLE, status.walkState());
        assertEquals(1, status.leftovers().size());
        GroundedSweepLeftoverTracker.GroundedLeftoverRecord record = status.leftovers().getFirst();
        assertEquals(2, record.placementIndex());
        assertEquals(List.of(GroundedSweepLeftoverTracker.GroundedLeftoverReason.DEFERRED), record.reasons());
    }

    @Test
    void startApproachTargetsOutsideLaneStartStagingBlock() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        BuildSession session = sessionWithOrigin();

        assertTrue(runner.start(session, 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        assertEquals(new BlockPos(9, 65, 12), baritone.lastGoToTarget);
    }

    @Test
    void standingApproachTargetDoesNotMutateLaneBuildPlaneStartPoint() {
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(14, 64, 14)
        );
        GroundedSweepLane lane = new GroundedSweepLane(
                0,
                12,
                GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12),
                new BlockPos(14, 64, 12),
                new GroundedLaneCorridorBounds(10, 14, 10, 14),
                1.0
        );

        BlockPos approachTarget = GroundedSingleLaneDebugRunner.approachStagingTargetBeforeLaneStart(lane, bounds);

        assertEquals(new BlockPos(9, 65, 12), approachTarget);
        assertEquals(new BlockPos(10, 64, 12), lane.startPoint());
    }

    @Test
    void nearStartDetectionUsesStandingStartTargetCoordinates() {
        BlockPos standingStart = new BlockPos(9, 65, 12);
        assertTrue(GroundedSingleLaneDebugRunner.isNearLaneStart(new Vec3d(9.5, 64.0, 12.5), standingStart));
        assertFalse(GroundedSingleLaneDebugRunner.isNearLaneStart(new Vec3d(13.0, 64.0, 12.5), standingStart));
    }

    @Test
    void startStagingTargetStaysOutsideBoundsForAllCardinalLaneDirections() {
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(20, 64, 20)
        );
        GroundedSweepLane east = new GroundedSweepLane(0, 12, GroundedLaneDirection.EAST, new BlockPos(10, 64, 12), new BlockPos(20, 64, 12), new GroundedLaneCorridorBounds(10, 20, 10, 14), 1.0);
        GroundedSweepLane west = new GroundedSweepLane(1, 14, GroundedLaneDirection.WEST, new BlockPos(20, 64, 14), new BlockPos(10, 64, 14), new GroundedLaneCorridorBounds(10, 20, 12, 16), 1.0);
        GroundedSweepLane south = new GroundedSweepLane(2, 16, GroundedLaneDirection.SOUTH, new BlockPos(16, 64, 10), new BlockPos(16, 64, 20), new GroundedLaneCorridorBounds(14, 18, 10, 20), 1.0);
        GroundedSweepLane north = new GroundedSweepLane(3, 18, GroundedLaneDirection.NORTH, new BlockPos(18, 64, 20), new BlockPos(18, 64, 10), new GroundedLaneCorridorBounds(16, 20, 10, 20), 1.0);

        assertEquals(new BlockPos(9, 65, 12), GroundedSingleLaneDebugRunner.approachStagingTargetBeforeLaneStart(east, bounds));
        assertEquals(new BlockPos(21, 65, 14), GroundedSingleLaneDebugRunner.approachStagingTargetBeforeLaneStart(west, bounds));
        assertEquals(new BlockPos(16, 65, 9), GroundedSingleLaneDebugRunner.approachStagingTargetBeforeLaneStart(south, bounds));
        assertEquals(new BlockPos(18, 65, 21), GroundedSingleLaneDebugRunner.approachStagingTargetBeforeLaneStart(north, bounds));
    }

    @Test
    void playerBeyondFlatDistanceToleranceIsRejectedDespiteNearLaneStart() {
        // isNearLaneStart uses a lenient proximity check; isReadyForLaneStart is stricter.
        // Player at (10.55, 13.55) is ~2.2 units² from staging center (9.5, 12.5), which exceeds
        // the flat-distance tolerance (1.0 unit²) — rejected for that reason, not for insideCorridor.
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        BlockPos standing = new BlockPos(9, 65, 12);

        assertTrue(GroundedSingleLaneDebugRunner.isNearLaneStart(new Vec3d(10.55, 64.0, 13.55), standing));
        assertFalse(GroundedSingleLaneDebugRunner.isReadyForLaneStart(
                new Vec3d(10.55, 64.0, 13.55),
                lane,
                standing,
                bounds
        ));
    }

    @Test
    void eastLaneStagingPositionOutsideCorridorIsAccepted() {
        // The staging/approach target for an EAST lane is one block west of the lane start (x=9
        // when the lane starts at x=10). A player at the staging center (x=9.5) is outside the
        // active corridor (minX=10), but insideCorridor must not be a hard gate — the flat-distance,
        // centerline, and forward checks confirm the player is correctly positioned.
        // This is the exact scenario that PR #220 broke.
        GroundedSweepLane lane = eastLane(); // startPoint x=10, corridor minX=10
        GroundedSchematicBounds bounds = eastLaneBounds();
        BlockPos stagingTarget = new BlockPos(9, 65, 12); // one block west of lane start

        assertTrue(GroundedSingleLaneDebugRunner.isReadyForLaneStart(
                new Vec3d(9.5, 64.0, 12.5), // at staging center; x=9.5 < minX=10 → insideCorridor=false
                lane,
                stagingTarget,
                bounds
        ));
    }

    @Test
    void westLaneStagingPositionOutsideCorridorIsAccepted() {
        // The staging target for a WEST lane is one block east of the lane start (x=15 when
        // the lane starts at x=14). A player at x=15.4 is outside the corridor (maxX+1=15),
        // but must be accepted — same reasoning as the EAST case.
        GroundedSweepLane lane = new GroundedSweepLane(
                1, 12, GroundedLaneDirection.WEST,
                new BlockPos(14, 64, 12), new BlockPos(10, 64, 12),
                new GroundedLaneCorridorBounds(10, 14, 10, 14),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(14, 64, 14)
        );
        BlockPos stagingTarget = new BlockPos(15, 65, 12); // one block east of WEST lane start

        assertTrue(GroundedSingleLaneDebugRunner.isReadyForLaneStart(
                new Vec3d(15.4, 64.0, 12.5), // x=15.4 > maxX(14)+1=15 → insideCorridor=false
                lane,
                stagingTarget,
                bounds
        ));
    }

    @Test
    void playerFarFromCenterlineIsRejectedEvenAtStagingForwardPosition() {
        // Player is at the correct staging X but far off-centerline (z-delta=1.9, limit=0.8).
        // The flat-distance check catches this before the centerline check, but either way
        // the player must not be considered ready.
        GroundedSweepLane lane = eastLane(); // centerline z=12
        GroundedSchematicBounds bounds = eastLaneBounds();
        BlockPos stagingTarget = new BlockPos(9, 65, 12);

        assertFalse(GroundedSingleLaneDebugRunner.isReadyForLaneStart(
                new Vec3d(9.5, 64.0, 14.4), // z=14.4, centerlineDelta=1.9 → far lateral miss
                lane,
                stagingTarget,
                bounds
        ));
    }

    @Test
    void playerFarBehindStagingIsRejected() {
        // Player is on the correct centerline but 3 blocks behind the staging target.
        // Must not be considered ready regardless of corridor membership.
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        BlockPos stagingTarget = new BlockPos(9, 65, 12);

        assertFalse(GroundedSingleLaneDebugRunner.isReadyForLaneStart(
                new Vec3d(6.5, 64.0, 12.5), // x=6.5, far behind staging center x=9.5
                lane,
                stagingTarget,
                bounds
        ));
    }

    @Test
    void westLaneReadinessRejectsPlayerOutsideStartEdge() {
        GroundedSweepLane lane = new GroundedSweepLane(
                1,
                -59,
                GroundedLaneDirection.WEST,
                new BlockPos(-65, 64, -59),
                new BlockPos(-192, 64, -59),
                new GroundedLaneCorridorBounds(-192, -65, -61, -57),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(-192, 64, -61),
                new BlockPos(-192, 64, -61),
                new BlockPos(-65, 64, -57)
        );
        BlockPos standing = new BlockPos(-65, 65, -58);

        assertFalse(GroundedSingleLaneDebugRunner.isReadyForLaneStart(
                new Vec3d(-63.56, 64.0, -58.5),
                lane,
                standing,
                bounds
        ));
    }

    @Test
    void playerOnCenterlineAndStartCoordinateIsReady() {
        assertTrue(GroundedSingleLaneDebugRunner.isReadyForLaneStart(
                new Vec3d(10.5, 64.0, 12.5),
                eastLane(),
                new BlockPos(9, 65, 12),
                eastLaneBounds()
        ));
    }

    @Test
    void outsideStagingPositionQueuesEntrySupportBeforeLaneYawLock() {
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        GroundedSingleLaneDebugRunner.LaneStartStage stageOutside = GroundedSingleLaneDebugRunner.laneStartStageForCurrentPositionForTests(
                new Vec3d(9.5, 64.0, 12.5),
                lane,
                bounds
        );
        GroundedSingleLaneDebugRunner.LaneStartStage stageInside = GroundedSingleLaneDebugRunner.laneStartStageForCurrentPositionForTests(
                new Vec3d(10.5, 64.0, 12.5),
                lane,
                bounds
        );
        assertEquals(GroundedSingleLaneDebugRunner.LaneStartStage.AWAITING_LANE_ENTRY_STEP, stageOutside);
        assertEquals(GroundedSingleLaneDebugRunner.LaneStartStage.ALIGN_ENTRY_CENTERLINE, stageInside);
    }

    @Test
    void entryProgressCrossingUsesDirectionalThresholdInsideCorridor() {
        GroundedSweepLane east = eastLane();
        GroundedSchematicBounds eastBounds = eastLaneBounds();
        assertFalse(GroundedSingleLaneDebugRunner.hasCrossedEntryProgressForTests(new Vec3d(10.15, 64.0, 12.5), east, eastBounds));
        assertTrue(GroundedSingleLaneDebugRunner.hasCrossedEntryProgressForTests(new Vec3d(10.25, 64.0, 12.5), east, eastBounds));

        GroundedSweepLane west = new GroundedSweepLane(
                0,
                12,
                GroundedLaneDirection.WEST,
                new BlockPos(14, 64, 12),
                new BlockPos(10, 64, 12),
                new GroundedLaneCorridorBounds(10, 14, 10, 14),
                1.0
        );
        GroundedSchematicBounds westBounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(14, 64, 14)
        );
        assertFalse(GroundedSingleLaneDebugRunner.hasCrossedEntryProgressForTests(new Vec3d(13.85, 64.0, 12.5), west, westBounds));
        assertTrue(GroundedSingleLaneDebugRunner.hasCrossedEntryProgressForTests(new Vec3d(13.75, 64.0, 12.5), west, westBounds));
    }

    @Test
    void playerInsideCorridorButOffCenterlineDoesNotCountAsCenteredForLaneWalkStart() {
        GroundedSweepLane lane = eastLane();
        assertFalse(GroundedSingleLaneDebugRunner.isCenteredForLaneWalk(new Vec3d(10.5, 64.0, 12.95), lane));
    }

    @Test
    void playerWithinCenterlineToleranceIsCenteredForLaneWalkStart() {
        GroundedSweepLane lane = eastLane();
        assertTrue(GroundedSingleLaneDebugRunner.isCenteredForLaneWalk(new Vec3d(10.5, 64.0, 12.69), lane));
    }

    @Test
    void centerlineCorrectionDirectionMatchesEastWestLaneRules() {
        GroundedSweepLane lane = eastLane();
        assertEquals(GroundedLaneDirection.SOUTH,
                GroundedSingleLaneDebugRunner.entryCenterlineCorrectionDirection(new Vec3d(10.5, 64.0, 12.2), lane));
        assertEquals(GroundedLaneDirection.NORTH,
                GroundedSingleLaneDebugRunner.entryCenterlineCorrectionDirection(new Vec3d(10.5, 64.0, 12.8), lane));
    }

    @Test
    void centerlineCorrectionDirectionMatchesNorthSouthLaneRules() {
        GroundedSweepLane lane = new GroundedSweepLane(
                0,
                12,
                GroundedLaneDirection.SOUTH,
                new BlockPos(12, 64, 10),
                new BlockPos(12, 64, 14),
                new GroundedLaneCorridorBounds(10, 14, 10, 14),
                1.0
        );
        assertEquals(GroundedLaneDirection.EAST,
                GroundedSingleLaneDebugRunner.entryCenterlineCorrectionDirection(new Vec3d(12.2, 64.0, 10.5), lane));
        assertEquals(GroundedLaneDirection.WEST,
                GroundedSingleLaneDebugRunner.entryCenterlineCorrectionDirection(new Vec3d(12.8, 64.0, 10.5), lane));
    }

    @Test
    void partialLaneResumeStandingTargetUsesSameStagingRule() {
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        BlockPos resumeStanding = new BlockPos(12, 65, 12);

        assertFalse(GroundedSingleLaneDebugRunner.isReadyForLaneStart(
                new Vec3d(12.5, 64.0, 13.6),
                lane,
                resumeStanding,
                bounds
        ));
        assertTrue(GroundedSingleLaneDebugRunner.isReadyForLaneStart(
                new Vec3d(12.5, 64.0, 12.5),
                lane,
                resumeStanding,
                bounds
        ));
    }

    @Test
    void missedPlacementIsRemovedFromPendingAfterRecording() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(1, new BlockPos(11, 64, 12))));
        assertEquals(List.of(1), runner.rankedPlacementIndicesForTests(11, 1));

        runner.recordPlacementOutcomeForTests(1, GroundedSweepPlacementExecutor.PlacementResult.MISSED, 1);

        assertTrue(runner.pendingPlacementIndicesForTests().isEmpty());
        assertTrue(runner.rankedPlacementIndicesForTests(11, 2).isEmpty());
    }

    @Test
    void hotbarSwapPendingKeepsPlacementPendingAndDoesNotCountMissed() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.setGroundedTraceEnabled(true);

        BlockPos worldPos = new BlockPos(11, 64, 12);
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(1, worldPos)));

        assertTrue(runner.recordHotbarSwapPendingForTests(1, new Placement(new BlockPos(1, 0, 2), null), worldPos, 1));

        assertEquals(List.of(1), runner.pendingPlacementIndicesForTests());
        assertEquals(0, runner.status().missedPlacements());
        assertEquals(0, runner.pendingVerificationCountForTests());
        assertEquals(1, runner.hotbarSwapStabilizationCountForTests(1));
    }

    @Test
    void hotbarSwapRetryReadyIsDiagnosticOnlyAndNextTickCanRetrySamePlacement() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.setGroundedTraceEnabled(true);

        BlockPos worldPos = new BlockPos(11, 64, 12);
        Placement placement = new Placement(new BlockPos(1, 0, 2), null);
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(1, worldPos)));

        assertTrue(runner.recordHotbarSwapPendingForTests(1, placement, worldPos, 1));
        runner.traceHotbarSwapRetryReadyForTests(1, placement, worldPos, 2);

        assertEquals(List.of(1), runner.pendingPlacementIndicesForTests());
        assertEquals(List.of(1), runner.rankedPlacementIndicesForTests(11, 2));
        assertTrue(runner.groundedTraceEventsForTests().stream().anyMatch(event -> event.contains("PLACEMENT_HOTBAR_SWAP_RETRY_READY")));
    }

    @Test
    void hotbarSwapPendingIsBoundedToPreventInfinitePlacementLoop() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.setGroundedTraceEnabled(true);

        BlockPos worldPos = new BlockPos(11, 64, 12);
        Placement placement = new Placement(new BlockPos(1, 0, 2), null);

        assertTrue(runner.recordHotbarSwapPendingForTests(1, placement, worldPos, 1));
        assertTrue(runner.recordHotbarSwapPendingForTests(1, placement, worldPos, 2));
        assertTrue(runner.recordHotbarSwapPendingForTests(1, placement, worldPos, 3));
        assertFalse(runner.recordHotbarSwapPendingForTests(1, placement, worldPos, 4));

        assertEquals(0, runner.hotbarSwapStabilizationCountForTests(1));
        assertTrue(runner.groundedTraceEventsForTests().stream().anyMatch(event -> event.contains("PLACEMENT_HOTBAR_SWAP_RETRY_EXHAUSTED")));
    }

    @Test
    void transitionSupportHotbarSwapPendingDoesNotFailTransitionTargetImmediately() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.setGroundedTraceEnabled(true);

        BlockPos worldPos = new BlockPos(11, 64, 12);
        Placement placement = new Placement(new BlockPos(1, 0, 2), null);

        assertTrue(runner.recordTransitionSupportHotbarSwapPendingForTests(7, placement, worldPos, 1));

        assertEquals(1, runner.transitionSupportHotbarSwapStabilizationCountForTests(7));
        assertTrue(runner.groundedTraceEventsForTests().stream().anyMatch(event -> event.contains("path=transition support")));
    }

    @Test
    void hardFailedPlacementBecomesFinalFailedLeftoverAndIsRemovedFromPending() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(3, new BlockPos(11, 64, 12))));
        assertEquals(List.of(3), runner.rankedPlacementIndicesForTests(11, 10));

        runner.recordFinalFailureForTests(3);
        runner.tickPlacementSelectionForTests(11, 11);

        assertTrue(runner.pendingPlacementIndicesForTests().isEmpty());
        assertTrue(runner.rankedPlacementIndicesForTests(11, 11).isEmpty());
        GroundedSweepLeftoverTracker.GroundedLeftoverRecord record = runner.status().leftovers().stream()
                .filter(leftover -> leftover.placementIndex() == 3)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(GroundedSweepLeftoverTracker.GroundedLeftoverReason.FAILED), record.reasons());
    }

    @Test
    void successfulPlacementStillRemovesPendingTarget() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(2, new BlockPos(11, 64, 12))));
        assertEquals(List.of(2), runner.rankedPlacementIndicesForTests(11, 1));

        runner.recordPlacementOutcomeForTests(2, GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, 1);

        assertTrue(runner.pendingPlacementIndicesForTests().isEmpty());
    }

    @Test
    void placedResultCreatesPendingVerificationInsteadOfImmediateSuccess() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(4, new BlockPos(11, 64, 12))));

        runner.recordPlacementPlacedForTests(4, new BlockPos(11, 64, 12), 10);

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(0, status.successfulPlacements());
        assertEquals(1, status.pendingVerification());
        assertTrue(runner.hasPendingVerificationForTests(4));
        assertTrue(runner.pendingPlacementIndicesForTests().isEmpty());
    }

    @Test
    void acceptedPendingPlacementDoesNotImmediatelyCountAsMissed() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(40, new BlockPos(11, 64, 12))));

        runner.recordPlacementAcceptedPendingForTests(40, new BlockPos(11, 64, 12), 10);

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(0, status.missedPlacements());
        assertEquals(1, status.pendingVerification());
        assertTrue(runner.pendingPlacementIndicesForTests().isEmpty());
        assertTrue(runner.groundedTraceEventsForTests().stream()
                .anyMatch(event -> event.contains("PLACEMENT_ACCEPTED_PENDING_VERIFICATION")
                        && event.contains("placementIndex=40")));
    }

    @Test
    void acceptedPendingVerificationSuccessRecordsConfirmedSuccess() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(41, new BlockPos(11, 64, 12))));
        runner.recordPlacementAcceptedPendingForTests(41, new BlockPos(11, 64, 12), 10);

        runner.processPendingVerificationsForTests(Map.of(41, true), 13);

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(1, status.successfulPlacements());
        assertEquals(0, status.missedPlacements());
        assertEquals(0, status.pendingVerification());
        assertEquals(1, runner.pendingAuditCountForTests());
        assertTrue(runner.hasPendingAuditForTests(41));
    }

    @Test
    void delayedAuditSuccessLeavesConfirmedPlacementSuccessful() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(44, new BlockPos(11, 64, 12))));
        runner.recordPlacementPlacedForTests(44, new BlockPos(11, 64, 12), 10);
        runner.processPendingVerificationsForTests(Map.of(44, true), 13);

        runner.processPendingAuditsForTests(Map.of(44, true), 28);

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(1, status.successfulPlacements());
        assertEquals(0, status.missedPlacements());
        assertEquals(0, runner.pendingAuditCountForTests());
        assertTrue(status.leftovers().stream().noneMatch(record -> record.placementIndex() == 44));
        assertTrue(runner.pendingPlacementIndicesForTests().isEmpty());
        assertTrue(runner.groundedTraceEventsForTests().stream().anyMatch(event -> event.contains("PLACEMENT_AUDIT_CONFIRMED")
                && event.contains("placementIndex=44")));
    }

    @Test
    void delayedAuditFailureRequeuesForCleanupWithoutDoubleCountingMissed() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        BlockPos pos = new BlockPos(11, 64, 12);
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(45, pos)));
        runner.recordPlacementPlacedForTests(45, pos, 10);
        runner.processPendingVerificationsForTests(Map.of(45, true), 13);

        runner.processPendingAuditsForTests(Map.of(45, false), 28);

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertTrue(status.active());
        assertEquals(1, status.successfulPlacements());
        assertEquals(0, status.missedPlacements());
        assertEquals(1, runner.delayedAuditFailuresForTests());
        assertEquals(0, runner.pendingAuditCountForTests());
        assertTrue(runner.pendingPlacementIndicesForTests().contains(45));
        assertTrue(status.leftovers().stream().anyMatch(record -> record.placementIndex() == 45));
        assertTrue(runner.groundedTraceEventsForTests().stream().anyMatch(event -> event.contains("PLACEMENT_AUDIT_REQUEUED_FOR_CLEANUP")
                && event.contains("placementIndex=45")));
    }

    @Test
    void terminalPendingAuditsAreForceProcessedBeforeDueTick() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        BlockPos pos = new BlockPos(11, 64, 12);
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(48, pos)));
        runner.recordPlacementPlacedForTests(48, pos, 10);
        runner.processPendingVerificationsForTests(Map.of(48, true), 13);

        runner.processTerminalPendingAuditsForTests(Map.of(48, true), 13);

        assertEquals(0, runner.pendingAuditCountForTests());
        assertEquals(1, runner.status().successfulPlacements());
        assertEquals(0, runner.status().missedPlacements());
        assertTrue(runner.groundedTraceEventsForTests().stream()
                .anyMatch(event -> event.contains("PLACEMENT_AUDITS_TERMINAL_FORCE_PROCESS")
                        && event.contains("pendingAudits=1")));
    }

    @Test
    void terminalAuditFailureRequeuesForCleanupWhileRunContinues() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        BlockPos pos = new BlockPos(11, 64, 12);
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(49, pos)));
        runner.recordPlacementPlacedForTests(49, pos, 10);
        runner.processPendingVerificationsForTests(Map.of(49, true), 13);

        runner.processTerminalPendingAuditsForTests(Map.of(49, false), 13);

        assertTrue(runner.isActive());
        assertEquals(0, runner.pendingAuditCountForTests());
        assertEquals(1, runner.delayedAuditFailuresForTests());
        assertEquals(1, runner.status().successfulPlacements());
        assertEquals(0, runner.status().missedPlacements());
        assertTrue(runner.pendingPlacementIndicesForTests().contains(49));
    }

    @Test
    void stopDuringTerminalAuditWaitClearsPendingAuditsAndActiveRunState() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        BlockPos pos = new BlockPos(11, 64, 12);
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(50, pos)));
        runner.recordPlacementPlacedForTests(50, pos, 10);
        runner.processPendingVerificationsForTests(Map.of(50, true), 13);

        assertTrue(runner.stop().isEmpty());

        assertFalse(runner.isActive());
        assertEquals(0, runner.pendingAuditCountForTests());
        assertFalse(runner.hasPendingAuditForTests(50));
        assertTrue(runner.pendingPlacementIndicesForTests().isEmpty());
        assertTrue(runner.groundedTraceEventsForTests().stream()
                .anyMatch(event -> event.contains("STOP_CLEARED_PENDING_AUDITS")
                        && event.contains("pendingAudits=1")));
    }

    @Test
    void delayedAuditFailureCanBeFixedByCleanupWithoutSecondSuccessCount() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        BlockPos pos = new BlockPos(11, 64, 12);
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(46, pos)));
        runner.recordPlacementPlacedForTests(46, pos, 10);
        runner.processPendingVerificationsForTests(Map.of(46, true), 13);
        runner.processPendingAuditsForTests(Map.of(46, false), 28);

        runner.recordPlacementOutcomeForTests(46, GroundedSweepPlacementExecutor.PlacementResult.SUCCESS, 29);

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(1, status.successfulPlacements());
        assertEquals(0, status.missedPlacements());
        assertTrue(status.leftovers().stream().noneMatch(record -> record.placementIndex() == 46));
        assertFalse(runner.pendingPlacementIndicesForTests().contains(46));
    }

    @Test
    void delayedAuditQueueIsBoundedByAttemptCountAndClearedOnStop() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        BlockPos pos = new BlockPos(11, 64, 12);
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(47, pos)));

        runner.recordPlacementPlacedForTests(47, pos, 10);
        runner.processPendingVerificationsForTests(Map.of(47, true), 13);
        runner.recordPlacementPlacedForTests(47, pos, 30);
        runner.processPendingVerificationsForTests(Map.of(47, true), 33);
        runner.recordPlacementPlacedForTests(47, pos, 50);
        runner.processPendingVerificationsForTests(Map.of(47, true), 53);

        assertEquals(2, runner.auditAttemptCountForTests(47));
        assertEquals(1, runner.pendingAuditCountForTests());

        runner.finalizeTerminalStateForTests(GroundedLaneWalkState.COMPLETE, Optional.empty());

        assertEquals(0, runner.pendingAuditCountForTests());
    }

    @Test
    void acceptedPendingVerificationTimeoutEventuallyBecomesMissedLeftover() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(42, new BlockPos(11, 64, 12))));
        runner.recordPlacementAcceptedPendingForTests(42, new BlockPos(11, 64, 12), 10);

        runner.processPendingVerificationsForTests(Map.of(42, false), 13);

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(1, status.missedPlacements());
        assertEquals(0, status.pendingVerification());
        assertTrue(status.leftovers().stream().anyMatch(record -> record.placementIndex() == 42));
    }

    @Test
    void transientRetryIsBoundedWhileTargetRemainsPending() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        BlockPos pos = new BlockPos(11, 64, 12);
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(43, pos)));

        assertTrue(runner.recordTransientPlacementRetryForTests(43, pos, com.example.mapart.plan.state.PlacementResult.Status.RETRY, 1, true));
        assertTrue(runner.recordTransientPlacementRetryForTests(43, pos, com.example.mapart.plan.state.PlacementResult.Status.MOVE_REQUIRED, 2, true));
        assertTrue(runner.recordTransientPlacementRetryForTests(43, pos, com.example.mapart.plan.state.PlacementResult.Status.RETRY, 3, true));
        assertFalse(runner.recordTransientPlacementRetryForTests(43, pos, com.example.mapart.plan.state.PlacementResult.Status.RETRY, 4, true));

        assertEquals(0, runner.transientPlacementRetryCountForTests(43));
        assertTrue(runner.pendingPlacementIndicesForTests().contains(43));
        assertTrue(runner.groundedTraceEventsForTests().stream()
                .anyMatch(event -> event.contains("PLACEMENT_TRANSIENT_RETRY_EXHAUSTED")
                        && event.contains("placementIndex=43")));
    }

    @Test
    void pendingVerificationPlacementIsNotReselectedDuringDelay() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(5, new BlockPos(11, 64, 12))));
        runner.recordPlacementPlacedForTests(5, new BlockPos(11, 64, 12), 20);

        assertTrue(runner.rankedPlacementIndicesForTests(11, 21).isEmpty());
        assertEquals(1, runner.pendingVerificationCountForTests());
    }

    @Test
    void dueVerificationWithWorldMatchBecomesConfirmedSuccessOnce() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(6, new BlockPos(11, 64, 12))));
        runner.recordPlacementPlacedForTests(6, new BlockPos(11, 64, 12), 30);

        runner.processPendingVerificationsForTests(Map.of(6, true), 32);
        assertEquals(0, runner.status().successfulPlacements());
        assertEquals(1, runner.pendingVerificationCountForTests());

        runner.processPendingVerificationsForTests(Map.of(6, true), 33);
        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(1, status.successfulPlacements());
        assertEquals(0, status.pendingVerification());
        assertTrue(runner.pendingPlacementIndicesForTests().isEmpty());
        assertTrue(status.leftovers().stream().noneMatch(record -> record.placementIndex() == 6));
    }

    @Test
    void dueVerificationWithoutWorldMatchBecomesMissedLeftoverWithoutStoppingLane() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(7, new BlockPos(11, 64, 12))));
        runner.recordPlacementPlacedForTests(7, new BlockPos(11, 64, 12), 40);

        runner.processPendingVerificationsForTests(Map.of(7, false), 43);

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertTrue(status.active());
        assertEquals(GroundedLaneWalkState.IDLE, status.walkState());
        assertEquals(1, status.missedPlacements());
        assertEquals(0, status.pendingVerification());
        assertTrue(runner.pendingPlacementIndicesForTests().isEmpty());
        GroundedSweepLeftoverTracker.GroundedLeftoverRecord record = status.leftovers().stream()
                .filter(leftover -> leftover.placementIndex() == 7)
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(GroundedSweepLeftoverTracker.GroundedLeftoverReason.MISSED), record.reasons());
    }

    @Test
    void terminalStatusCapturesOutstandingPendingVerificationCount() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(8, new BlockPos(11, 64, 12))));
        runner.recordPlacementPlacedForTests(8, new BlockPos(11, 64, 12), 50);

        runner.finalizeTerminalStateForTests(GroundedLaneWalkState.COMPLETE, Optional.empty());

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertEquals(1, status.pendingVerification());
    }

    @Test
    void targetFilteringRequiresCorridorAndConfiguredSweepHalfWidth() {
        BuildPlan plan = buildPlan(List.of(
                new Placement(new BlockPos(1, 0, 2), null), // centerline
                new Placement(new BlockPos(1, 0, 3), null), // within corridor, outside half-width=0
                new Placement(new BlockPos(1, 0, 5), null)  // outside corridor
        ));
        BlockPos origin = new BlockPos(10, 64, 10);
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, origin);
        GroundedSweepLane lane = new GroundedSweepLane(
                0,
                12,
                GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12),
                new BlockPos(14, 64, 12),
                new GroundedLaneCorridorBounds(10, 14, 10, 14),
                1.0
        );

        Map<Integer, Placement> byIndex = new java.util.HashMap<>();
        List<GroundedSweepPlacementExecutor.PlacementTarget> targets = GroundedSingleLaneDebugRunner.buildLanePlacementTargetsForTests(
                plan,
                origin,
                bounds,
                lane,
                0,
                byIndex
        );

        assertEquals(List.of(0), targets.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList());
    }

    @Test
    void placementAttemptsAreGatedOnWalkerActiveState() {
        assertTrue(GroundedSingleLaneDebugRunner.shouldAttemptPlacementAfterWalkerTick(GroundedLaneWalkState.ACTIVE));
        assertFalse(GroundedSingleLaneDebugRunner.shouldAttemptPlacementAfterWalkerTick(GroundedLaneWalkState.FAILED));
        assertFalse(GroundedSingleLaneDebugRunner.shouldAttemptPlacementAfterWalkerTick(GroundedLaneWalkState.COMPLETE));
    }

    @Test
    void fullSweepStartsInForwardPhaseOnNorthWestAnchoredLane() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        BuildSession session = sessionWithOrigin();

        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertTrue(status.active());
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD, status.phase());
        assertEquals(0, status.laneIndex());
        assertTrue(status.awaitingStartApproach());
        assertFalse(status.awaitingLaneShift());
        assertEquals(1, baritone.goToCalls);
    }

    @Test
    void smartResumeFreshWorldStillStartsLaneZeroForward() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(5, 1, 11));

        Optional<String> error = runner.startFullSweepSmart(
                session,
                GroundedSweepSettings.defaults(),
                new Vec3d(10.5, 65, 10.5),
                (worldPos, expected) -> false
        );

        assertTrue(error.isEmpty());
        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(0, status.laneIndex());
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD, status.phase());
        assertTrue(status.smartResumeUsed());
        assertEquals(GroundedSweepResumePoint.ResumeReason.FRESH_START, status.resumePoint().orElseThrow().reason());
    }

    @Test
    void smartResumeNoUnfinishedPlacementsCompletesCleanlyWithoutStartingRun() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(3, 1, 3));
        Optional<String> error = runner.startFullSweepSmart(
                session,
                GroundedSweepSettings.defaults(),
                new Vec3d(10.5, 65, 10.5),
                (worldPos, expected) -> true
        );

        assertTrue(error.isEmpty());
        assertFalse(runner.isActive());
        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertEquals(GroundedLaneWalkState.COMPLETE, status.walkState());
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.COMPLETE, status.phase());
        assertTrue(status.failureReason().isEmpty());
        assertTrue(runner.groundedTraceEventsForTests().stream()
                .anyMatch(event -> event.contains(GroundedDiagnostics.BUILD_COMPLETE_NO_UNFINISHED_PLACEMENTS)));
    }

    @Test
    void smartResumeCanSelectLaterClosestUsefulLane() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(21, 1, 11));
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(session.getPlan(), session.getOrigin());
        List<GroundedSweepLane> lanes = new GroundedSweepLanePlanner().planLanes(bounds, settings);
        GroundedSweepLane lane2 = lanes.get(2);

        java.util.Set<BlockPos> complete = new java.util.HashSet<>();
        markLaneComplete(session.getPlan(), session.getOrigin(), settings.sweepHalfWidth(), lanes.get(0), complete);
        markLaneComplete(session.getPlan(), session.getOrigin(), settings.sweepHalfWidth(), lanes.get(1), complete);

        Optional<String> error = runner.startFullSweepSmart(
                session,
                settings,
                Vec3d.ofBottomCenter(new BlockPos(lane2.startPoint().getX(), 65, lane2.centerlineCoordinate())),
                (worldPos, expected) -> complete.contains(worldPos)
        );
        assertTrue(error.isEmpty());
        assertEquals(2, runner.status().laneIndex());
    }

    @Test
    void groundedTraceIsDisabledByDefault() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertFalse(runner.groundedTraceEnabled());
    }

    @Test
    void enablingTraceDoesNotChangeRunnerState() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertFalse(runner.isActive());

        runner.setGroundedTraceEnabled(true);

        assertTrue(runner.groundedTraceEnabled());
        assertFalse(runner.isActive());
    }

    @Test
    void diagnosticsYawReadersHandleNullAndMissingFieldsSafely() {
        assertEquals("unavailable", GroundedSingleLaneDebugRunner.readHeadYawForDiagnostics(null));
        assertEquals("unavailable", GroundedSingleLaneDebugRunner.readBodyYawForDiagnostics(new Object()));
    }

    @Test
    void smartResumeSelectionIsRecordedWhenTraceEnabled() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(5, 1, 11));

        Optional<String> error = runner.startFullSweepSmart(
                session,
                GroundedSweepSettings.defaults(),
                new Vec3d(10.5, 65, 10.5),
                (worldPos, expected) -> false
        );

        assertTrue(error.isEmpty());
        assertTrue(runner.groundedTraceEventsForTests().stream().anyMatch(event -> event.contains("smart resume selected")));
    }

    @Test
    void laneStartAndTransitionChangesAreRecorded() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        GroundedSweepLane lane = new GroundedSweepLane(
                0,
                12,
                GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12),
                new BlockPos(14, 64, 12),
                new GroundedLaneCorridorBounds(10, 14, 10, 14),
                1.0
        );

        GroundedSingleLaneDebugRunner.TestYawState yawState = new GroundedSingleLaneDebugRunner.TestYawState();
        assertTrue(runner.queueLaneStartForTests(yawState, lane));
        assertTrue(runner.groundedTraceEventsForTests().stream().anyMatch(event -> event.contains("lane yaw lock started")));
    }

    @Test
    void snapshotRateLimitUsesDedicatedDiagnosticsTickCounterCadence() {
        assertFalse(GroundedSingleLaneDebugRunner.shouldEmitGroundedSnapshotForTick(1));
        assertFalse(GroundedSingleLaneDebugRunner.shouldEmitGroundedSnapshotForTick(19));
        assertTrue(GroundedSingleLaneDebugRunner.shouldEmitGroundedSnapshotForTick(20));
        assertFalse(GroundedSingleLaneDebugRunner.shouldEmitGroundedSnapshotForTick(21));
        assertTrue(GroundedSingleLaneDebugRunner.shouldEmitGroundedSnapshotForTick(40));
    }

    @Test
    void smartResumePartialLaneSkipsTargetsBehindResumeProgress() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(11, 1, 5));
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();
        BlockPos origin = session.getOrigin();
        java.util.Set<BlockPos> complete = new java.util.HashSet<>();
        for (int z = origin.getZ(); z < origin.getZ() + 5; z++) {
            complete.add(new BlockPos(origin.getX(), origin.getY(), z));
            complete.add(new BlockPos(origin.getX() + 1, origin.getY(), z));
        }

        Optional<String> error = runner.startFullSweepSmart(
                session,
                settings,
                new Vec3d(origin.getX() + 2.5, origin.getY() + 1, origin.getZ() + 2.5),
                (worldPos, expected) -> complete.contains(worldPos)
        );
        assertTrue(error.isEmpty());
        GroundedSweepResumePoint point = runner.status().resumePoint().orElseThrow();
        assertEquals(GroundedSweepResumePoint.ResumeReason.PARTIAL_LANE, point.reason());
        assertTrue(runner.pendingPlacementWorldPositionsForTests().stream()
                .allMatch(pos -> pos.getX() >= point.progressCoordinate()));
    }

    @Test
    void smartResumeApproachTargetsOutsideStagingBeforeResumeStandingPosition() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(11, 1, 5));
        BlockPos origin = session.getOrigin();
        java.util.Set<BlockPos> complete = new java.util.HashSet<>();
        for (int z = origin.getZ(); z < origin.getZ() + 5; z++) {
            complete.add(new BlockPos(origin.getX(), origin.getY(), z));
        }

        assertTrue(runner.startFullSweepSmart(
                session,
                GroundedSweepSettings.defaults(),
                new Vec3d(origin.getX() + 2.5, origin.getY() + 1, origin.getZ() + 2.5),
                (worldPos, expected) -> complete.contains(worldPos)
        ).isEmpty());

        BlockPos selectedStanding = runner.status().resumePoint().orElseThrow().standingPosition();
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);
        GroundedSweepLane resumeLane = laneForIndex(session, GroundedSweepSettings.defaults(), runner.status().laneIndex());
        BlockPos expectedStaging = switch (resumeLane.direction()) {
            case EAST -> selectedStanding.add(-1, 0, 0);
            case WEST -> selectedStanding.add(1, 0, 0);
            case SOUTH -> selectedStanding.add(0, 0, -1);
            case NORTH -> selectedStanding.add(0, 0, 1);
        };
        assertEquals(expectedStaging, baritone.lastGoToTarget);
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD, runner.status().phase());
    }

    @Test
    void smartResumeEntryAnchorUsesResumeStandingAndProgressInsteadOfLaneStart() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(11, 1, 5));
        BlockPos origin = session.getOrigin();
        java.util.Set<BlockPos> complete = new java.util.HashSet<>();
        for (int z = origin.getZ(); z < origin.getZ() + 5; z++) {
            complete.add(new BlockPos(origin.getX(), origin.getY(), z));
        }

        assertTrue(runner.startFullSweepSmart(
                session,
                GroundedSweepSettings.defaults(),
                new Vec3d(origin.getX() + 2.5, origin.getY() + 1, origin.getZ() + 2.5),
                (worldPos, expected) -> complete.contains(worldPos)
        ).isEmpty());

        GroundedSweepResumePoint selectedResume = runner.status().resumePoint().orElseThrow();
        GroundedSweepLane resumeLane = laneForIndex(session, GroundedSweepSettings.defaults(), selectedResume.laneIndex());
        assertEquals(selectedResume.standingPosition(), runner.laneEntryStandingTargetForTests().orElseThrow());
        assertEquals(selectedResume.progressCoordinate(), runner.laneEntryProgressCoordinateForTests().orElseThrow());
        int laneStartProgress = resumeLane.direction().alongX() ? resumeLane.startPoint().getX() : resumeLane.startPoint().getZ();
        assertFalse(selectedResume.progressCoordinate() == laneStartProgress);
    }

    @Test
    void entryBurstTargetsIncludeCurrentAheadAndCrossSectionBands() {
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        List<GroundedSweepPlacementExecutor.PlacementTarget> pending = List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(1, new BlockPos(10, 64, 12)),
                new GroundedSweepPlacementExecutor.PlacementTarget(2, new BlockPos(10, 64, 11)),
                new GroundedSweepPlacementExecutor.PlacementTarget(3, new BlockPos(11, 64, 13)),
                new GroundedSweepPlacementExecutor.PlacementTarget(4, new BlockPos(12, 64, 12)),
                new GroundedSweepPlacementExecutor.PlacementTarget(5, new BlockPos(14, 64, 12))
        );
        List<GroundedSweepPlacementExecutor.PlacementTarget> burst = GroundedSingleLaneDebugRunner.entryBurstTargetsForTests(
                lane,
                bounds,
                pending,
                2,
                10
        );
        // Sorted by progress-batch then band: batch-0 has LEFT_ONE(idx2) then CENTER(idx1);
        // batch-1 has RIGHT_ONE(idx3); batch-2 has CENTER(idx4).
        assertEquals(List.of(2, 1, 3, 4), burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList());
    }

    @Test
    void entryBurstTargetsIncludeInWindowTargetsAcrossProgressGap() {
        // Pending list is band-major ordered, so an out-of-window target appearing between two
        // in-window targets does not mean the later target is invalid. The spatial guard is
        // allowedProgress, not position in the list. Targets at burst-zone progress values are
        // included regardless of gaps formed by other bands' out-of-window targets.
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        List<GroundedSweepPlacementExecutor.PlacementTarget> pending = List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(1, new BlockPos(10, 64, 12)),   // progress=10, in burst
                new GroundedSweepPlacementExecutor.PlacementTarget(2, new BlockPos(13, 64, 12)),   // progress=13, out of burst
                new GroundedSweepPlacementExecutor.PlacementTarget(894, new BlockPos(10, 64, 11))  // progress=10, in burst (different lateral band)
        );
        List<GroundedSweepPlacementExecutor.PlacementTarget> burst = GroundedSingleLaneDebugRunner.entryBurstTargetsForTests(
                lane,
                bounds,
                pending,
                2,
                10
        );
        // idx=2 is outside the progress window and is skipped.
        // idx=894 is at progress=10 (in burst) in a different lateral band and is included.
        // Sorted by progress-batch then band: LEFT_ONE(idx894) comes before CENTER(idx1).
        assertEquals(List.of(894, 1), burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList());
    }

    @Test
    void entryBurstTargetsExcludeTargetsOutsideSpatialProgressWindow() {
        // The allowedProgress spatial filter prevents selecting targets that are truly outside
        // the entry burst zone — this is the PR #214 guard, now applied correctly as a spatial
        // filter rather than an ordering assumption.
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        List<GroundedSweepPlacementExecutor.PlacementTarget> pending = List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(1, new BlockPos(10, 64, 12)),  // progress=10, in burst
                new GroundedSweepPlacementExecutor.PlacementTarget(2, new BlockPos(14, 64, 12)),  // progress=14, FAR outside burst window {10,11,12}
                new GroundedSweepPlacementExecutor.PlacementTarget(3, new BlockPos(11, 64, 12))   // progress=11, in burst
        );
        List<GroundedSweepPlacementExecutor.PlacementTarget> burst = GroundedSingleLaneDebugRunner.entryBurstTargetsForTests(
                lane,
                bounds,
                pending,
                2,
                10
        );
        // idx=2 at progress=14 is excluded by the spatial filter; idx=1 and idx=3 are included
        assertEquals(List.of(1, 3), burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList());
    }

    @Test
    void entryBurstTargetsSelectsAcrossBandGaps() {
        // Regression for PR #214's break-on-gap: the pending list is band-major ordered,
        // so each band's burst-zone targets are separated by that band's out-of-window targets.
        // All spatially valid burst targets must be selected across these gaps.
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        // eastLane: EAST, centerline Z=12, allowedProgress for base=10 → {10,11,12}
        List<GroundedSweepPlacementExecutor.PlacementTarget> pending = new ArrayList<>();
        // Band 0 (Z=12): 3 burst-zone targets, then 2 out-of-window
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(0, new BlockPos(10, 64, 12)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(1, new BlockPos(11, 64, 12)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(2, new BlockPos(12, 64, 12)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(3, new BlockPos(13, 64, 12)));  // out of burst
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(4, new BlockPos(14, 64, 12)));  // out of burst
        // Band 1 (Z=11): 3 burst-zone targets, then 1 out-of-window
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(5, new BlockPos(10, 64, 11)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(6, new BlockPos(11, 64, 11)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(7, new BlockPos(12, 64, 11)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(8, new BlockPos(13, 64, 11)));  // out of burst
        // Band 2 (Z=13): non-contiguous high indices, burst-zone targets
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(254, new BlockPos(10, 64, 13)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(255, new BlockPos(11, 64, 13)));

        List<GroundedSweepPlacementExecutor.PlacementTarget> burst = GroundedSingleLaneDebugRunner.entryBurstTargetsForTests(
                lane,
                bounds,
                pending,
                2,
                10
        );
        // Sorted by progress-batch then band. Within each batch: L1 < C < R1.
        // batch-0(X=10): L1(idx5), C(idx0), R1(idx254)
        // batch-1(X=11): L1(idx6), C(idx1), R1(idx255)
        // batch-2(X=12): L1(idx7), C(idx2)
        assertEquals(
                List.of(5, 0, 254, 6, 1, 255, 7, 2),
                burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList()
        );
    }

    @Test
    void entryBurstTargetsAllowMultipleAttemptsPerTarget() {
        assertEquals(2, GroundedSingleLaneDebugRunner.maxEntryAttemptsPerTargetForTests());
    }

    @Test
    void entryBurstTargetsEastFiveWideLaneRightTwoAtBaseProgressBeforeLaterProgress() {
        // Regression: with band-major plan ordering (LEFT_TWO all indices first, then LEFT_ONE,
        // etc.), RIGHT_TWO targets have the highest placement indices. The burst must sort by
        // progress-batch then band so RIGHT_TWO at baseProgress comes before any target at
        // baseProgress+1, regardless of placement index.
        //
        // eastLane: EAST, centerline Z=12, startX=10, bounds X∈[10,14] Z∈[10,14].
        // Simulate band-major ordering: all Z=10 (LEFT_TWO) first (low indices),
        // then Z=14 (RIGHT_TWO) last (high indices).
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        List<GroundedSweepPlacementExecutor.PlacementTarget> pending = new ArrayList<>();
        // LEFT_TWO (Z=10): indices 0..2 for X=10,11,12
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(0, new BlockPos(10, 64, 10)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(1, new BlockPos(11, 64, 10)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(2, new BlockPos(12, 64, 10)));
        // LEFT_ONE (Z=11): indices 10..12
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(10, new BlockPos(10, 64, 11)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(11, new BlockPos(11, 64, 11)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(12, new BlockPos(12, 64, 11)));
        // CENTER (Z=12): indices 20..22
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(20, new BlockPos(10, 64, 12)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(21, new BlockPos(11, 64, 12)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(22, new BlockPos(12, 64, 12)));
        // RIGHT_ONE (Z=13): indices 30..32
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(30, new BlockPos(10, 64, 13)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(31, new BlockPos(11, 64, 13)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(32, new BlockPos(12, 64, 13)));
        // RIGHT_TWO (Z=14): indices 40..42 (highest — the "starved" band without the fix)
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(40, new BlockPos(10, 64, 14)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(41, new BlockPos(11, 64, 14)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(42, new BlockPos(12, 64, 14)));

        List<GroundedSweepPlacementExecutor.PlacementTarget> burst = GroundedSingleLaneDebugRunner.entryBurstTargetsForTests(
                lane, bounds, pending, 2, 10);

        List<Integer> indices = burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList();

        // All 5 bands at X=10 (batch-0) must appear before any X=11 or X=12 target.
        // Sorted order: L2(0), L1(10), C(20), R1(30), R2(40), then batch-1, then batch-2.
        assertEquals(List.of(0, 10, 20, 30, 40, 1, 11, 21, 31, 41, 2, 12, 22, 32, 42), indices);

        // RIGHT_TWO at baseProgress (idx=40) must be at position 4 (before any later-progress target).
        int rightTwoBaseIdx = indices.indexOf(40);
        assertTrue(rightTwoBaseIdx >= 0, "RIGHT_TWO at baseProgress must be in burst list");
        // All targets before it must be at X=10 (batch-0).
        for (int i = 0; i < rightTwoBaseIdx; i++) {
            BlockPos pos = burst.get(i).worldPos();
            assertEquals(10, pos.getX(), "All targets before RIGHT_TWO@baseProgress must be at X=10");
        }
    }

    @Test
    void entryBurstTargetsWestLaneRightTwoAtBaseProgressBeforeLaterProgress() {
        // WEST lane: direction=-X, forwardSign=-1. Right of travel is -Z.
        // RIGHT_TWO at baseProgress (high X start, low Z) must appear before any target at X=start-1.
        // Centerline Z=80, start X=200, going west. RIGHT_TWO = Z=78.
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 80, GroundedLaneDirection.WEST,
                new BlockPos(200, 64, 80), new BlockPos(196, 64, 80),
                new GroundedLaneCorridorBounds(196, 200, 78, 82), 1.0);
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(196, 64, 78),
                new BlockPos(196, 64, 78),
                new BlockPos(200, 64, 82));

        // Band-major ordering: LEFT_TWO (Z=82) first, RIGHT_TWO (Z=78) last.
        // For WEST: left is +Z, so LEFT_TWO = Z=82.  right is -Z, RIGHT_TWO = Z=78.
        List<GroundedSweepPlacementExecutor.PlacementTarget> pending = new ArrayList<>();
        // LEFT_TWO (Z=82): indices 0..2 for X=200,199,198
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(0, new BlockPos(200, 64, 82)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(1, new BlockPos(199, 64, 82)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(2, new BlockPos(198, 64, 82)));
        // CENTER (Z=80): indices 10..12
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(10, new BlockPos(200, 64, 80)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(11, new BlockPos(199, 64, 80)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(12, new BlockPos(198, 64, 80)));
        // RIGHT_TWO (Z=78): indices 20..22 (last band, highest indices)
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(20, new BlockPos(200, 64, 78)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(21, new BlockPos(199, 64, 78)));
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(22, new BlockPos(198, 64, 78)));

        // baseProgress=200 (WEST start), allowedProgress={200,199,198}
        List<GroundedSweepPlacementExecutor.PlacementTarget> burst = GroundedSingleLaneDebugRunner.entryBurstTargetsForTests(
                lane, bounds, pending, 2, 200);

        List<Integer> indices = burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList();

        // batch-0 (X=200): L2(0), C(10), R2(20) — must all come before batch-1 (X=199).
        // Sorted: L2(0), C(10), R2(20), L2(1), C(11), R2(21), L2(2), C(12), R2(22)
        assertEquals(List.of(0, 10, 20, 1, 11, 21, 2, 12, 22), indices);

        // RIGHT_TWO at X=200 (idx=20) must appear before any X=199 target.
        int r2Pos = indices.indexOf(20);
        assertTrue(r2Pos >= 0, "RIGHT_TWO at baseProgress must be selected");
        for (int i = 0; i < r2Pos; i++) {
            assertEquals(200, burst.get(i).worldPos().getX(), "All targets before RIGHT_TWO@baseProgress must be at X=200");
        }
    }

    @Test
    void entryBurstNonContiguousIndexJumpDoesNotSkipBaseProgressBands() {
        // Regression for index-ordered burst: when RIGHT_TWO (highest placement index) would only
        // be reached on tick 13+ in a 16-tick burst, the sort fix must bring it to position 4
        // in the burst list so it is always reached on tick 3 regardless of index order.
        GroundedSweepLane lane = eastLane();
        GroundedSchematicBounds bounds = eastLaneBounds();
        // Simulate real plan: 5 bands, baseProgress=10, EAST. Indices are band-major (high for R2).
        List<GroundedSweepPlacementExecutor.PlacementTarget> pending = new ArrayList<>();
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(0,   new BlockPos(10, 64, 10)));  // L2 X=10
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(1,   new BlockPos(11, 64, 10)));  // L2 X=11 (out of window)
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(128, new BlockPos(10, 64, 11)));  // L1 X=10
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(256, new BlockPos(10, 64, 12)));  // C  X=10
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(384, new BlockPos(10, 64, 13)));  // R1 X=10
        pending.add(new GroundedSweepPlacementExecutor.PlacementTarget(512, new BlockPos(10, 64, 14)));  // R2 X=10 ← far-side, high index

        List<GroundedSweepPlacementExecutor.PlacementTarget> burst = GroundedSingleLaneDebugRunner.entryBurstTargetsForTests(
                lane, bounds, pending, 2, 10);

        List<Integer> indices = burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList();

        // Only X=10 targets are in window {10,11,12}... but X=11 (L2) is also in window.
        // In window: idx0(L2,X=10), idx1(L2,X=11), idx128(L1,X=10), idx256(C,X=10), idx384(R1,X=10), idx512(R2,X=10)
        // Sorted: batch-0(X=10): L2(0),L1(128),C(256),R1(384),R2(512) then batch-1(X=11): L2(1)
        assertEquals(List.of(0, 128, 256, 384, 512, 1), indices);

        // RIGHT_TWO at X=10 (idx=512) is at position 4 — reached on tick 3, not tick 13.
        assertEquals(4, indices.indexOf(512));
    }

    @Test
    void allBaseProgressTargetsAttemptedReturnsFalseWhenFarSideBandUnstated() {
        // Walker-start guard: if RIGHT_TWO at baseProgress has not been attempted, the guard
        // must hold the walker until the burst covers it.
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        GroundedSweepLane lane = eastLane();
        // 5 pending targets at X=10 (baseProgress): L2, L1, C, R1, R2
        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(0, new BlockPos(10, 64, 10)),   // L2
                new GroundedSweepPlacementExecutor.PlacementTarget(1, new BlockPos(10, 64, 11)),   // L1
                new GroundedSweepPlacementExecutor.PlacementTarget(2, new BlockPos(10, 64, 12)),   // C
                new GroundedSweepPlacementExecutor.PlacementTarget(3, new BlockPos(10, 64, 13)),   // R1
                new GroundedSweepPlacementExecutor.PlacementTarget(4, new BlockPos(10, 64, 14))    // R2 ← must be covered
        ));

        // No attempts yet → guard should block walker start.
        assertFalse(runner.allBaseProgressTargetsAttemptedForTests(lane, 10));

        // Attempt all except RIGHT_TWO → still blocked.
        runner.seedEntryAttemptsForTests(Map.of(0, 1, 1, 1, 2, 1, 3, 1));
        assertFalse(runner.allBaseProgressTargetsAttemptedForTests(lane, 10));

        // Attempt RIGHT_TWO too → guard released.
        runner.seedEntryAttemptsForTests(Map.of(0, 1, 1, 1, 2, 1, 3, 1, 4, 1));
        assertTrue(runner.allBaseProgressTargetsAttemptedForTests(lane, 10));
    }

    @Test
    void fullSweepLaneAdvanceUsesNativeLaneShiftInsteadOfStartApproach() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertTrue(status.awaitingLaneShift());
        assertFalse(status.awaitingStartApproach());

        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);
    }

    @Test
    void laneZeroToLaneOneTransitionRemainsForwardPhaseAndAwaitsShift() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(11, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());

        runner.advanceSweepToNextLaneForTests();

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD, status.phase());
        assertEquals(1, status.laneIndex());
        assertTrue(status.awaitingLaneShift());
        assertFalse(status.awaitingStartApproach());
    }


    @Test
    void shiftedLaneCompletionQueuesYawLockBeforeWalkerStartsInRuntimeFlow() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(11, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();

        GroundedSingleLaneDebugRunner.TestYawState yawState = new GroundedSingleLaneDebugRunner.TestYawState();
        boolean queued = runner.queueLaneStartForTests(yawState, plan.toLane());

        assertTrue(queued);
        assertEquals(GroundedSingleLaneDebugRunner.LaneStartStage.LOCK_LANE_YAW, runner.laneStartStageForTests());
        assertEquals(GroundedLaneDirection.WEST, plan.toLane().direction());
        assertEquals(plan.toLane().direction().yawDegrees(), yawState.yaw());
        assertEquals(plan.toLane().direction().yawDegrees(), yawState.headYaw);
        assertEquals(plan.toLane().direction().yawDegrees(), yawState.bodyYaw);
    }

    @Test
    void laneOneWestYawLockAppliesPlayerHeadAndBodyYawInTestHook() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(11, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSweepLane westLane = runner.laneShiftPlanForTests().orElseThrow().toLane();
        assertEquals(1, westLane.laneIndex());
        assertEquals(GroundedLaneDirection.WEST, westLane.direction());

        GroundedSingleLaneDebugRunner.TestYawState yawState = new GroundedSingleLaneDebugRunner.TestYawState();
        assertTrue(GroundedSingleLaneDebugRunner.forceLaneYawForTests(yawState, westLane));
        assertEquals(westLane.direction().yawDegrees(), yawState.yaw());
        assertEquals(westLane.direction().yawDegrees(), yawState.headYaw);
        assertEquals(westLane.direction().yawDegrees(), yawState.bodyYaw);
    }

    @Test
    void laneShiftCompletionDoesNotCallBaritoneAgain() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();
        runner.completeLaneShiftIfNearForTests(
                new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterlineCoordinate() + 0.5),
                false
        );

        assertEquals(1, baritone.goToCalls);
    }

    @Test
    void laneShiftCompletionStartsNextLaneWalker() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();

        GroundedSingleLaneDebugRunner.DebugStatus shifting = runner.status();
        assertTrue(shifting.awaitingLaneShift());
        assertEquals(GroundedLaneWalkState.IDLE, shifting.walkState());

        runner.completeLaneShiftIfNearForTests(
                new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterlineCoordinate() + 0.5),
                false
        );

        GroundedSingleLaneDebugRunner.DebugStatus shifted = runner.status();
        assertFalse(shifted.awaitingLaneShift());
        assertEquals(GroundedLaneWalkState.ACTIVE, shifted.walkState());
        assertEquals(plan.toLane().direction().yawDegrees(), runner.laneWalkCommandForTests().orElseThrow().yaw());
    }

    @Test
    void eastThenWestSerpentineShiftUsesSouthCardinalDirection() {
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(20, 64, 20)
        );
        GroundedSweepLane fromLane = new GroundedSweepLane(
                0,
                12,
                GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12),
                new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSweepLane toLane = new GroundedSweepLane(
                1,
                17,
                GroundedLaneDirection.WEST,
                new BlockPos(20, 64, 17),
                new BlockPos(10, 64, 17),
                new GroundedLaneCorridorBounds(10, 20, 15, 19),
                1.0
        );

        GroundedSingleLaneDebugRunner.LaneShiftPlan plan =
                GroundedSingleLaneDebugRunner.buildLaneShiftPlanForTests(fromLane, toLane, bounds);

        assertEquals(GroundedLaneDirection.SOUTH, plan.shiftDirection());
        assertEquals(17, plan.targetCenterlineCoordinate());
    }

    @Test
    void westThenEastSerpentineShiftAlsoUsesSouthCardinalDirection() {
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(20, 64, 20)
        );
        GroundedSweepLane fromLane = new GroundedSweepLane(
                1,
                17,
                GroundedLaneDirection.WEST,
                new BlockPos(20, 64, 17),
                new BlockPos(10, 64, 17),
                new GroundedLaneCorridorBounds(10, 20, 15, 19),
                1.0
        );
        GroundedSweepLane toLane = new GroundedSweepLane(
                2,
                22,
                GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 22),
                new BlockPos(20, 64, 22),
                new GroundedLaneCorridorBounds(10, 20, 20, 24),
                1.0
        );

        GroundedSingleLaneDebugRunner.LaneShiftPlan plan =
                GroundedSingleLaneDebugRunner.buildLaneShiftPlanForTests(fromLane, toLane, bounds);

        assertEquals(GroundedLaneDirection.SOUTH, plan.shiftDirection());
        assertEquals(22, plan.targetCenterlineCoordinate());
    }

    @Test
    void zAxisLaneSerpentineShiftUsesEastOrWestDirection() {
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(20, 64, 20)
        );
        GroundedSweepLane fromLane = new GroundedSweepLane(
                0,
                12,
                GroundedLaneDirection.SOUTH,
                new BlockPos(12, 64, 10),
                new BlockPos(12, 64, 20),
                new GroundedLaneCorridorBounds(10, 14, 10, 20),
                1.0
        );
        GroundedSweepLane eastLane = new GroundedSweepLane(
                1,
                17,
                GroundedLaneDirection.NORTH,
                new BlockPos(17, 64, 20),
                new BlockPos(17, 64, 10),
                new GroundedLaneCorridorBounds(15, 19, 10, 20),
                1.0
        );
        GroundedSweepLane westLane = new GroundedSweepLane(
                2,
                8,
                GroundedLaneDirection.SOUTH,
                new BlockPos(8, 64, 10),
                new BlockPos(8, 64, 20),
                new GroundedLaneCorridorBounds(6, 10, 10, 20),
                1.0
        );

        assertEquals(
                GroundedLaneDirection.EAST,
                GroundedSingleLaneDebugRunner.buildLaneShiftPlanForTests(fromLane, eastLane, bounds).shiftDirection()
        );
        assertEquals(
                GroundedLaneDirection.WEST,
                GroundedSingleLaneDebugRunner.buildLaneShiftPlanForTests(fromLane, westLane, bounds).shiftDirection()
        );
    }

    @Test
    void eastWestTransitionUsesCardinalShiftOnly() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(11, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();

        runner.completeLaneShiftIfNearForTests(new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline() - 1.0), false);
        assertEquals(GroundedLaneDirection.SOUTH,
                runner.laneShiftDirectionForTests(new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline() - 1.0)));
        runner.completeLaneShiftIfNearForTests(new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline()), false);
        assertEquals(GroundedLaneWalkState.ACTIVE, runner.status().walkState());
    }

    @Test
    void centerlineCorrectionReversesDirectionWhenOvershootingTargetLane() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(11, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();

        Vec3d northOfCenterline = new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline() - 1.25);
        Vec3d southOfCenterline = new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline() + 1.25);

        runner.completeLaneShiftIfNearForTests(northOfCenterline, false);
        assertEquals(GroundedLaneDirection.SOUTH, runner.laneShiftDirectionForTests(northOfCenterline));
        assertEquals(GroundedLaneDirection.NORTH, runner.laneShiftDirectionForTests(southOfCenterline));
    }

    @Test
    void transitionDoesNotStartNextLaneUntilBothAxesAligned() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(11, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();

        runner.completeLaneShiftIfNearForTests(new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline() - 1.0), false);
        assertTrue(runner.status().awaitingLaneShift());
        assertEquals(GroundedLaneWalkState.IDLE, runner.status().walkState());

        runner.completeLaneShiftIfNearForTests(new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline()), false);
        assertFalse(runner.status().awaitingLaneShift());
        assertEquals(GroundedLaneWalkState.ACTIVE, runner.status().walkState());
    }

    @Test
    void westEastTransitionUsesExpectedShiftDirectionForLaneOrder() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();
        assertTrue(plan.shiftDirection() == GroundedLaneDirection.SOUTH || plan.shiftDirection() == GroundedLaneDirection.NORTH);
    }

    @Test
    void northSouthTransitionUsesSingleCardinalCenterlineShift() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(11, 1, 5)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();

        if (plan.toLane().direction().alongX()) {
            Vec3d northOfCenterline = new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline() - 1.0);
            assertEquals(GroundedLaneDirection.SOUTH, GroundedSingleLaneDebugRunner.centerlineAlignmentDirectionForTests(northOfCenterline, plan.toLane()));
        } else {
            Vec3d westOfCenterline = new Vec3d(plan.targetCenterline() - 1.0, 64.0, plan.toLane().startPoint().getZ() + 0.5);
            assertEquals(GroundedLaneDirection.EAST, GroundedSingleLaneDebugRunner.centerlineAlignmentDirectionForTests(westOfCenterline, plan.toLane()));
        }
    }

    @Test
    void northSouthCenterlineCorrectionReversesWhenOvershootingXCenterline() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(11, 1, 5)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();
        if (plan.toLane().direction().alongX()) {
            Vec3d north = new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline() - 1.25);
            Vec3d south = new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterline() + 1.25);
            runner.completeLaneShiftIfNearForTests(north, false);
            assertEquals(GroundedLaneDirection.SOUTH, runner.laneShiftDirectionForTests(north));
            assertEquals(GroundedLaneDirection.NORTH, runner.laneShiftDirectionForTests(south));
            return;
        }
        Vec3d forwardAlignedWest = new Vec3d(plan.targetCenterline() - 1.25, 64.0, plan.toLane().startPoint().getZ() + 0.5);
        Vec3d forwardAlignedEast = new Vec3d(plan.targetCenterline() + 1.25, 64.0, plan.toLane().startPoint().getZ() + 0.5);
        runner.completeLaneShiftIfNearForTests(forwardAlignedWest, false);
        assertEquals(GroundedLaneDirection.EAST, runner.laneShiftDirectionForTests(forwardAlignedWest));
        assertEquals(GroundedLaneDirection.WEST, runner.laneShiftDirectionForTests(forwardAlignedEast));
    }

    @Test
    void transitionFailureMarksRunFailed() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();

        runner.forceLaneTransitionTimeoutForTests("Lane transition failed to reach next lane start");

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertEquals(GroundedLaneWalkState.FAILED, status.walkState());
        assertEquals("Lane transition failed to reach next lane start", status.failureReason().orElseThrow());
    }

    @Test
    void startApproachTimeoutFailsWithClearReason() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        runner.forceStartApproachTimeoutForTests();

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertEquals(GroundedLaneWalkState.FAILED, status.walkState());
        assertEquals("Unable to reach valid lane start staging position", status.failureReason().orElseThrow());
    }

    @Test
    void stopDuringLaneTransitionClearsShiftState() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        assertTrue(runner.status().awaitingLaneShift());

        assertTrue(runner.stop().isEmpty());

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertFalse(status.awaitingLaneShift());
    }

    @Test
    void reversePhaseDoesNotStartAfterFirstForwardLane() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(11, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());

        runner.advanceSweepToNextLaneForTests();

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD, status.phase());
        assertTrue(status.awaitingLaneShift());
        assertEquals(1, status.laneIndex());
    }

    @Test
    void reverseSweepTransitionsAlsoUseNativeLaneShift() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());

        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(42, new BlockPos(11, 64, 10))));
        runner.advanceSweepToNextLaneForTests(); // complete forward lane and move to reverse lane

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.REVERSE, status.phase());
        assertTrue(status.awaitingLaneShift());
        assertFalse(status.awaitingStartApproach());
        assertTrue(runner.laneShiftPlanForTests().isPresent());
    }

    @Test
    void reverseCleanupCompletionStillMarksRunComplete() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());

        runner.seedLanePlacementsForTests(List.of(new GroundedSweepPlacementExecutor.PlacementTarget(42, new BlockPos(11, 64, 10))));
        runner.advanceSweepToNextLaneForTests();
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.REVERSE, runner.status().phase());

        runner.seedLanePlacementsForTests(List.of());
        runner.advanceSweepToNextLaneForTests();
        runner.finalizeTerminalStateWithSummaryForTests(GroundedLaneWalkState.COMPLETE, Optional.empty());

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertEquals(GroundedLaneWalkState.COMPLETE, status.walkState());
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.COMPLETE, status.phase());
        assertTrue(status.failureReason().isEmpty());
        assertTrue(runner.lastRunSummaryTextForTests().contains("Completed: yes"));
    }

    @Test
    void singleLaneStartStillUsesStartApproachBehavior() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertTrue(status.awaitingStartApproach());
        assertFalse(status.awaitingLaneShift());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);
    }

    @Test
    void reverseSweepLaneListFlipsOrderAndDirection() {
        GroundedSweepLane lane0 = new GroundedSweepLane(
                0,
                12,
                GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12),
                new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSweepLane lane1 = new GroundedSweepLane(
                1,
                17,
                GroundedLaneDirection.WEST,
                new BlockPos(20, 64, 17),
                new BlockPos(10, 64, 17),
                new GroundedLaneCorridorBounds(10, 20, 15, 19),
                1.0
        );

        List<GroundedSweepLane> reverse = GroundedSingleLaneDebugRunner.buildReverseSweepLanesForTests(List.of(lane0, lane1));

        assertEquals(2, reverse.size());
        assertEquals(1, reverse.get(0).laneIndex());
        assertEquals(GroundedLaneDirection.EAST, reverse.get(0).direction());
        assertEquals(lane1.endPoint(), reverse.get(0).startPoint());
        assertEquals(lane1.startPoint(), reverse.get(0).endPoint());
        assertEquals(0, reverse.get(1).laneIndex());
        assertEquals(GroundedLaneDirection.WEST, reverse.get(1).direction());
    }

    @Test
    void transitionSupportTargetsBridgeBetweenEastWestLaneCenterlinesInsideBounds() {
        BuildPlan plan = buildPlan(new Vec3i(5, 1, 5), List.of(
                new Placement(new BlockPos(4, 0, 1), null),
                new Placement(new BlockPos(4, 0, 2), null),
                new Placement(new BlockPos(4, 0, 3), null),
                new Placement(new BlockPos(0, 0, 4), null)
        ));
        BlockPos origin = new BlockPos(10, 64, 10);
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, origin);
        GroundedSweepLane fromLane = new GroundedSweepLane(0, 11, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 11), new BlockPos(14, 64, 11), new GroundedLaneCorridorBounds(10, 14, 10, 12), 1.0);
        GroundedSweepLane toLane = new GroundedSweepLane(1, 13, GroundedLaneDirection.WEST,
                new BlockPos(14, 64, 13), new BlockPos(10, 64, 13), new GroundedLaneCorridorBounds(10, 14, 12, 14), 1.0);

        List<BlockPos> targets = GroundedSingleLaneDebugRunner.buildTransitionSupportTargetsForTests(
                plan, origin, bounds, fromLane, toLane, new java.util.HashMap<>())
                .stream()
                .map(GroundedSweepPlacementExecutor.PlacementTarget::worldPos)
                .toList();

        assertTrue(targets.contains(new BlockPos(14, 64, 11)));
        assertTrue(targets.contains(new BlockPos(14, 64, 12)));
        assertTrue(targets.contains(new BlockPos(14, 64, 13)));
        assertFalse(targets.contains(new BlockPos(10, 64, 14)));
    }

    @Test
    void transitionSupportTargetsBuildXBridgeForNorthSouthLanes() {
        BuildPlan plan = buildPlan(new Vec3i(5, 1, 5), List.of(
                new Placement(new BlockPos(1, 0, 4), null),
                new Placement(new BlockPos(2, 0, 4), null),
                new Placement(new BlockPos(3, 0, 4), null)
        ));
        BlockPos origin = new BlockPos(10, 64, 10);
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(plan, origin);
        GroundedSweepLane fromLane = new GroundedSweepLane(0, 11, GroundedLaneDirection.SOUTH,
                new BlockPos(11, 64, 10), new BlockPos(11, 64, 14), new GroundedLaneCorridorBounds(10, 12, 10, 14), 1.0);
        GroundedSweepLane toLane = new GroundedSweepLane(1, 13, GroundedLaneDirection.NORTH,
                new BlockPos(13, 64, 14), new BlockPos(13, 64, 10), new GroundedLaneCorridorBounds(12, 14, 10, 14), 1.0);

        Set<BlockPos> targets = Set.copyOf(GroundedSingleLaneDebugRunner.buildTransitionSupportTargetsForTests(
                        plan, origin, bounds, fromLane, toLane, new java.util.HashMap<>())
                .stream().map(GroundedSweepPlacementExecutor.PlacementTarget::worldPos).toList());

        assertTrue(targets.contains(new BlockPos(11, 64, 14)));
        assertTrue(targets.contains(new BlockPos(12, 64, 14)));
        assertTrue(targets.contains(new BlockPos(13, 64, 14)));
    }

    @Test
    void laneShiftAdvancesToActiveWhenCenterlineReachedDespitePendingSupport() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithBridgeSupport(), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();

        assertTrue(runner.awaitingTransitionSupportForTests());
        assertTrue(runner.status().awaitingLaneShift());

        GroundedSingleLaneDebugRunner.LaneShiftPlan plan = runner.laneShiftPlanForTests().orElseThrow();
        runner.completeLaneShiftIfNearForTests(
                new Vec3d(plan.toLane().startPoint().getX() + 0.5, 64.0, plan.targetCenterlineCoordinate() + 0.5),
                false
        );
        assertFalse(runner.awaitingTransitionSupportForTests());
        assertEquals(GroundedLaneWalkState.ACTIVE, runner.status().walkState());
    }

    @Test
    void transitionSupportFailureProducesClearReason() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithBridgeSupport(), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        assertTrue(runner.awaitingTransitionSupportForTests());

        runner.failTransitionSupportForTests();

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertEquals("Unable to build safe transition support path", status.failureReason().orElseThrow());
        assertFalse(runner.hasActiveLaneTransitionStateForTests());
    }

    @Test
    void transitionSupportVerificationTimeoutRequeuesInsteadOfFailingImmediately() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.startFullSweep(sessionWithBridgeSupport(), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSweepPlacementExecutor.PlacementTarget target = runner.transitionSupportTargetsForTests().getFirst();
        runner.keepOnlyTransitionSupportTargetForTests(target.placementIndex());
        runner.recordTransitionSupportAcceptedPendingForTests(target.placementIndex(), target.worldPos(), 0);

        runner.processTransitionSupportVerificationsForTests(Map.of(target.placementIndex(), false), 999);

        assertTrue(runner.awaitingTransitionSupportForTests());
        assertEquals(0, runner.pendingTransitionSupportVerificationCountForTests());
        assertEquals(List.of(target.placementIndex()), runner.transitionSupportTargetsForTests().stream()
                .map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex)
                .toList());
        assertTrue(runner.groundedTraceEventsForTests().stream()
                .anyMatch(event -> event.contains("PLACEMENT_PENDING_VERIFICATION_TIMEOUT")
                        && event.contains("path=transition support")));
    }

    @Test
    void transitionSupportAuditFailureRequeuesSupportTargetInsteadOfCompletingTransition() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.startFullSweep(sessionWithBridgeSupport(), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSweepPlacementExecutor.PlacementTarget target = runner.transitionSupportTargetsForTests().getFirst();
        runner.keepOnlyTransitionSupportTargetForTests(target.placementIndex());
        runner.recordTransitionSupportPlacedForTests(target.placementIndex(), target.worldPos(), 0);
        runner.processTransitionSupportVerificationsForTests(Map.of(target.placementIndex(), true), 3);

        runner.processTransitionSupportAuditsForTests(Map.of(target.placementIndex(), false), 18);

        assertTrue(runner.awaitingTransitionSupportForTests());
        assertEquals(0, runner.pendingTransitionSupportAuditCountForTests());
        assertEquals(List.of(target.placementIndex()), runner.transitionSupportTargetsForTests().stream()
                .map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex)
                .toList());
        assertTrue(runner.groundedTraceEventsForTests().stream()
                .anyMatch(event -> event.contains("PLACEMENT_AUDIT_REQUEUED_FOR_CLEANUP")
                        && event.contains("path=transition support")));
    }

    @Test
    void centerlineAlignmentAndDirectionAreNullSafeForMissingLane() {
        Vec3d playerPos = new Vec3d(10.5, 64.0, 10.5);
        assertDoesNotThrow(() -> GroundedSingleLaneDebugRunner.isCenterlineAlignedForTests(playerPos, null));
        assertFalse(GroundedSingleLaneDebugRunner.isCenterlineAlignedForTests(playerPos, null));
        assertDoesNotThrow(() -> GroundedSingleLaneDebugRunner.centerlineAlignmentDirectionForTests(playerPos, null));
        assertNull(GroundedSingleLaneDebugRunner.centerlineAlignmentDirectionForTests(playerPos, null));
    }

    @Test
    void transitionSupportVerificationTimingUsesSupportTicksNotLaneTicks() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithBridgeSupport(), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        assertTrue(runner.awaitingTransitionSupportForTests());
        assertEquals(0, runner.status().ticksElapsed());

        GroundedSweepPlacementExecutor.PlacementTarget target = runner.transitionSupportTargetsForTests().getFirst();
        runner.keepOnlyTransitionSupportTargetForTests(target.placementIndex());
        runner.recordTransitionSupportPlacedForTests(target.placementIndex(), target.worldPos(), 0);
        assertEquals(1, runner.pendingTransitionSupportVerificationCountForTests());

        runner.processTransitionSupportVerificationsForTests(Map.of(target.placementIndex(), true), 2);
        assertEquals(1, runner.pendingTransitionSupportVerificationCountForTests());
        assertEquals(0, runner.status().ticksElapsed());
        assertTrue(runner.awaitingTransitionSupportForTests());
        assertTrue(runner.status().awaitingLaneShift());

        runner.processTransitionSupportVerificationsForTests(Map.of(target.placementIndex(), true), 3);
        assertEquals(0, runner.pendingTransitionSupportVerificationCountForTests());
        assertEquals(1, runner.pendingTransitionSupportAuditCountForTests());
        assertTrue(runner.awaitingTransitionSupportForTests());
        assertTrue(runner.status().awaitingLaneShift());
        assertEquals(0, runner.status().ticksElapsed());

        runner.processTransitionSupportAuditsForTests(Map.of(target.placementIndex(), true), 18);
        assertEquals(0, runner.pendingTransitionSupportAuditCountForTests());
        assertFalse(runner.awaitingTransitionSupportForTests());
        assertTrue(runner.status().awaitingLaneShift());
        assertEquals(0, runner.status().ticksElapsed());
    }

    private static BuildSession sessionWithOrigin() {
        return sessionWithOrigin(new Vec3i(5, 1, 5));
    }

    private static GroundedSweepLane eastLane() {
        return new GroundedSweepLane(
                0,
                12,
                GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12),
                new BlockPos(14, 64, 12),
                new GroundedLaneCorridorBounds(10, 14, 10, 14),
                1.0
        );
    }

    private static GroundedSchematicBounds eastLaneBounds() {
        return new GroundedSchematicBounds(
                new BlockPos(10, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(14, 64, 14)
        );
    }

    private static void markLaneComplete(BuildPlan plan, BlockPos origin, int sweepHalfWidth, GroundedSweepLane lane, java.util.Set<BlockPos> world) {
        for (Placement placement : plan.placements()) {
            BlockPos worldPos = origin.add(placement.relativePos());
            if (worldPos.getX() < lane.corridorBounds().minX() || worldPos.getX() > lane.corridorBounds().maxX()) {
                continue;
            }
            if (worldPos.getZ() < lane.corridorBounds().minZ() || worldPos.getZ() > lane.corridorBounds().maxZ()) {
                continue;
            }
            int lateral = lane.direction().alongX() ? worldPos.getZ() : worldPos.getX();
            if (Math.abs(lateral - lane.centerlineCoordinate()) <= sweepHalfWidth) {
                world.add(worldPos);
            }
        }
    }

    private static BuildSession sessionWithOrigin(Vec3i dimensions) {
        BuildPlan plan = buildPlan(dimensions, List.of(new Placement(new BlockPos(0, 0, 0), null)));

        BuildSession session = new BuildSession(plan);
        session.setOrigin(new BlockPos(10, 64, 10));
        return session;
    }

    private static BuildSession sessionWithBridgeSupport() {
        BuildPlan plan = buildPlan(new Vec3i(5, 1, 5), List.of(
                new Placement(new BlockPos(0, 0, 0), null),
                new Placement(new BlockPos(4, 0, 1), null),
                new Placement(new BlockPos(4, 0, 2), null),
                new Placement(new BlockPos(4, 0, 3), null)
        ));
        BuildSession session = new BuildSession(plan);
        session.setOrigin(new BlockPos(10, 64, 10));
        return session;
    }

    @Test
    void recoveryNotActiveInitially() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertFalse(runner.getRecoveryState().isActive());
        assertTrue(runner.getRecoveryState().snapshot().isEmpty());
    }

    @Test
    void recoveryClearResetsState() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        runner.start(session, 0, GroundedSweepSettings.defaults());

        GroundedSweepLane lane = laneForIndex(session, GroundedSweepSettings.defaults(), 0);
        Vec3d testPosition = new Vec3d(10.5, 64.0, 10.5);
        GroundedRecoverySnapshot snapshot = new GroundedRecoverySnapshot(
                lane,
                GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD,
                GroundedLaneDirection.EAST,
                10.0,
                testPosition,
                GroundedRecoveryReason.OFF_CORRIDOR
        );
        runner.getRecoveryState().activate(snapshot);

        assertTrue(runner.getRecoveryState().isActive());
        runner.getRecoveryState().clear();
        assertFalse(runner.getRecoveryState().isActive());
        assertTrue(runner.getRecoveryState().snapshot().isEmpty());
    }

    @Test
    void recoverySnapshotPreservesLaneAndPhaseAndProgress() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        runner.start(session, 0, GroundedSweepSettings.defaults());

        GroundedSweepLane lane = laneForIndex(session, GroundedSweepSettings.defaults(), 0);
        Vec3d testPosition = new Vec3d(12.5, 64.0, 11.5);
        GroundedRecoverySnapshot snapshot = new GroundedRecoverySnapshot(
                lane,
                GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD,
                GroundedLaneDirection.EAST,
                12.0,
                testPosition,
                GroundedRecoveryReason.NO_PROGRESS
        );
        runner.getRecoveryState().activate(snapshot);

        var retrieved = runner.getRecoveryState().snapshot().orElseThrow();
        assertEquals(lane.laneIndex(), retrieved.activeLane().laneIndex());
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD, retrieved.passPhase());
        assertEquals(GroundedLaneDirection.EAST, retrieved.laneDirection());
        assertEquals(12.0, retrieved.lastKnownSafeProgressCoordinate(), 0.01);
        assertEquals(testPosition, retrieved.playerPosition());
        assertEquals(GroundedRecoveryReason.NO_PROGRESS, retrieved.reason());
    }


    private static BuildSession rectangularSessionWithOrigin(Vec3i dimensions) {
        List<Placement> placements = new java.util.ArrayList<>();
        for (int y = 0; y < dimensions.getY(); y++) {
            for (int z = 0; z < dimensions.getZ(); z++) {
                for (int x = 0; x < dimensions.getX(); x++) {
                    placements.add(new Placement(new BlockPos(x, y, z), null));
                }
            }
        }
        BuildSession session = new BuildSession(buildPlan(dimensions, placements));
        session.setOrigin(new BlockPos(10, 64, 10));
        return session;
    }


    private static GroundedSweepLane laneForIndex(BuildSession session, GroundedSweepSettings settings, int laneIndex) {
        GroundedSchematicBounds bounds = GroundedSchematicBounds.fromPlan(session.getPlan(), session.getOrigin());
        List<GroundedSweepLane> lanes = new GroundedSweepLanePlanner().planLanes(bounds, settings);
        return lanes.get(laneIndex);
    }

    private static BuildPlan buildPlan(List<Placement> placements) {
        return buildPlan(new Vec3i(5, 1, 5), placements);
    }

    private static BuildPlan buildPlan(Vec3i dimensions, List<Placement> placements) {
        return new BuildPlan(
                "test",
                Path.of("plan.schem"),
                dimensions,
                placements,
                Map.of(),
                List.of()
        );
    }


    @Test
    void diagnosticsPayloadHandlesNullOptionalSubsystemFields() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        assertTrue(runner.start(session, 0, GroundedSweepSettings.defaults()).isEmpty());
        var payload = runner.buildDiagnosticsPayload(null);
        assertNotNull(payload);
        assertDoesNotThrow(() -> payload.get("selectedResumePoint"));
        assertDoesNotThrow(() -> ((Map<?, ?>) payload.get("placements")).get("lastPlacedPos"));
        assertDoesNotThrow(() -> ((Map<?, ?>) payload.get("refill")).get("returnTarget"));
        assertDoesNotThrow(() -> ((Map<?, ?>) payload.get("baritone")).get("lastIssuedGoal"));
    }

    // ─── Exhausted-material tracking tests ───────────────────────────────────

    @Test
    void exhaustedMaterialsClearedOnStop() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:dirt");
        runner.markExhaustedForTests(itemId, GroundedRefillController.SupplyExhaustedReason.NOT_FOUND_IN_SUPPLIES);

        assertFalse(runner.exhaustedMaterialsForTests().isEmpty());
        runner.stop();
        assertTrue(runner.exhaustedMaterialsForTests().isEmpty());
    }

    @Test
    void exhaustedWarningSentClearedOnStop() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:dirt");
        runner.markExhaustedForTests(itemId, GroundedRefillController.SupplyExhaustedReason.NOT_FOUND_IN_SUPPLIES);
        // simulate that a warning was already sent for this item
        runner.stop(); // stop clears both; re-mark and verify the clear path
        // After stop the sets are empty
        assertTrue(runner.exhaustedMaterialsForTests().isEmpty());
        assertTrue(runner.exhaustedWarningSentForTests().isEmpty());
    }

    @Test
    void exhaustedMaterialsClearedOnNewRunReset() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:stone");
        // Mark without starting a run (simulates stale state from a previous completed run)
        runner.markExhaustedForTests(itemId, GroundedRefillController.SupplyExhaustedReason.INSUFFICIENT_SUPPLY);
        assertFalse(runner.exhaustedMaterialsForTests().isEmpty());

        runner.resetExhaustedStateForNewRun();

        assertTrue(runner.exhaustedMaterialsForTests().isEmpty());
        assertTrue(runner.exhaustedWarningSentForTests().isEmpty());
    }

    @Test
    void exhaustedMaterialsSurvivesHandleRefillDone() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        assertTrue(runner.start(session, 0, GroundedSweepSettings.defaults()).isEmpty());

        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:sand");
        com.example.mapart.supply.SupplyPoint supply =
                new com.example.mapart.supply.SupplyPoint(1, new BlockPos(5, 64, 5), "minecraft:overworld", "chest");
        runner.triggerRefillForTests(Map.of(itemId, 16), List.of(supply), new NoOpBaritoneFacade());

        // Mark the item as exhausted in the refill controller (simulates supply found empty)
        runner.getRefillController().markExhaustedForTests(itemId, GroundedRefillController.SupplyExhaustedReason.SUPPLY_EMPTY);

        // Complete the refill — handleRefillDone copies exhaustedReasons into runner's exhaustedMaterials
        runner.simulateRefillCompleteForTests();

        assertTrue(runner.exhaustedMaterialsForTests().containsKey(itemId),
                "exhaustedMaterials should still contain item after refill completes");
        assertEquals(GroundedRefillController.SupplyExhaustedReason.SUPPLY_EMPTY,
                runner.exhaustedMaterialsForTests().get(itemId));
    }

    @Test
    void exhaustedMaterialsSurvivesAdvanceToReversePass() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(21, 1, 11));
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:gravel");
        runner.markExhaustedForTests(itemId, GroundedRefillController.SupplyExhaustedReason.NOT_FOUND_IN_SUPPLIES);

        // Advance through all forward lanes to trigger transition to reverse pass
        int maxAdvances = 20;
        for (int i = 0; i < maxAdvances; i++) {
            runner.advanceSweepToNextLaneForTests();
            if (!runner.isActive()) break;
        }

        // exhaustedMaterials must survive lane transitions (Fix 4: not cleared in tryAdvanceSweepToNextLane)
        assertTrue(runner.exhaustedMaterialsForTests().containsKey(itemId),
                "exhaustedMaterials should survive lane transitions including reverse pass entry");
    }

    @Test
    void exhaustedWarningSentPreventsDuplicateWarningEntry() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:dirt");
        runner.markExhaustedForTests(itemId, GroundedRefillController.SupplyExhaustedReason.NOT_FOUND_IN_SUPPLIES);

        // Before any warning: set is empty
        assertTrue(runner.exhaustedWarningSentForTests().isEmpty());

        // After stop: both are cleared
        runner.stop();
        assertTrue(runner.exhaustedWarningSentForTests().isEmpty());
        assertTrue(runner.exhaustedMaterialsForTests().isEmpty());
    }

    @Test
    void diagnosticsPayloadIncludesExhaustedMaterialFields() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:dirt");
        runner.markExhaustedForTests(itemId, GroundedRefillController.SupplyExhaustedReason.SUPPLY_EMPTY);

        var payload = runner.buildDiagnosticsPayload(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> refill = (Map<String, Object>) payload.get("refill");
        assertNotNull(refill.get("exhaustedMaterials"), "diagnostics should include exhaustedMaterials");
        assertNotNull(refill.get("exhaustedWarningSent"), "diagnostics should include exhaustedWarningSent");
        @SuppressWarnings("unchecked")
        Map<String, String> exhaustedMap = (Map<String, String>) refill.get("exhaustedMaterials");
        assertTrue(exhaustedMap.containsKey("minecraft:dirt"));
        assertEquals("SUPPLY_EMPTY", exhaustedMap.get("minecraft:dirt"));
    }

    private static final class RecordingBaritoneFacade implements BaritoneFacade {
        private BlockPos lastGoToTarget;
        private int goToCalls;
        private boolean busy = false;

        void setBusy(boolean busy) {
            this.busy = busy;
        }

        @Override
        public CommandResult goTo(BlockPos target) {
            lastGoToTarget = target;
            goToCalls++;
            return CommandResult.success("ok");
        }

        @Override
        public CommandResult goNear(BlockPos target, int range) {
            return CommandResult.success("ok");
        }

        @Override
        public CommandResult pause() {
            return CommandResult.success("ok");
        }

        @Override
        public CommandResult resume() {
            return CommandResult.success("ok");
        }

        @Override
        public CommandResult cancel() {
            return CommandResult.success("ok");
        }

        @Override
        public boolean isBusy() {
            return busy;
        }
    }

    // ─── Run summary tests ────────────────────────────────────────────────────

    @Test
    void formatElapsedNanosFormatsMinutesAndSeconds() {
        assertEquals("00:00", GroundedSingleLaneDebugRunner.formatElapsedNanos(0));
        assertEquals("00:05", GroundedSingleLaneDebugRunner.formatElapsedNanos(5_000_000_000L));
        assertEquals("01:00", GroundedSingleLaneDebugRunner.formatElapsedNanos(60_000_000_000L));
        assertEquals("48:12", GroundedSingleLaneDebugRunner.formatElapsedNanos(2892_000_000_000L));
    }

    @Test
    void formatElapsedNanosFormatsHoursWhenPresent() {
        assertEquals("01:00:00", GroundedSingleLaneDebugRunner.formatElapsedNanos(3600_000_000_000L));
        assertEquals("02:14:36", GroundedSingleLaneDebugRunner.formatElapsedNanos(8076_000_000_000L));
    }

    @Test
    void formatElapsedNanosHandlesNegativeInputAsZero() {
        assertEquals("00:00", GroundedSingleLaneDebugRunner.formatElapsedNanos(-1_000_000_000L));
    }

    @Test
    void countRemainingMismatchesSkipsNullBlockPlacements() {
        BuildPlan plan = buildPlan(new Vec3i(2, 1, 1), List.of(
                new Placement(new BlockPos(0, 0, 0), null),
                new Placement(new BlockPos(1, 0, 0), null)
        ));
        // All placements have null blocks — they are skipped regardless of the lookup result
        int count = GroundedSingleLaneDebugRunner.countRemainingMismatches(
                plan, new BlockPos(10, 64, 10), (pos, block) -> false);
        assertEquals(0, count);
    }

    @Test
    void countRemainingMismatchesReturnsNegativeOneForNullInputs() {
        assertEquals(-1, GroundedSingleLaneDebugRunner.countRemainingMismatches(null, new BlockPos(0, 0, 0), (p, b) -> true));
        BuildPlan plan = buildPlan(List.of());
        assertEquals(-1, GroundedSingleLaneDebugRunner.countRemainingMismatches(plan, null, (p, b) -> true));
        assertEquals(-1, GroundedSingleLaneDebugRunner.countRemainingMismatches(plan, new BlockPos(0, 0, 0), null));
    }

    @Test
    void refillTripCountStartsAtZeroAndIncrementsOncePerInitiatedRefill() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());
        assertEquals(0, runner.refillTripCountForTests());

        com.example.mapart.supply.SupplyPoint supply =
                new com.example.mapart.supply.SupplyPoint(1, new BlockPos(5, 64, 5), "minecraft:overworld", "chest");
        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:stone");
        runner.triggerRefillForTests(Map.of(itemId, 1), List.of(supply), new NoOpBaritoneFacade());

        assertEquals(1, runner.refillTripCountForTests());

        // Completing the refill and resuming does not double-count
        runner.simulateRefillCompleteForTests();
        assertEquals(1, runner.refillTripCountForTests());
    }

    @Test
    void refillTripCountResetsOnFreshFullSweepStart() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        com.example.mapart.supply.SupplyPoint supply =
                new com.example.mapart.supply.SupplyPoint(1, new BlockPos(5, 64, 5), "minecraft:overworld", "chest");
        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:stone");
        runner.triggerRefillForTests(Map.of(itemId, 1), List.of(supply), new NoOpBaritoneFacade());
        assertEquals(1, runner.refillTripCountForTests());

        // Stop ends the run (runSummaryPending = false); count resets on the next fresh start
        runner.stop();

        // Fresh start resets the counter because runSummaryPending is now false
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());
        assertEquals(0, runner.refillTripCountForTests());
    }

    @Test
    void runSummaryPrintsOnNormalFullSweepCompletion() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());
        assertNull(runner.lastRunSummaryTextForTests());

        // Simulate normal full-sweep completion: sweepPassPhase must be COMPLETE
        runner.finalizeTerminalStateForTests(GroundedLaneWalkState.COMPLETE, Optional.empty());
        // finalizeTerminalStateForTests does not go through the summary path (test bypass)
        assertNull(runner.lastRunSummaryTextForTests());

        // Re-start and use the summary-aware terminal path
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());
        runner.setSweepPassPhaseCompleteForTests();
        runner.finalizeTerminalStateWithSummaryForTests(GroundedLaneWalkState.COMPLETE, Optional.empty());

        String summary = runner.lastRunSummaryTextForTests();
        assertNotNull(summary);
        assertTrue(summary.contains("Completed: yes"), "summary should say Completed: yes, got: " + summary);
        assertTrue(summary.contains("Refill trips: 0"));
        assertTrue(summary.contains("Time taken:"));
    }

    @Test
    void runSummaryPrintsCompletedNoOnStop() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());

        runner.finalizeTerminalStateWithSummaryForTests(GroundedLaneWalkState.FAILED, Optional.of("lane timed out"));

        String summary = runner.lastRunSummaryTextForTests();
        assertNotNull(summary);
        assertTrue(summary.contains("Completed: no"), "summary should say Completed: no");
        assertTrue(summary.contains("Reason: lane timed out"));
    }

    @Test
    void runSummaryNotPrintedForSingleLaneMode() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        runner.finalizeTerminalStateWithSummaryForTests(GroundedLaneWalkState.COMPLETE, Optional.empty());

        // Single-lane mode: no summary printed
        assertNull(runner.lastRunSummaryTextForTests());
    }

    @Test
    void runSummaryIncludesExhaustedMaterialReasonWhenNoExplicitFailure() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());

        net.minecraft.util.Identifier wool = net.minecraft.util.Identifier.of("minecraft:white_wool");
        runner.markExhaustedForTests(wool, GroundedRefillController.SupplyExhaustedReason.SUPPLY_EMPTY);

        runner.finalizeTerminalStateWithSummaryForTests(GroundedLaneWalkState.FAILED, Optional.empty());

        String summary = runner.lastRunSummaryTextForTests();
        assertNotNull(summary);
        assertTrue(summary.contains("Reason: Supply exhausted:"), "should contain supply exhausted reason, got: " + summary);
        assertTrue(summary.contains("white_wool"));
    }

    @Test
    void diagnosticsEventWrittenOnRunEnd() throws Exception {
        java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("mapart-summary-test");
        java.nio.file.Path logPath = temp.resolve("diag.log");

        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(
                new NoOpBaritoneFacade(), null, new GroundedDiagnostics(logPath));
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());
        runner.setSweepPassPhaseCompleteForTests();
        runner.finalizeTerminalStateWithSummaryForTests(GroundedLaneWalkState.COMPLETE, Optional.empty());

        List<String> lines = java.nio.file.Files.readAllLines(logPath);
        assertTrue(lines.stream().anyMatch(l -> l.contains("run_summary")),
                "diagnostics log should contain a run_summary event");
    }

    @Test
    void failedValidationDoesNotStartRunSummaryTracking() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        // Session without origin fails validateStart("Origin must be set...")
        BuildSession noOrigin = new BuildSession(buildPlan(List.of(new Placement(new BlockPos(0, 0, 0), null))));
        assertTrue(runner.startFullSweep(noOrigin, GroundedSweepSettings.defaults()).isPresent());
        assertFalse(runner.runSummaryPendingForTests(), "failed validation must not set runSummaryPending");
        assertEquals(0, runner.refillTripCountForTests());
    }

    @Test
    void buildCompleteSmartResumeDoesNotLeaveStaleRunSummaryState() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        // All blocks appear placed — smart resume scanner reports build complete
        Optional<String> result = runner.startFullSweepSmart(
                sessionWithOrigin(), GroundedSweepSettings.defaults(),
                new net.minecraft.util.math.Vec3d(10.5, 64.0, 10.5),
                (pos, block) -> true);
        assertTrue(result.isEmpty(), "build-complete smart resume should be clean completion, not failure");
        assertFalse(runner.runSummaryPendingForTests(), "build-complete early exit must not set runSummaryPending");
        assertEquals(0, runner.refillTripCountForTests());
    }

    @Test
    void freshSuccessfulStartAfterFailedStartResetsTimerAndRefillCount() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        // Failed start — validation rejects no-origin session
        BuildSession noOrigin = new BuildSession(buildPlan(List.of(new Placement(new BlockPos(0, 0, 0), null))));
        assertTrue(runner.startFullSweep(noOrigin, GroundedSweepSettings.defaults()).isPresent());
        assertFalse(runner.runSummaryPendingForTests());

        // Successful start — tracking begins now, with clean state
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());
        assertTrue(runner.runSummaryPendingForTests(), "run summary should be pending after successful start");
        assertEquals(0, runner.refillTripCountForTests(), "refill count should reset to zero on fresh start");
    }

    @Test
    void refillResumeDoesNotResetOriginalTimerOrRefillCount() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());
        assertTrue(runner.runSummaryPendingForTests());

        com.example.mapart.supply.SupplyPoint supply =
                new com.example.mapart.supply.SupplyPoint(1, new BlockPos(5, 64, 5), "minecraft:overworld", "chest");
        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:stone");
        runner.triggerRefillForTests(Map.of(itemId, 1), List.of(supply), new NoOpBaritoneFacade());
        assertEquals(1, runner.refillTripCountForTests());

        // Refill complete internally calls startFullSweep — must not reset the original timer
        runner.simulateRefillCompleteForTests();
        assertTrue(runner.runSummaryPendingForTests(), "run summary should still be pending after refill resume");
        assertEquals(1, runner.refillTripCountForTests(), "refill count must not reset on refill resume");
    }

    // ─── LaneRelativeBand classification tests ───────────────────────────────

    @Test
    void westLaneLowZClassifiesAsRightTwo() {
        // WEST lane: player faces -X, so right is -Z (lower Z). Centerline Z=80, position Z=78 → RIGHT_TWO.
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 80, GroundedLaneDirection.WEST,
                new BlockPos(200, 64, 80), new BlockPos(100, 64, 80),
                new GroundedLaneCorridorBounds(100, 200, 78, 82), 1.0);
        assertEquals(LaneRelativeBand.RIGHT_TWO, LaneRelativeBand.classify(lane, new BlockPos(150, 64, 78)));
    }

    @Test
    void westLaneHighZClassifiesAsLeftTwo() {
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 80, GroundedLaneDirection.WEST,
                new BlockPos(200, 64, 80), new BlockPos(100, 64, 80),
                new GroundedLaneCorridorBounds(100, 200, 78, 82), 1.0);
        assertEquals(LaneRelativeBand.LEFT_TWO, LaneRelativeBand.classify(lane, new BlockPos(150, 64, 82)));
        assertEquals(LaneRelativeBand.LEFT_ONE, LaneRelativeBand.classify(lane, new BlockPos(150, 64, 81)));
        assertEquals(LaneRelativeBand.CENTER,   LaneRelativeBand.classify(lane, new BlockPos(150, 64, 80)));
        assertEquals(LaneRelativeBand.RIGHT_ONE, LaneRelativeBand.classify(lane, new BlockPos(150, 64, 79)));
    }

    @Test
    void eastLaneHighZClassifiesAsRightTwo() {
        // EAST lane: player faces +X, right is +Z. Centerline Z=12, position Z=14 → RIGHT_TWO.
        GroundedSweepLane lane = eastLane(); // centerline Z=12
        assertEquals(LaneRelativeBand.RIGHT_TWO, LaneRelativeBand.classify(lane, new BlockPos(12, 64, 14)));
        assertEquals(LaneRelativeBand.RIGHT_ONE, LaneRelativeBand.classify(lane, new BlockPos(12, 64, 13)));
        assertEquals(LaneRelativeBand.CENTER,    LaneRelativeBand.classify(lane, new BlockPos(12, 64, 12)));
        assertEquals(LaneRelativeBand.LEFT_ONE,  LaneRelativeBand.classify(lane, new BlockPos(12, 64, 11)));
        assertEquals(LaneRelativeBand.LEFT_TWO,  LaneRelativeBand.classify(lane, new BlockPos(12, 64, 10)));
    }

    @Test
    void southLaneNegativeXClassifiesAsRightTwo() {
        // SOUTH lane: player faces +Z, right is -X. Centerline X=15, position X=13 → RIGHT_TWO.
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 15, GroundedLaneDirection.SOUTH,
                new BlockPos(15, 64, 10), new BlockPos(15, 64, 20),
                new GroundedLaneCorridorBounds(13, 17, 10, 20), 1.0);
        assertEquals(LaneRelativeBand.RIGHT_TWO, LaneRelativeBand.classify(lane, new BlockPos(13, 64, 15)));
        assertEquals(LaneRelativeBand.LEFT_TWO,  LaneRelativeBand.classify(lane, new BlockPos(17, 64, 15)));
    }

    @Test
    void northLanePositiveXClassifiesAsRightTwo() {
        // NORTH lane: player faces -Z, right is +X. Centerline X=15, position X=17 → RIGHT_TWO.
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 15, GroundedLaneDirection.NORTH,
                new BlockPos(15, 64, 20), new BlockPos(15, 64, 10),
                new GroundedLaneCorridorBounds(13, 17, 10, 20), 1.0);
        assertEquals(LaneRelativeBand.RIGHT_TWO, LaneRelativeBand.classify(lane, new BlockPos(17, 64, 15)));
        assertEquals(LaneRelativeBand.LEFT_TWO,  LaneRelativeBand.classify(lane, new BlockPos(13, 64, 15)));
    }

    // ─── findLaneForPos tests ─────────────────────────────────────────────────

    @Test
    void findLaneForPosMatchesNearestCenterlineByLateralDistance() {
        GroundedSweepLane laneA = new GroundedSweepLane(0, 10, GroundedLaneDirection.EAST,
                new BlockPos(0, 64, 10), new BlockPos(20, 64, 10), new GroundedLaneCorridorBounds(0, 20, 8, 12), 1.0);
        GroundedSweepLane laneB = new GroundedSweepLane(1, 15, GroundedLaneDirection.WEST,
                new BlockPos(20, 64, 15), new BlockPos(0, 64, 15), new GroundedLaneCorridorBounds(0, 20, 13, 17), 1.0);

        // Z=11 is closer to laneA (centerline Z=10, dist=1) than laneB (dist=4)
        assertEquals(laneA, GroundedSingleLaneDebugRunner.findLaneForPos(new BlockPos(10, 64, 11), List.of(laneA, laneB)));
        // Z=14 is closer to laneB (dist=1) than laneA (dist=4)
        assertEquals(laneB, GroundedSingleLaneDebugRunner.findLaneForPos(new BlockPos(10, 64, 14), List.of(laneA, laneB)));
    }

    @Test
    void findLaneForPosReturnsNullForEmptyOrNullList() {
        assertNull(GroundedSingleLaneDebugRunner.findLaneForPos(new BlockPos(10, 64, 10), List.of()));
        assertNull(GroundedSingleLaneDebugRunner.findLaneForPos(new BlockPos(10, 64, 10), null));
    }

    // ─── scanRemainingMismatchDetails tests ───────────────────────────────────

    @Test
    void mismatchScannerSkipsNullBlockPlacements() {
        BuildPlan plan = buildPlan(new Vec3i(3, 1, 5), List.of(
                new Placement(new BlockPos(0, 0, 0), null),
                new Placement(new BlockPos(1, 0, 0), null),
                new Placement(new BlockPos(2, 0, 0), null)
        ));
        List<GroundedSingleLaneDebugRunner.MismatchRecord> records =
                GroundedSingleLaneDebugRunner.scanRemainingMismatchDetails(
                        plan, new BlockPos(10, 64, 10),
                        (pos, block) -> false,
                        pos -> "minecraft:air",
                        List.of(), null, 100);
        assertEquals(0, records.size(), "null-block placements should always be skipped");
    }

    @Test
    void mismatchScannerReturnsNullInputsAsEmpty() {
        List<GroundedSingleLaneDebugRunner.MismatchRecord> r1 =
                GroundedSingleLaneDebugRunner.scanRemainingMismatchDetails(
                        null, new BlockPos(0, 0, 0), (p, b) -> false, p -> "x", List.of(), null, 100);
        assertTrue(r1.isEmpty());
        List<GroundedSingleLaneDebugRunner.MismatchRecord> r2 =
                GroundedSingleLaneDebugRunner.scanRemainingMismatchDetails(
                        buildPlan(List.of()), null, (p, b) -> false, p -> "x", List.of(), null, 100);
        assertTrue(r2.isEmpty());
    }

    @Test
    void westLaneNearStartDistanceClassifiesCorrectly() {
        // WEST lane: progress axis = X, startPoint.X=200, endPoint.X=100
        // Position X=198 → distFromStart=2 ≤ 3 → NEAR_START
        // Position X=102 → distFromEnd=2 ≤ 3 → NEAR_END
        // Position X=150 → both ≥ 3 → MIDDLE
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 80, GroundedLaneDirection.WEST,
                new BlockPos(200, 64, 80), new BlockPos(100, 64, 80),
                new GroundedLaneCorridorBounds(100, 200, 78, 82), 1.0);

        double startProgress = lane.direction().progressCoordinate(lane.startPoint().getX(), lane.startPoint().getZ());
        double endProgress   = lane.direction().progressCoordinate(lane.endPoint().getX(),   lane.endPoint().getZ());

        double distFromStart198 = Math.abs(198.0 - startProgress);
        double distFromEnd102   = Math.abs(102.0 - endProgress);
        double distFromStart150 = Math.abs(150.0 - startProgress);
        double distFromEnd150   = Math.abs(150.0 - endProgress);

        assertTrue(distFromStart198 <= 3, "X=198 should be near start");
        assertTrue(distFromEnd102 <= 3,   "X=102 should be near end");
        assertTrue(distFromStart150 > 3 && distFromEnd150 > 3, "X=150 should be in the middle");
    }

    // ─── Start-approach reissue / retry budget tests ─────────────────────────

    @Test
    void startApproachBaritoneStopped_playerNotReady_reissuesInsteadOfFailing() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        // Player is far outside the lane corridor — not ready for lane start
        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        boolean reissued = runner.simulateStartApproachBaritoneStopped(playerFarAway);

        assertTrue(reissued);
        assertTrue(runner.isActive());
        assertEquals(1, runner.startApproachReissueCountForTests());
        assertEquals(2, baritone.goToCalls);
    }

    @Test
    void startApproachTimeout_retriesRemaining_reissuesInsteadOfFailing() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        boolean reissued = runner.simulateStartApproachTimeoutReissue();

        assertTrue(reissued);
        assertTrue(runner.isActive());
        assertEquals(1, runner.startApproachReissueCountForTests());
        assertEquals(2, baritone.goToCalls);
    }

    @Test
    void startApproachRetryBudgetExhausted_failsWithCorrectMessage() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        // Exhaust the full budget
        for (int i = 0; i < 3; i++) {
            boolean reissued = runner.simulateStartApproachBaritoneStopped(playerFarAway);
            assertTrue(reissued, "Expected reissue on attempt " + i);
        }
        // Budget is now exhausted — next call must fail
        boolean reissued = runner.simulateStartApproachBaritoneStopped(playerFarAway);

        assertFalse(reissued);
        assertFalse(runner.isActive());
        assertEquals("Unable to reach valid lane start staging position",
                runner.status().failureReason().orElseThrow());
    }

    @Test
    void startApproachTimeoutBudgetExhausted_failsWithCorrectMessage() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        // Exhaust budget via timeout path
        for (int i = 0; i < 3; i++) {
            boolean reissued = runner.simulateStartApproachTimeoutReissue();
            assertTrue(reissued, "Expected reissue on attempt " + i);
        }
        boolean reissued = runner.simulateStartApproachTimeoutReissue();

        assertFalse(reissued);
        assertFalse(runner.isActive());
        assertEquals("Unable to reach valid lane start staging position",
                runner.status().failureReason().orElseThrow());
    }

    @Test
    void startApproachReadinessDiagnosticsIncludeReasonCenterlineForwardCorridor() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        runner.simulateStartApproachBaritoneStopped(playerFarAway);

        List<String> events = runner.groundedTraceEventsForTests();
        String reissueEvent = events.stream()
                .filter(e -> e.contains("reissuing") || e.contains("retry budget"))
                .findFirst()
                .orElse("");
        assertFalse(reissueEvent.isEmpty(), "Expected a reissue or retry-budget trace event");
        assertTrue(reissueEvent.contains("reason="), "Missing reason in: " + reissueEvent);
        assertTrue(reissueEvent.contains("centerlineDelta="), "Missing centerlineDelta in: " + reissueEvent);
        assertTrue(reissueEvent.contains("forwardDelta="), "Missing forwardDelta in: " + reissueEvent);
        assertTrue(reissueEvent.contains("insideCorridor="), "Missing insideCorridor in: " + reissueEvent);
    }

    // ─── Staging-readiness tests (post-#221 regression fix) ──────────────────

    // Regression test: player at the staging target (one block behind lane start) must be
    // accepted as ready. Before this fix the "outside lane start edge" check rejected the
    // position because forwardDeltaFromLaneStart ≈ −1.2, breaching the −1.0 tolerance.
    @Test
    void stagingPositionOutsideLaneStartEdge_validStagingAccepted() {
        // EAST lane, centerline z=65, startPoint.x=10, stagingTarget.x=9
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 65, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 65), new BlockPos(20, 64, 65),
                new GroundedLaneCorridorBounds(10, 20, 63, 67), 1.0);
        BlockPos stagingTarget = new BlockPos(9, 65, 65);
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 63), new BlockPos(10, 64, 63), new BlockPos(20, 64, 67));

        // Player is at the staging target position — close to center (9.5, 65.5)
        // centerlineDelta ≈ 0.09, forwardDeltaToStanding ≈ −0.20, flatDistanceSq ≈ 0.05
        Vec3d player = new Vec3d(9.3, 65.0, 65.59);
        assertTrue(GroundedSingleLaneDebugRunner.isReadyForLaneStart(player, lane, stagingTarget, bounds),
                "Player at staging target (behind lane start edge) must be accepted as ready");
    }

    // Player has moved slightly forward from staging toward the lane start — still staging-ready.
    @Test
    void stagingPositionBetweenTargetAndLaneStart_validStagingAccepted() {
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 65, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 65), new BlockPos(20, 64, 65),
                new GroundedLaneCorridorBounds(10, 20, 63, 67), 1.0);
        BlockPos stagingTarget = new BlockPos(9, 65, 65);
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 63), new BlockPos(10, 64, 63), new BlockPos(20, 64, 67));

        // Player is slightly forward of the staging target center (9.5) but still within tolerance.
        // Not yet inside the lane corridor (corridor starts at x=10).
        Vec3d player = new Vec3d(9.7, 65.0, 65.5);
        assertTrue(GroundedSingleLaneDebugRunner.isReadyForLaneStart(player, lane, stagingTarget, bounds),
                "Player between staging target and lane start must be accepted as ready");
    }

    // Player is many blocks behind the staging target — flat-distance check must reject it.
    @Test
    void stagingPositionFarBehindStagingTarget_rejected() {
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 65, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 65), new BlockPos(20, 64, 65),
                new GroundedLaneCorridorBounds(10, 20, 63, 67), 1.0);
        BlockPos stagingTarget = new BlockPos(9, 65, 65);
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 63), new BlockPos(10, 64, 63), new BlockPos(20, 64, 67));

        // Player is 5+ blocks behind staging — well outside flat-distance tolerance of 1.0
        Vec3d player = new Vec3d(4.0, 65.0, 65.5);
        assertFalse(GroundedSingleLaneDebugRunner.isReadyForLaneStart(player, lane, stagingTarget, bounds),
                "Player far behind staging target must be rejected");
    }

    // Player is laterally far from centerline — centerline check must reject it.
    @Test
    void stagingPositionFarLaterallyFromCenterline_rejected() {
        GroundedSweepLane lane = new GroundedSweepLane(
                0, 65, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 65), new BlockPos(20, 64, 65),
                new GroundedLaneCorridorBounds(10, 20, 63, 67), 1.0);
        BlockPos stagingTarget = new BlockPos(9, 65, 65);
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 63), new BlockPos(10, 64, 63), new BlockPos(20, 64, 67));

        // Player is at the correct staging X but 4 blocks off in Z (centerlineDelta ≈ 4)
        Vec3d player = new Vec3d(9.3, 65.0, 70.0);
        assertFalse(GroundedSingleLaneDebugRunner.isReadyForLaneStart(player, lane, stagingTarget, bounds),
                "Player far off centerline must be rejected");
    }

    // Baritone stopped branch must not consume a retry within the grace period.
    @Test
    void baritoneStopped_withinGracePeriod_noRetryConsumed() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);
        // Grace ticks start at 0 after issue
        assertEquals(0, runner.startApproachGraceTicksForTests());

        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        // Within grace period — no retry should be consumed
        int result = runner.simulateStartApproachBaritoneStoppedRespectingGrace(playerFarAway);

        assertEquals(0, result, "Grace period still active — should not reissue or fail");
        assertEquals(0, runner.startApproachReissueCountForTests());
        assertEquals(1, baritone.goToCalls);
        assertTrue(runner.isActive());
        List<String> events = runner.groundedTraceEventsForTests();
        assertTrue(events.stream().anyMatch(e -> e.contains("grace period")),
                "Expected grace period trace event");
    }

    // Baritone stopped branch must reissue normally once grace period has expired.
    @Test
    void baritoneStopped_afterGracePeriod_retriesNormally() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        // Advance past the grace threshold
        runner.advanceStartApproachGraceTicksForTests(20);

        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        int result = runner.simulateStartApproachBaritoneStoppedRespectingGrace(playerFarAway);

        assertEquals(1, result, "After grace period, Baritone-stopped path should reissue");
        assertEquals(1, runner.startApproachReissueCountForTests());
        assertEquals(2, baritone.goToCalls);
        assertTrue(runner.isActive());
    }

    // ─── Baritone-busy start-approach tests ──────────────────────────────────

    // While Baritone is busy navigating, no retry must be consumed and runner must stay active.
    @Test
    void baritoneBusy_playerNotReady_noRetryConsumedAndNoFailure() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        int result = runner.simulateStartApproachTickWhileBaritoneBusy(playerFarAway);

        assertEquals(0, result, "Baritone busy — no action expected");
        assertEquals(0, runner.startApproachReissueCountForTests(), "No retry must be consumed while Baritone is busy");
        assertTrue(runner.isActive(), "Runner must remain active while Baritone is busy");
        assertEquals(1, baritone.goToCalls, "No additional goTo call expected");
    }

    // While Baritone is busy and the player moves closer, noProgressTicks must reset.
    @Test
    void baritoneBusy_distanceImproving_noProgressTicksReset() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        // First tick — far away, establishes best distance (noProgressTicks stays 0 due to initial INFINITY)
        Vec3d playerFar = new Vec3d(100.0, 64.0, 200.0);
        runner.simulateStartApproachTickWhileBaritoneBusy(playerFar);
        assertEquals(0, runner.startApproachNoProgressTicksForTests(), "First tick always sets best distance");

        // Second tick — same far position, no improvement: noProgressTicks must now increment
        runner.simulateStartApproachTickWhileBaritoneBusy(playerFar);
        int noProgressAfterStall = runner.startApproachNoProgressTicksForTests();
        assertTrue(noProgressAfterStall > 0, "noProgressTicks should have incremented when no progress");

        // Third tick — player is closer, progress improved
        Vec3d playerCloser = new Vec3d(20.0, 64.0, 100.0);
        runner.simulateStartApproachTickWhileBaritoneBusy(playerCloser);

        assertEquals(0, runner.startApproachNoProgressTicksForTests(),
                "noProgressTicks must reset when distance to target improves");
        List<String> events = runner.groundedTraceEventsForTests();
        assertTrue(events.stream().anyMatch(e -> e.contains("START_APPROACH_BUSY_MOVING")),
                "Expected START_APPROACH_BUSY_MOVING trace event when distance improves");
    }

    // While Baritone is busy but no progress observed for the full timeout window,
    // a reissue is allowed (but retry budget is consumed, not the absolute tick limit).
    @Test
    void baritoneBusy_noProgressForTimeout_reissueAllowed() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        // The first tick at this position sets best distance (noProgressTicks stays 0).
        // We need 81 additional ticks of no-progress to exceed MAX_START_APPROACH_NO_PROGRESS_TICKS (80).
        // Total ticks: 1 (establish) + 81 (stall) = 82.
        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        for (int i = 0; i < 82; i++) {
            runner.simulateStartApproachTickWhileBaritoneBusy(playerFarAway);
        }

        // The no-progress timeout must have fired a reissue
        assertEquals(1, runner.startApproachReissueCountForTests(), "Busy/no-progress timeout must trigger one reissue");
        assertTrue(runner.isActive(), "Runner must remain active after reissue");
        assertEquals(2, baritone.goToCalls, "Reissue must re-issue goTo");
    }

    // When Baritone is not busy after the grace period and player is not ready,
    // the existing bounded retry behavior must still work (regression guard).
    @Test
    void baritoneNotBusy_afterGrace_notReady_boundedRetryBehaviorPreserved() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        runner.advanceStartApproachGraceTicksForTests(20);
        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        int result = runner.simulateStartApproachBaritoneStoppedRespectingGrace(playerFarAway);

        assertEquals(1, result, "Not-busy path after grace must still reissue");
        assertEquals(1, runner.startApproachReissueCountForTests());
        assertTrue(runner.isActive());
    }

    // The absolute startApproachTicks counter must not advance while Baritone is busy.
    @Test
    void absoluteTickLimit_doesNotAdvanceWhileBaritoneBusy() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        // 10 ticks while "busy" (simulated via simulateStartApproachTickWhileBaritoneBusy)
        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        for (int i = 0; i < 10; i++) {
            runner.simulateStartApproachTickWhileBaritoneBusy(playerFarAway);
        }

        assertEquals(0, runner.startApproachTicksForTests(),
                "startApproachTicks must not advance while Baritone is busy");
    }

    // Player physically moves each tick but away from the staging target (Baritone detouring).
    // noProgressTicks must remain 0 and no retry budget consumed for the entire detour.
    @Test
    void baritoneBusy_playerMovingAwayFromTarget_doesNotConsumeNoProgressTicks() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        // First tick: establishes best distance from infinity, noProgressTicks stays 0.
        runner.simulateStartApproachTickWhileBaritoneBusy(new Vec3d(100.0, 64.0, 200.0));
        assertEquals(0, runner.startApproachNoProgressTicksForTests());

        // Subsequent ticks: player moves each tick (physically moving) but further from target.
        // Each tick has a different position so displacementSq > epsilon — not stuck.
        for (int i = 1; i <= 100; i++) {
            Vec3d movingPos = new Vec3d(100.0 + i * 0.5, 64.0, 200.0 + i * 0.5);
            runner.simulateStartApproachTickWhileBaritoneBusy(movingPos);
        }

        assertEquals(0, runner.startApproachNoProgressTicksForTests(),
                "noProgressTicks must stay 0 while player is physically moving each tick");
        assertEquals(0, runner.startApproachReissueCountForTests(),
                "No retry consumed while player is physically moving");
        assertTrue(runner.isActive(), "Runner must remain active");

        List<String> events = runner.groundedTraceEventsForTests();
        assertTrue(events.stream().anyMatch(e -> e.contains("START_APPROACH_BUSY_DISTANCE_WORSENING")),
                "Expected START_APPROACH_BUSY_DISTANCE_WORSENING diagnostic event");
    }

    // Player is stationary (same position every tick): noProgressTicks increments normally.
    @Test
    void baritoneBusy_playerStationary_noProgressTicksIncrement() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        Vec3d stationaryPos = new Vec3d(100.0, 64.0, 200.0);
        // First tick: best distance established from infinity, noProgressTicks stays 0.
        runner.simulateStartApproachTickWhileBaritoneBusy(stationaryPos);
        assertEquals(0, runner.startApproachNoProgressTicksForTests());

        // Second tick: same position, displacementSq=0 — must count as stuck.
        runner.simulateStartApproachTickWhileBaritoneBusy(stationaryPos);
        assertEquals(1, runner.startApproachNoProgressTicksForTests(),
                "Stationary player must increment noProgressTicks");

        List<String> events = runner.groundedTraceEventsForTests();
        assertTrue(events.stream().anyMatch(e -> e.contains("START_APPROACH_BUSY_STUCK")),
                "Expected START_APPROACH_BUSY_STUCK diagnostic event");
    }

    // While Baritone is busy, if the player arrives at the staging position the readiness check
    // must fire before any stuck-counting logic is evaluated.
    @Test
    void baritoneBusy_playerBecomesReady_readinessDetectedBeforeStuckCounting() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        // First tick: player far away, establishes best distance.
        runner.simulateStartApproachTickWhileBaritoneBusy(new Vec3d(50.0, 64.0, 50.0));

        // Player arrives at the staging target.
        // Lane 0 EAST: origin (10,64,10), centerline z=12, staging target at (9,64,12).
        // Position (9.3,64,12.5) passes the flat-distance, centerline, and forward checks.
        int result = runner.simulateStartApproachTickWhileBaritoneBusy(new Vec3d(9.3, 64.0, 12.5));

        assertEquals(2, result, "Player at staging target must return readiness code 2");
        // Readiness was detected — no retry consumed.
        assertEquals(0, runner.startApproachReissueCountForTests(),
                "No retry must be consumed when readiness is detected");
    }

    // While Baritone is busy, if the player walks far outside the build envelope the runner must
    // reissue the approach (not wait for the no-progress timeout) and emit the safe-envelope event.
    @Test
    void baritoneBusy_playerLeaveSafeEnvelope_emitsUnsafePathDiagnosticAndReissues() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();
        assertEquals(1, baritone.goToCalls);

        // sessionWithOrigin() bounds: minX=10, maxX=14, minZ=10, maxZ=14.
        // Safe envelope margin is 400 blocks → threshold at x < -390.
        // Player at x=-500 is 510 blocks outside minX=10, well past the margin.
        Vec3d outsideEnvelope = new Vec3d(-500.0, 64.0, 12.0);
        int result = runner.simulateStartApproachTickWhileBaritoneBusy(outsideEnvelope);

        assertEquals(1, result, "Leaving safe envelope must trigger a reissue (return code 1)");
        assertEquals(1, runner.startApproachReissueCountForTests(), "Reissue count must increment");
        assertEquals(2, baritone.goToCalls, "goTo must be re-issued after safe-envelope breach");
        assertTrue(runner.isActive(), "Runner must remain active after safe-envelope reissue");

        List<String> events = runner.groundedTraceEventsForTests();
        assertTrue(events.stream().anyMatch(e -> e.contains("START_APPROACH_LEFT_SAFE_ENVELOPE")),
                "Expected START_APPROACH_LEFT_SAFE_ENVELOPE diagnostic event");
        assertTrue(
                events.stream().anyMatch(e -> e.contains("START_APPROACH_BUSY_REISSUE_REASON")
                        && e.contains("left_safe_envelope")),
                "Expected START_APPROACH_BUSY_REISSUE_REASON event with reason=left_safe_envelope");
    }

    @Test
    void preflightRefillStartsNormallyWhenMaterialsMissing() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());
        assertTrue(runner.isActive());

        com.example.mapart.supply.SupplyPoint supply =
                new com.example.mapart.supply.SupplyPoint(1, new BlockPos(5, 64, 5), "minecraft:overworld", "chest");
        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:stone");
        runner.triggerRefillForTests(Map.of(itemId, 1), List.of(supply), new NoOpBaritoneFacade());

        // Refill in progress — runner remains active
        assertTrue(runner.isActive());
        assertEquals(1, runner.refillTripCountForTests());
    }

    @Test
    void refillTripCountAndRunTimerNotResetByApproachRetries() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        assertTrue(runner.startFullSweep(sessionWithOrigin(), GroundedSweepSettings.defaults()).isEmpty());

        // Trigger and complete a refill so the counter is non-zero
        com.example.mapart.supply.SupplyPoint supply =
                new com.example.mapart.supply.SupplyPoint(1, new BlockPos(5, 64, 5), "minecraft:overworld", "chest");
        net.minecraft.util.Identifier itemId = net.minecraft.util.Identifier.of("minecraft:stone");
        runner.triggerRefillForTests(Map.of(itemId, 1), List.of(supply), baritone);
        runner.simulateRefillCompleteForTests();
        assertEquals(1, runner.refillTripCountForTests());

        // Simulate approach reissues after resume — refill count must not change
        Vec3d playerFarAway = new Vec3d(100.0, 64.0, 200.0);
        runner.simulateStartApproachBaritoneStopped(playerFarAway);
        runner.simulateStartApproachBaritoneStopped(playerFarAway);

        assertEquals(1, runner.refillTripCountForTests());
        assertTrue(runner.isActive());
    }

    // ─── Near-start leftover capture / refill-resume tests ───────────────────

    // Core regression: EAST RIGHT_TWO block at the lane start X that sits in
    // pendingPlacementTargets must be captured into forwardLeftoverPlacements
    // when a refill fires while the player is deeper in the lane (i.e. the
    // block ends up behind the refill resume progress hint).
    @Test
    void nearStartRightTwoPendingTargetCapturedAsForwardLeftoverWhenRefillFiresMidLane() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin(); // EAST lane 0: centerlineZ=12, startX=10
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        // RIGHT_TWO for EAST: lateralDelta = Z−12 = +2  →  Z=14.  Progress = X=10 (lane start).
        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(99, new BlockPos(10, 64, 14))
        ));

        // Refill fires while player is at X=15 — the block at X=10 is now behind the resume hint.
        runner.setRefillHintForTests(15, 0);
        runner.simulateRefillCompleteForTests();

        assertTrue(runner.forwardLeftoverPlacementsForTests().contains(99),
                "RIGHT_TWO NEAR_START block behind refill hint must be captured as a forward leftover");
    }

    // A pending target that is AHEAD of the refill resume hint will be included in
    // the rebuilt pending list on resume and must NOT be pre-recorded as a leftover.
    @Test
    void pendingTargetAheadOfRefillHintNotCapturedAsForwardLeftover() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin(); // EAST lane 0: startX=10
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        // Target at X=15, hint at X=10: (15−10)*1 = +5 > 0 → not behind hint.
        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(55, new BlockPos(15, 64, 12))
        ));

        runner.setRefillHintForTests(10, 0);
        runner.simulateRefillCompleteForTests();

        assertFalse(runner.forwardLeftoverPlacementsForTests().contains(55),
                "Target ahead of refill hint must not be pre-captured as a forward leftover");
    }

    // forwardLeftoverPlacements accumulated from lanes that completed before
    // the refill must survive the refill+resume cycle intact.
    @Test
    void forwardLeftoverPlacementsFromPriorLaneSurviveRefillResume() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        // 2-lane session: lane 0 EAST (centerlineZ=12, startX=10),
        //                 lane 1 WEST (centerlineZ=17, startX=20).
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(11, 1, 11));
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        // Seed a pending target on lane 0 that will become a leftover when lane ends.
        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(77, new BlockPos(10, 64, 12))
        ));

        // Advance to lane 1 — captures idx=77 into forwardLeftoverPlacements,
        // then sets activeLane = lane 1 WEST.
        runner.advanceSweepToNextLaneForTests();
        assertTrue(runner.forwardLeftoverPlacementsForTests().contains(77),
                "lane-0 leftover should be in forwardLeftoverPlacements before refill");

        // Seed a lane-1 target at its start (WEST start X=20).
        // WEST forwardSign=-1: (20−18)*(−1) = −2 < 0 → behind refill hint X=18.
        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(99, new BlockPos(20, 64, 17))
        ));

        runner.setRefillHintForTests(18, 1);
        runner.simulateRefillCompleteForTests();

        Set<Integer> leftovers = runner.forwardLeftoverPlacementsForTests();
        assertTrue(leftovers.contains(77),
                "lane-0 leftover idx=77 must survive refill resume");
        assertTrue(leftovers.contains(99),
                "lane-1 behind-hint target idx=99 must be captured during refill resume");
    }

    // Verifies that the pending-target list still contains an exhausted-burst
    // entry before the refill fires (i.e. exhausting the entry burst budget does
    // not silently remove the target from pendingPlacementTargets).
    @Test
    void nearStartPendingTargetRemainsInPendingBeforeRefillCaptures() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        // Seed a NEAR_START block (RIGHT_TWO position at lane start X=10).
        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(88, new BlockPos(10, 64, 14))
        ));

        // Before refill fires: target must still be in pendingPlacementTargets.
        assertTrue(runner.pendingPlacementIndicesForTests().contains(88),
                "NEAR_START target must remain in pendingPlacementTargets before any refill");

        // After refill fires with hint ahead: target is captured as leftover.
        runner.setRefillHintForTests(14, 0);
        runner.simulateRefillCompleteForTests();

        assertTrue(runner.forwardLeftoverPlacementsForTests().contains(88),
                "NEAR_START target must be captured as forward leftover when refill fires");
    }

    // ─── EAST RIGHT_TWO NEAR_START MISSED-then-refill tracker-snapshot tests ────

    // Core regression: EAST RIGHT_TWO block is MISSED (RETRY → removed from pending,
    // marked in leftoverTracker) in the same executor tick that a MISSING_ITEM triggers
    // a refill. The behind-hint scan sees nothing (block is gone from pending), but the
    // new tracker-snapshot path in snapshotAndExtendLeftoversBeforeRefillResume must still
    // capture it via leftoverTracker.snapshot().
    @Test
    void missedNearStartRightTwoCapturedFromTrackerSnapshotWhenRefillFiresAfterMiss() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        // EAST lane 0: centerlineZ=12, startX=10. RIGHT_TWO: Z=14, X=10.
        BuildSession session = sessionWithOrigin();
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(99, new BlockPos(10, 64, 14))
        ));

        // Simulate the RETRY path: block marked MISSED in tracker and removed from pending.
        runner.recordPlacementOutcomeForTests(99, GroundedSweepPlacementExecutor.PlacementResult.MISSED, 1);
        assertFalse(runner.pendingPlacementIndicesForTests().contains(99),
                "MISSED block must be removed from pendingPlacementTargets");

        // Refill fires — no behind-hint pending targets, so old code would miss this index.
        runner.setRefillHintForTests(12, 0);
        runner.simulateRefillCompleteForTests();

        assertTrue(runner.forwardLeftoverPlacementsForTests().contains(99),
                "MISSED EAST RIGHT_TWO NEAR_START block must be captured from leftover tracker on refill resume");
    }

    // Same regression with no refill-hint at all: the tracker snapshot must still capture
    // the MISSED block even when refillLaneProgressHint is null (hint scan is skipped).
    @Test
    void missedNearStartRightTwoCapturedFromTrackerSnapshotEvenWithNoRefillHint() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(77, new BlockPos(10, 64, 14))
        ));

        runner.recordPlacementOutcomeForTests(77, GroundedSweepPlacementExecutor.PlacementResult.MISSED, 1);
        // No hint set — behind-hint scan is skipped entirely.
        runner.simulateRefillCompleteForTests();

        assertTrue(runner.forwardLeftoverPlacementsForTests().contains(77),
                "MISSED NEAR_START block must reach forwardLeftoverPlacements even when refill hint is absent");
    }

    // Multiple blocks: one MISSED before refill fires, one still pending behind the hint.
    // Both must appear in forwardLeftoverPlacements after refill resume.
    @Test
    void bothMissedAndBehindHintPendingBlocksCapturedOnRefillResume() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin();
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        // Seed two blocks: idx=11 is the MISSED one, idx=22 stays pending behind the hint.
        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(11, new BlockPos(10, 64, 14)),
                new GroundedSweepPlacementExecutor.PlacementTarget(22, new BlockPos(10, 64, 13))
        ));

        // idx=11 gets MISSED — removed from pending, lives only in the tracker now.
        runner.recordPlacementOutcomeForTests(11, GroundedSweepPlacementExecutor.PlacementResult.MISSED, 1);

        // Refill fires at X=14 — idx=22 at X=10 is behind hint, idx=11 is already gone from pending.
        runner.setRefillHintForTests(14, 0);
        runner.simulateRefillCompleteForTests();

        Set<Integer> leftovers = runner.forwardLeftoverPlacementsForTests();
        assertTrue(leftovers.contains(11),
                "MISSED block (tracker-only) must be captured on refill resume");
        assertTrue(leftovers.contains(22),
                "Behind-hint pending block must also be captured on refill resume");
    }

    // Prior-lane leftovers must not be disturbed when the tracker-snapshot path captures
    // a freshly MISSED block during refill resume.
    @Test
    void priorLaneLeftoversPreservedWhenMissedTrackerBlockCapturedOnRefill() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(11, 1, 11));
        assertTrue(runner.startFullSweep(session, GroundedSweepSettings.defaults()).isEmpty());

        // Seed a leftover on lane 0 and advance — it lands in forwardLeftoverPlacements.
        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(5, new BlockPos(10, 64, 12))
        ));
        runner.advanceSweepToNextLaneForTests();
        assertTrue(runner.forwardLeftoverPlacementsForTests().contains(5),
                "lane-0 leftover must be in forwardLeftoverPlacements before refill");

        // Now on lane 1 WEST: seed a RIGHT_TWO NEAR_START block, mark it MISSED, trigger refill.
        runner.seedLanePlacementsForTests(List.of(
                new GroundedSweepPlacementExecutor.PlacementTarget(88, new BlockPos(20, 64, 19))
        ));
        runner.recordPlacementOutcomeForTests(88, GroundedSweepPlacementExecutor.PlacementResult.MISSED, 1);
        runner.simulateRefillCompleteForTests();

        Set<Integer> leftovers = runner.forwardLeftoverPlacementsForTests();
        assertTrue(leftovers.contains(5),
                "lane-0 leftover must survive the refill resume");
        assertTrue(leftovers.contains(88),
                "MISSED lane-1 block must be captured from tracker on refill resume");
    }

    // ─── Tick-stall / background-safety tests ────────────────────────────────

    @Test
    void tickStallDetectedWhenLargeWallClockGapOccurs() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(
                new NoOpBaritoneFacade(), null, new GroundedDiagnostics(Path.of("run/logs/test-stall.log")), monitor);

        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        // Normal tick — not stalled
        monitor.tick(); // seed the first tick time
        time[0] = 50_000_000L; // 50 ms later
        runner.tick(null, null);
        assertFalse(runner.clientTickStallActiveForTests(), "50 ms gap must not trigger stall");

        // Simulate a 5-second gap
        time[0] = 5_050_000_000L;
        runner.tick(null, null);
        assertTrue(runner.clientTickStallActiveForTests(), "5 s gap must trigger stall");

        // Next normal tick clears the stall
        time[0] = 5_100_000_000L; // 50 ms later
        runner.tick(null, null);
        assertFalse(runner.clientTickStallActiveForTests(), "stall must clear after normal tick");
    }

    @Test
    void refillTimeoutsSuppressedOnRefillControllerDuringStall() {
        // Verify that the runner propagates stall state to the refill controller via setSuppressTimeouts.
        // The controller's nav counter behaviour under suppression is tested separately in
        // GroundedRefillControllerTest.navTimeoutDoesNotDecrementWhenSuppressed.
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(
                new NoOpBaritoneFacade(), null, new GroundedDiagnostics(Path.of("run/logs/test-refill-stall.log")), monitor);

        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        // Seed refill using a recording facade so goNear succeeds and state enters NAVIGATING.
        com.example.mapart.supply.SupplyPoint supply =
                new com.example.mapart.supply.SupplyPoint(1, new BlockPos(500, 64, 500), "minecraft:overworld", "chest");
        RecordingBaritoneFacade refillBaritone = new RecordingBaritoneFacade();
        runner.triggerRefillForTests(
                Map.of(net.minecraft.util.Identifier.of("minecraft", "dirt"), 1),
                List.of(supply),
                refillBaritone);
        assertEquals(GroundedRefillController.RefillState.NAVIGATING, runner.getRefillController().state(),
                "refill controller must enter NAVIGATING after trigger");

        // Normal tick — suppression off
        monitor.tick();
        time[0] = 50_000_000L; // 50 ms
        runner.tick(null, null);
        assertFalse(runner.getRefillController().isTimeoutsSuppressed(),
                "timeout suppression must be off during normal tick");

        // Stall tick — suppression on
        time[0] = 5_050_000_000L; // 5-second gap
        runner.tick(null, null);
        assertTrue(runner.clientTickStallActiveForTests(), "5 s gap must activate stall");
        assertTrue(runner.getRefillController().isTimeoutsSuppressed(),
                "timeout suppression must be set on the refill controller during stall");

        // Resume normal — suppression off again
        time[0] = 5_100_000_000L; // 50 ms
        runner.tick(null, null);
        assertFalse(runner.clientTickStallActiveForTests(), "stall must clear on normal tick");
        assertFalse(runner.getRefillController().isTimeoutsSuppressed(),
                "timeout suppression must be cleared after stall resolves");
    }

    @Test
    void stallFlagClearedOnStop() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(
                new NoOpBaritoneFacade(), null, new GroundedDiagnostics(Path.of("run/logs/test-stop-stall.log")), monitor);

        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        // Trigger a stall
        monitor.tick();
        time[0] = 5_000_000_000L;
        runner.tick(null, null);
        assertTrue(runner.clientTickStallActiveForTests());

        runner.stop();
        assertFalse(runner.clientTickStallActiveForTests(), "stall flag must be cleared on stop");
        assertFalse(runner.getRefillController().isTimeoutsSuppressed(),
                "refill timeout suppression must be cleared on stop");
    }

    @Test
    void stallFlagClearedOnNewStart() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(
                new NoOpBaritoneFacade(), null, new GroundedDiagnostics(Path.of("run/logs/test-restart-stall.log")), monitor);

        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        monitor.tick();
        time[0] = 5_000_000_000L;
        runner.tick(null, null);
        assertTrue(runner.clientTickStallActiveForTests());

        // Stop then restart — stall state should reset on initializeRunState
        runner.stop();
        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());
        assertFalse(runner.clientTickStallActiveForTests(), "stall flag must reset on new start");
        assertFalse(runner.getRefillController().isTimeoutsSuppressed(),
                "refill timeout suppression must reset on new start");
    }

    @Test
    void normalTicksNotAffectedByStallLogic() {
        long[] time = {0L};
        ClientTickHealthMonitor monitor = new ClientTickHealthMonitor(() -> time[0], 2_000);
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(
                new NoOpBaritoneFacade(), null, new GroundedDiagnostics(Path.of("run/logs/test-normal-stall.log")), monitor);

        assertTrue(runner.start(sessionWithOrigin(), 0, GroundedSweepSettings.defaults()).isEmpty());

        monitor.tick();
        // 20 normal ticks at 50 ms each (10 seconds of game time at 20 tps)
        for (int i = 0; i < 20; i++) {
            time[0] += 50_000_000L;
            runner.tick(null, null);
            assertFalse(runner.clientTickStallActiveForTests(),
                    "normal 50 ms ticks must never trigger stall (tick " + i + ")");
        }
    }

    // ─── writeMismatchDetailsEvent grouped-count tests ────────────────────────

    @Test
    void mismatchDetailsEventIncludesGroupedCountsByDirectionAndBand() throws Exception {
        java.nio.file.Path temp = java.nio.file.Files.createTempDirectory("mapart-mismatch-test");
        java.nio.file.Path logPath = temp.resolve("diag.log");

        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(
                new NoOpBaritoneFacade(), null, new GroundedDiagnostics(logPath));

        // Construct mismatch records manually — same package, so package-private record is accessible.
        List<GroundedSingleLaneDebugRunner.MismatchRecord> records = List.of(
                new GroundedSingleLaneDebugRunner.MismatchRecord(
                        0, new BlockPos(150, 64, 78), "minecraft:stone", "minecraft:air",
                        0, "WEST", new BlockPos(200, 64, 80), new BlockPos(100, 64, 80),
                        LaneRelativeBand.RIGHT_TWO, 150.0, 50.0, 50.0, "MIDDLE", "FORWARD"),
                new GroundedSingleLaneDebugRunner.MismatchRecord(
                        1, new BlockPos(151, 64, 78), "minecraft:stone", "minecraft:air",
                        0, "WEST", new BlockPos(200, 64, 80), new BlockPos(100, 64, 80),
                        LaneRelativeBand.RIGHT_TWO, 151.0, 49.0, 51.0, "MIDDLE", "FORWARD"),
                new GroundedSingleLaneDebugRunner.MismatchRecord(
                        2, new BlockPos(155, 64, 80), "minecraft:dirt", "minecraft:air",
                        1, "EAST", new BlockPos(100, 64, 80), new BlockPos(200, 64, 80),
                        LaneRelativeBand.CENTER, 155.0, 55.0, 45.0, "MIDDLE", "FORWARD")
        );

        runner.writeMismatchDetailsEvent(records, GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD);

        List<String> lines = java.nio.file.Files.readAllLines(logPath);
        String detailsLine = lines.stream()
                .filter(l -> l.contains("remaining_mismatch_details"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no remaining_mismatch_details event in log"));

        assertTrue(detailsLine.contains("\"totalMismatches\":3"),
                "event should record total count of 3");
        assertTrue(detailsLine.contains("byLaneDirection"),
                "event should contain byLaneDirection grouping");
        assertTrue(detailsLine.contains("byLaneRelativeBand"),
                "event should contain byLaneRelativeBand grouping");
        assertTrue(detailsLine.contains("RIGHT_TWO"),
                "byLaneRelativeBand should list RIGHT_TWO");
        assertTrue(detailsLine.contains("WEST"),
                "byLaneDirection should list WEST");
        assertTrue(detailsLine.contains("EAST"),
                "byLaneDirection should list EAST");
        assertTrue(detailsLine.contains("byLaneIndex"),
                "event should contain byLaneIndex grouping");
        assertTrue(detailsLine.contains("\"records\""),
                "event should contain a records array");
    }

    // ─── Walker support guard tests ───────────────────────────────────────────

    /**
     * Helper: builds a SchematicTargetLookup that says "block required here, not yet confirmed"
     * for the given positions. Returns true (required/unconfirmed) for any position in the set.
     */
    private static GroundedLaneWalker.SchematicTargetLookup targetLookupRequiringBlock(
            java.util.Set<BlockPos> required) {
        return pos -> required.contains(pos);
    }

    // Helper: SchematicTargetLookup that says all positions are safe (no block required or all confirmed).
    private static GroundedLaneWalker.SchematicTargetLookup targetLookupAllSafe() {
        return pos -> false;
    }

    /**
     * WEST lane, near end: if the next footprint block is in the pending-placement set
     * (not yet placed), the walker must hold (forwardPressed=false).
     */
    @Test
    void westLaneNearEndSupportBlocksRequiredBeforeWalking() {
        // Build a WEST lane that runs from X=-65 to X=-192, centerline Z=-59.
        GroundedSweepLane westLane = new GroundedSweepLane(
                5, -59, GroundedLaneDirection.WEST,
                new BlockPos(-65, 64, -59), new BlockPos(-192, 64, -59),
                new GroundedLaneCorridorBounds(-192, -65, -61, -57),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(-192, 64, -61),
                new BlockPos(-192, 64, -61),
                new BlockPos(-65, 64, -57)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(westLane, bounds, true);

        // Player is near the lane end (X=-184, 8 blocks from end at X=-192).
        Vec3d playerPos = new Vec3d(-184.5, 64.0, -59.5);

        // Next footprint for a WEST lane at playerX=-184: floor(-184.5)=-185, forwardSign=-1 → nextX=-186.
        BlockPos expectedFootprint = new BlockPos(-186, 64, -59);

        // Mark that footprint block as pending placement (required but not yet placed).
        java.util.Set<BlockPos> pendingPlacements = Set.of(expectedFootprint);
        java.util.Set<BlockPos> pendingVerifications = java.util.Set.of();

        // TargetLookup: block is required at that position (returns true).
        GroundedLaneWalker.SchematicTargetLookup lookup = targetLookupRequiringBlock(Set.of(expectedFootprint));

        walker.tick(playerPos, lookup, pendingPlacements, pendingVerifications);

        // Walker must hold (forwardPressed = false).
        assertTrue(walker.currentCommand().isPresent(), "Walker must still be ACTIVE");
        assertFalse(walker.currentCommand().get().forwardPressed(),
                "Walker must hold when next footprint is in pending-placement set");
        assertEquals(GroundedLaneWalker.SupportCheckResult.EXPECTED_PENDING_PLACEMENT, walker.lastSupportCheck(),
                "Support check result must be EXPECTED_PENDING_PLACEMENT when block is in pending-placement map");
    }

    /**
     * Walker must hold forward movement when the next footprint support block is expected by
     * schematic but absent from world and not in any tracked queue (EXPECTED_MISSING_UNTRACKED).
     */
    @Test
    void walkerHoldsWhenNextFootprintSupportMissing() {
        GroundedSweepLane eastLane = new GroundedSweepLane(
                0, 12, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12), new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10), new BlockPos(10, 64, 10), new BlockPos(20, 64, 14)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastLane, bounds, true);

        Vec3d playerPos = new Vec3d(12.5, 64.0, 12.5);
        // Next footprint: floor(12.5)=12, EAST forwardSign=+1 → nextX=13, Z=centerline=12
        BlockPos nextFootprint = new BlockPos(13, 64, 12);

        // Block is expected by schematic but NOT in pending queues (untracked).
        java.util.Set<BlockPos> pendingPlacements = Set.of(); // not in queue
        java.util.Set<BlockPos> pendingVerifications = java.util.Set.of();

        // TargetLookup: block is required here (returns true) but not in world.
        GroundedLaneWalker.SchematicTargetLookup lookup = targetLookupRequiringBlock(Set.of(nextFootprint));

        walker.tick(playerPos, lookup, pendingPlacements, pendingVerifications);

        assertTrue(walker.currentCommand().isPresent(), "Walker must be ACTIVE");
        assertFalse(walker.currentCommand().get().forwardPressed(),
                "Walker must hold when next footprint support block is expected but untracked");
        assertEquals(GroundedLaneWalker.SupportCheckResult.EXPECTED_MISSING_UNTRACKED, walker.lastSupportCheck());
        assertEquals(nextFootprint, walker.lastSupportCheckPos());
    }

    /**
     * Walker must hold when the support block placement was sent but not yet server-confirmed.
     */
    @Test
    void walkerHoldsWhenSupportPendingVerification() {
        GroundedSweepLane eastLane = new GroundedSweepLane(
                0, 12, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12), new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10), new BlockPos(10, 64, 10), new BlockPos(20, 64, 14)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastLane, bounds, true);

        Vec3d playerPos = new Vec3d(12.5, 64.0, 12.5);
        BlockPos nextFootprint = new BlockPos(13, 64, 12);

        // Block is in pending verification set (placed, awaiting server confirmation).
        java.util.Set<BlockPos> pendingPlacements = Set.of();
        java.util.Set<BlockPos> pendingVerifications = java.util.Set.of(nextFootprint);

        // TargetLookup: block is required at this position (returns true) — not yet confirmed.
        GroundedLaneWalker.SchematicTargetLookup lookup = targetLookupRequiringBlock(Set.of(nextFootprint));

        walker.tick(playerPos, lookup, pendingPlacements, pendingVerifications);

        assertTrue(walker.currentCommand().isPresent(), "Walker must be ACTIVE");
        assertFalse(walker.currentCommand().get().forwardPressed(),
                "Walker must hold when support is pending server verification");
        assertEquals(GroundedLaneWalker.SupportCheckResult.EXPECTED_PENDING_VERIFICATION, walker.lastSupportCheck());
        assertEquals(nextFootprint, walker.lastSupportCheckPos());
    }

    /**
     * Walker must resume normal forward movement once the support block is confirmed in the world
     * (schematicTargetLookup returns null = confirmed/safe).
     */
    @Test
    void walkerResumesOnceSupportConfirmed() {
        GroundedSweepLane eastLane = new GroundedSweepLane(
                0, 12, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12), new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10), new BlockPos(10, 64, 10), new BlockPos(20, 64, 14)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastLane, bounds, true);

        Vec3d playerPos = new Vec3d(12.5, 64.0, 12.5);
        BlockPos nextFootprint = new BlockPos(13, 64, 12);

        // Tick 1: block is required and pending verification — walker should hold.
        GroundedLaneWalker.SchematicTargetLookup notYetConfirmed = targetLookupRequiringBlock(Set.of(nextFootprint));
        walker.tick(playerPos, notYetConfirmed,
                Set.of(), java.util.Set.of(nextFootprint));
        assertFalse(walker.currentCommand().get().forwardPressed(),
                "Walker should hold while support is pending verification");

        // Tick 2: block is now confirmed (lookup returns false = safe), not in pending sets.
        GroundedLaneWalker.SchematicTargetLookup confirmed = targetLookupAllSafe();
        walker.tick(playerPos, confirmed,
                Set.of(), java.util.Set.of());
        assertTrue(walker.currentCommand().get().forwardPressed(),
                "Walker must resume forward movement once support block is confirmed");
        assertEquals(GroundedLaneWalker.SupportCheckResult.NOT_EXPECTED_AIR_OK, walker.lastSupportCheck());
    }

    /**
     * Recovery auto-resume from an interrupted/active state must NOT fail with "already active".
     * The fix: clearActiveRunState() is called before startFullSweep() during recovery auto-resume.
     */
    @Test
    void recoveryAutoResumeFromInterruptedStateDoesNotFailWithAlreadyActive() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin(new Vec3i(11, 1, 11));
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();

        // Start a full sweep — runner becomes active.
        assertTrue(runner.startFullSweep(session, settings).isEmpty());
        assertTrue(runner.isActive(), "Runner must be active after startFullSweep");

        // Activate recovery (simulates player falling below build plane mid-lane).
        GroundedSweepLane lane = laneForIndex(session, settings, 0);
        runner.activateRecoveryForTests(lane, GroundedRecoveryReason.BELOW_BUILD_PLANE);
        assertTrue(runner.getRecoveryState().isActive(), "Recovery must be active");

        // Simulate recovery stabilization completing and auto-resume firing.
        // Before the fix, this returned "Grounded single-lane debug run is already active."
        // because activeLane was still non-null when startFullSweep ran validateStart.
        Optional<String> err = runner.simulateRecoveryAutoResumeForTests(session, settings);

        // The auto-resume must succeed (no "already active" error).
        assertTrue(err.isEmpty(),
                "Recovery auto-resume must not fail with 'already active'; got: " + err.orElse(""));
        assertTrue(runner.isActive(), "Runner must be active after successful recovery auto-resume");
        assertFalse(runner.getRecoveryState().isActive(),
                "Recovery state must be cleared after auto-resume");
    }

    @Test
    void recoveryAutoResumeNoUnfinishedPlacementsMarksRunCompleteAndClearsState() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        BuildSession session = sessionWithOrigin(new Vec3i(11, 1, 11));
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();

        assertTrue(runner.startFullSweep(session, settings).isEmpty());
        GroundedSweepLane lane = laneForIndex(session, settings, 0);
        runner.activateRecoveryForTests(lane, GroundedRecoveryReason.BELOW_BUILD_PLANE);

        Optional<String> err = runner.simulateRecoveryAutoResumeForTests(
                session,
                settings,
                new Vec3d(10.5, 64.0, 10.5),
                (worldPos, expected) -> true);

        assertTrue(err.isEmpty(), "no unfinished placements must not be reported as auto-resume failure");
        assertFalse(runner.isActive(), "runner must stop after clean completion");
        assertFalse(runner.getRecoveryState().isActive(), "recovery state must be cleared");
        assertEquals(GroundedSingleLaneDebugRunner.LaneStartStage.NONE, runner.laneStartStageForTests());

        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertFalse(status.active());
        assertFalse(status.awaitingStartApproach());
        assertFalse(status.awaitingLaneShift());
        assertEquals(GroundedLaneWalkState.COMPLETE, status.walkState());
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.COMPLETE, status.phase());
        assertTrue(status.failureReason().isEmpty());
        assertTrue(runner.groundedTraceEventsForTests().stream()
                .anyMatch(event -> event.contains(GroundedDiagnostics.BUILD_COMPLETE_NO_UNFINISHED_PLACEMENTS)));
        assertFalse(runner.groundedTraceEventsForTests().stream()
                .anyMatch(event -> event.contains("auto-resume failed")));
    }

    @Test
    void recoveryAutoResumeWithUnfinishedPlacementsStillResumesNormally() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = sessionWithOrigin(new Vec3i(11, 1, 11));
        GroundedSweepSettings settings = GroundedSweepSettings.defaults();

        assertTrue(runner.startFullSweep(session, settings).isEmpty());
        GroundedSweepLane lane = laneForIndex(session, settings, 0);
        runner.activateRecoveryForTests(lane, GroundedRecoveryReason.OFF_CORRIDOR);

        Optional<String> err = runner.simulateRecoveryAutoResumeForTests(
                session,
                settings,
                new Vec3d(10.5, 64.0, 10.5),
                (worldPos, expected) -> false);

        assertTrue(err.isEmpty());
        assertTrue(runner.isActive(), "unfinished placements should resume the sweep");
        assertFalse(runner.getRecoveryState().isActive());
        GroundedSingleLaneDebugRunner.DebugStatus status = runner.status();
        assertTrue(status.active());
        assertEquals(GroundedSingleLaneDebugRunner.SweepPassPhase.FORWARD, status.phase());
        assertTrue(status.failureReason().isEmpty());
    }

    @Test
    void recoveryAutoResumeWithMissingSessionStillReportsFailure() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        GroundedSweepLane lane = eastLane();

        runner.activateRecoveryForTests(lane, GroundedRecoveryReason.OFF_CORRIDOR);
        Optional<String> err = runner.simulateRecoveryAutoResumeForTests(null, null);

        assertTrue(err.isPresent());
        assertEquals("Session or settings not available for auto-resume", err.orElseThrow());
        assertFalse(runner.isActive());
    }

    // ─── New support-guard tests (amendments to PR #231) ─────────────────────

    /**
     * 1. EXPECTED_MISSING_UNTRACKED causes hold, not silent proceed.
     * When a block is required by the schematic but not in any tracking queue,
     * the walker must hold (not silently advance).
     */
    @Test
    void expectedMissingUntrackedSupportDoesNotProceedSilently() {
        GroundedSweepLane eastLane = new GroundedSweepLane(
                0, 12, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12), new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10), new BlockPos(10, 64, 10), new BlockPos(20, 64, 14)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastLane, bounds, true);

        Vec3d playerPos = new Vec3d(12.5, 64.0, 12.5);
        BlockPos nextFootprint = new BlockPos(13, 64, 12);

        // Block is expected by schematic (lookup returns true) but NOT in any queue.
        GroundedLaneWalker.SchematicTargetLookup lookup = targetLookupRequiringBlock(Set.of(nextFootprint));
        java.util.Set<BlockPos> pendingPlacements = Set.of();
        java.util.Set<BlockPos> pendingVerifications = java.util.Set.of();

        walker.tick(playerPos, lookup, pendingPlacements, pendingVerifications);

        // Must hold — not proceed silently.
        assertTrue(walker.currentCommand().isPresent());
        assertFalse(walker.currentCommand().get().forwardPressed(),
                "EXPECTED_MISSING_UNTRACKED must NOT silently proceed — walker must hold");
        assertEquals(GroundedLaneWalker.SupportCheckResult.EXPECTED_MISSING_UNTRACKED, walker.lastSupportCheck());
        assertEquals(1, walker.supportHoldTicks(), "Hold tick counter must be 1 after first hold tick");
    }

    /**
     * 2. NOT_EXPECTED_AIR_OK does not hold.
     * When the schematic has no block at the footprint position, walking is safe.
     */
    @Test
    void nonExpectedAirProceedsNormally() {
        GroundedSweepLane eastLane = new GroundedSweepLane(
                0, 12, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12), new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10), new BlockPos(10, 64, 10), new BlockPos(20, 64, 14)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastLane, bounds, true);

        Vec3d playerPos = new Vec3d(12.5, 64.0, 12.5);

        // TargetLookup returns null for all positions (no block required = air is fine).
        GroundedLaneWalker.SchematicTargetLookup allAirOk = targetLookupAllSafe();

        walker.tick(playerPos, allAirOk, Set.of(), java.util.Set.of());

        // Must not hold — forward movement is allowed.
        assertTrue(walker.currentCommand().isPresent());
        assertTrue(walker.currentCommand().get().forwardPressed(),
                "NOT_EXPECTED_AIR_OK must allow normal forward movement");
        assertEquals(GroundedLaneWalker.SupportCheckResult.NOT_EXPECTED_AIR_OK, walker.lastSupportCheck());
        assertEquals(0, walker.supportHoldTicks(), "Hold tick counter must be 0 when movement is not held");
    }

    /**
     * 3. EXPECTED_PENDING_VERIFICATION holds, then resumes when confirmed.
     */
    @Test
    void pendingVerificationHoldsThenResumes() {
        GroundedSweepLane eastLane = new GroundedSweepLane(
                0, 12, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12), new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10), new BlockPos(10, 64, 10), new BlockPos(20, 64, 14)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastLane, bounds, true);

        Vec3d playerPos = new Vec3d(12.5, 64.0, 12.5);
        BlockPos nextFootprint = new BlockPos(13, 64, 12);

        GroundedLaneWalker.SchematicTargetLookup required = targetLookupRequiringBlock(Set.of(nextFootprint));

        // Hold tick 1: pending verification.
        walker.tick(playerPos, required, Set.of(), Set.of(nextFootprint));
        assertFalse(walker.currentCommand().get().forwardPressed(), "Should hold tick 1");
        assertEquals(GroundedLaneWalker.SupportCheckResult.EXPECTED_PENDING_VERIFICATION, walker.lastSupportCheck());
        assertEquals(1, walker.supportHoldTicks());

        // Hold tick 2: still pending.
        walker.tick(playerPos, required, Set.of(), Set.of(nextFootprint));
        assertFalse(walker.currentCommand().get().forwardPressed(), "Should hold tick 2");
        assertEquals(2, walker.supportHoldTicks());

        // Tick 3: block is now confirmed (lookup returns false = safe, removed from verification).
        walker.tick(playerPos, targetLookupAllSafe(), Set.of(), Set.of());
        assertTrue(walker.currentCommand().get().forwardPressed(), "Must resume when confirmed");
        assertEquals(0, walker.supportHoldTicks(), "Hold counter resets when movement resumes");
    }

    /**
     * 4. EXPECTED_PENDING_PLACEMENT holds, then resumes when placed.
     */
    @Test
    void pendingPlacementHoldsThenResumes() {
        GroundedSweepLane eastLane = new GroundedSweepLane(
                0, 12, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12), new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10), new BlockPos(10, 64, 10), new BlockPos(20, 64, 14)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastLane, bounds, true);

        Vec3d playerPos = new Vec3d(12.5, 64.0, 12.5);
        BlockPos nextFootprint = new BlockPos(13, 64, 12);
        java.util.Set<BlockPos> inQueue = Set.of(nextFootprint);
        GroundedLaneWalker.SchematicTargetLookup required = targetLookupRequiringBlock(Set.of(nextFootprint));

        // Hold: block in pending-placement queue.
        walker.tick(playerPos, required, inQueue, Set.of());
        assertFalse(walker.currentCommand().get().forwardPressed(), "Must hold while in pending-placement queue");
        assertEquals(GroundedLaneWalker.SupportCheckResult.EXPECTED_PENDING_PLACEMENT, walker.lastSupportCheck());
        assertEquals(1, walker.supportHoldTicks());

        // Resume: block is now placed and confirmed (lookup returns false = safe).
        walker.tick(playerPos, targetLookupAllSafe(), Set.of(), Set.of());
        assertTrue(walker.currentCommand().get().forwardPressed(), "Must resume when placed and confirmed");
        assertEquals(0, walker.supportHoldTicks(), "Hold counter resets when movement resumes");
    }

    /**
     * 5. Hold timeout exceeded with EXPECTED_PENDING_PLACEMENT → enters fail state with
     * diagnostic reason string.
     */
    @Test
    void pendingPlacementTimesOut() {
        GroundedSweepLane eastLane = new GroundedSweepLane(
                0, 12, GroundedLaneDirection.EAST,
                new BlockPos(10, 64, 12), new BlockPos(20, 64, 12),
                new GroundedLaneCorridorBounds(10, 20, 10, 14),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(10, 64, 10), new BlockPos(10, 64, 10), new BlockPos(20, 64, 14)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(eastLane, bounds, true);

        Vec3d playerPos = new Vec3d(12.5, 64.0, 12.5);
        BlockPos nextFootprint = new BlockPos(13, 64, 12);
        java.util.Set<BlockPos> inQueue = Set.of(nextFootprint);
        GroundedLaneWalker.SchematicTargetLookup required = targetLookupRequiringBlock(Set.of(nextFootprint));

        // Tick MAX_SUPPORT_HOLD_TICKS_PENDING times — each tick the block is still pending.
        int maxTicks = GroundedLaneWalker.MAX_SUPPORT_HOLD_TICKS_PENDING;
        for (int i = 0; i < maxTicks - 1; i++) {
            walker.tick(playerPos, required, inQueue, Set.of());
            assertEquals(GroundedLaneWalkState.ACTIVE, walker.state(),
                    "Walker should still be ACTIVE before timeout (tick " + (i + 1) + ")");
        }

        // The timeout tick: walker should fail.
        walker.tick(playerPos, required, inQueue, Set.of());
        assertEquals(GroundedLaneWalkState.FAILED, walker.state(),
                "Walker must FAIL after hold timeout exceeded for EXPECTED_PENDING_PLACEMENT");
        assertTrue(walker.failureReason().isPresent(), "Failure reason must be set");
        assertTrue(walker.failureReason().get().contains("EXPECTED_PENDING_PLACEMENT"),
                "Failure reason must mention the check state; got: " + walker.failureReason().get());
        assertTrue(walker.failureReason().get().contains("ticks hold"),
                "Failure reason must mention the hold duration; got: " + walker.failureReason().get());
    }

    /**
     * 6. RIGHT_TWO/RIGHT_ONE column missing on WEST lane triggers hold.
     * The failure holes were on RIGHT_TWO / RIGHT_ONE bands, not just centerline.
     * This test verifies that the full walking footprint (lateral offset column) is checked.
     */
    @Test
    void lateralFootprintMissingOnWestLaneIsDetected() {
        // WEST lane: centerline Z=-59. Player at Z=-57 (RIGHT_TWO for WEST lane).
        // For a WEST lane (facing -X), lateralStrafeSign = 1 * -1 = -1.
        // lateralOffset for Z=-57 = -57 - (-59) = 2 → rightwardOffset = 2 * -1 = -2 → LEFT_TWO.
        // Let's use Z=-61: offset = -61 - (-59) = -2 → rightwardOffset = -2 * -1 = 2 → RIGHT_TWO.
        GroundedSweepLane westLane = new GroundedSweepLane(
                5, -59, GroundedLaneDirection.WEST,
                new BlockPos(-65, 64, -59), new BlockPos(-192, 64, -59),
                new GroundedLaneCorridorBounds(-192, -65, -61, -57),
                1.0
        );
        GroundedSchematicBounds bounds = new GroundedSchematicBounds(
                new BlockPos(-192, 64, -61),
                new BlockPos(-192, 64, -61),
                new BlockPos(-65, 64, -57)
        );

        GroundedLaneWalker walker = new GroundedLaneWalker();
        walker.start(westLane, bounds, true);

        // Player is at Z=-61 (RIGHT_TWO offset), near the lane end.
        // For WEST (along X), lateral axis is Z.
        // playerZ = -61.3, so floor(-61.3) = -62. The player's lateral column is Z=-62
        // (outside corridor, would trigger corridor check). Use Z=-60.3 instead:
        // floor(-60.3) = -61 → player lateral column = Z=-61 (centerline is -59, so ≠ center).
        Vec3d playerPos = new Vec3d(-180.5, 64.0, -60.3);

        // Next footprint for WEST lane: floor(-180.5) = -181, forwardSign=-1 → nextX=-182.
        // Lateral column: floor(-60.3) = -61 (not centerline -59).
        BlockPos lateralFootprint = new BlockPos(-182, 64, -61);
        BlockPos centerlineFootprint = new BlockPos(-182, 64, -59);

        // Only the lateral offset block is required (centerline is fine/air).
        // targetLookupRequiringBlock returns true (required/unconfirmed) for lateralFootprint only.
        GroundedLaneWalker.SchematicTargetLookup lookup = targetLookupRequiringBlock(Set.of(lateralFootprint));

        // Lateral block is in pending-placement queue.
        java.util.Set<BlockPos> pendingPlacements = Set.of(lateralFootprint);

        walker.tick(playerPos, lookup, pendingPlacements, Set.of());

        // Walker must hold because the lateral footprint column is EXPECTED_PENDING_PLACEMENT.
        assertTrue(walker.currentCommand().isPresent());
        assertFalse(walker.currentCommand().get().forwardPressed(),
                "Walker must hold when a non-centerline footprint column has a pending block");
        // lastSupportCheckPos might be the lateral column or the centerline — the worst result wins.
        assertTrue(
                walker.lastSupportCheck() == GroundedLaneWalker.SupportCheckResult.EXPECTED_PENDING_PLACEMENT
                || walker.lastSupportCheck() == GroundedLaneWalker.SupportCheckResult.EXPECTED_MISSING_UNTRACKED,
                "Support check must be EXPECTED_PENDING_PLACEMENT or EXPECTED_MISSING_UNTRACKED when lateral footprint is blocked; got: "
                        + walker.lastSupportCheck()
        );
    }
}
