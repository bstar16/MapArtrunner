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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void readyForLaneStartRequiresInsideCorridorEvenWhenNearTarget() {
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
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());

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
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
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
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
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
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
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
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
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
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());
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
        assertTrue(runner.startFullSweep(sessionWithOrigin(new Vec3i(5, 1, 11)), GroundedSweepSettings.defaults()).isEmpty());

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

        @Override
        public void applyOnPlaneConstraints() {
            // No-op for tests
        }

        @Override
        public void clearOnPlaneConstraints() {
            // No-op for tests
        }
    }
}
