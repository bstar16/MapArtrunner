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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void startApproachTargetsStandingPositionOneBlockAboveBuildPlane() {
        RecordingBaritoneFacade baritone = new RecordingBaritoneFacade();
        GroundedSingleLaneDebugRunner runner = new GroundedSingleLaneDebugRunner(baritone);
        BuildSession session = sessionWithOrigin();

        assertTrue(runner.start(session, 0, GroundedSweepSettings.defaults()).isEmpty());
        runner.issueStartApproachIfNeeded();

        assertEquals(new BlockPos(10, 65, 12), baritone.lastGoToTarget);
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

        BlockPos approachTarget = GroundedSingleLaneDebugRunner.approachTargetForLaneStart(lane, bounds);

        assertEquals(new BlockPos(10, 65, 12), approachTarget);
        assertEquals(new BlockPos(10, 64, 12), lane.startPoint());
    }

    @Test
    void nearStartDetectionUsesStandingStartTargetCoordinates() {
        BlockPos standingStart = new BlockPos(10, 65, 12);
        assertTrue(GroundedSingleLaneDebugRunner.isNearLaneStart(new Vec3d(10.5, 64.0, 12.5), standingStart));
        assertFalse(GroundedSingleLaneDebugRunner.isNearLaneStart(new Vec3d(13.0, 64.0, 12.5), standingStart));
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

    private static BuildSession sessionWithOrigin() {
        BuildPlan plan = buildPlan(List.of(new Placement(new BlockPos(0, 0, 0), null)));

        BuildSession session = new BuildSession(plan);
        session.setOrigin(new BlockPos(10, 64, 10));
        return session;
    }

    private static BuildPlan buildPlan(List<Placement> placements) {
        return new BuildPlan(
                "test",
                Path.of("plan.schem"),
                new Vec3i(5, 1, 5),
                placements,
                Map.of(),
                List.of()
        );
    }

    private static final class RecordingBaritoneFacade implements BaritoneFacade {
        private BlockPos lastGoToTarget;

        @Override
        public CommandResult goTo(BlockPos target) {
            lastGoToTarget = target;
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
}
