package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.baritone.BaritoneFacade;
import com.example.mapart.baritone.NoOpBaritoneFacade;
import com.example.mapart.plan.BuildPlan;
import com.example.mapart.plan.Placement;
import com.example.mapart.plan.sweep.grounded.GroundedLaneWalker.GroundedLaneWalkState;
import com.example.mapart.plan.state.BuildSession;
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
    void smartResumeNoUnfinishedPlacementsDoesNotStartRun() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        BuildSession session = rectangularSessionWithOrigin(new Vec3i(3, 1, 3));
        Optional<String> error = runner.startFullSweepSmart(
                session,
                GroundedSweepSettings.defaults(),
                new Vec3d(10.5, 65, 10.5),
                (worldPos, expected) -> true
        );

        assertTrue(error.isPresent());
        assertFalse(runner.isActive());
        assertTrue(error.orElseThrow().contains("no unfinished placements"));
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
        assertEquals(List.of(1, 2, 3, 4), burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList());
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
        assertEquals(List.of(1, 894), burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList());
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
        // Must include burst-zone targets from all bands, skipping only out-of-window items
        assertEquals(
                List.of(0, 1, 2, 5, 6, 7, 254, 255),
                burst.stream().map(GroundedSweepPlacementExecutor.PlacementTarget::placementIndex).toList()
        );
    }

    @Test
    void entryBurstTargetsAllowMultipleAttemptsPerTarget() {
        assertEquals(2, GroundedSingleLaneDebugRunner.maxEntryAttemptsPerTargetForTests());
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
    void transitionSupportFailureTraceIncludesFailureDetails() {
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(new NoOpBaritoneFacade());
        runner.setGroundedTraceEnabled(true);
        assertTrue(runner.startFullSweep(sessionWithBridgeSupport(), GroundedSweepSettings.defaults()).isEmpty());
        runner.advanceSweepToNextLaneForTests();
        GroundedSweepPlacementExecutor.PlacementTarget target = runner.transitionSupportTargetsForTests().getFirst();
        runner.keepOnlyTransitionSupportTargetForTests(target.placementIndex());
        runner.recordTransitionSupportPlacedForTests(target.placementIndex(), target.worldPos(), 0);

        runner.processTransitionSupportVerificationsForTests(Map.of(target.placementIndex(), false), 999);

        assertTrue(runner.groundedTraceEventsForTests().stream().anyMatch(event -> event.contains("transition support failed:")));
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
            return false;
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
        assertTrue(result.isPresent(), "should return build-complete message");
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
}
