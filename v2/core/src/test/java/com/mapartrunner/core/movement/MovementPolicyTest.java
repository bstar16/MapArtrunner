package com.mapartrunner.core.movement;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.BlockSpec;
import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.model.BuildPlan.GridSize;
import com.mapartrunner.core.model.BuildPlan.Target;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementLedger;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.traversal.LanePlanner;
import com.mapartrunner.core.traversal.LanePlanner.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MovementPolicyTest {
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of("axis", "x"));
    private static final BlockSpec WRONG = new BlockSpec("minecraft:stone", Map.of("axis", "z"));
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private static final SupportObservation UNKNOWN = new SupportObservation(Map.of());
    private final MovementPolicy policy = new MovementPolicy();

    @ParameterizedTest
    @CsvSource({"0,2.5,true,false,NONE", "1,2.5,false,true,NONE", "0.5,3,false,false,LEFT",
            "0.5,2,false,false,RIGHT", "0,3,true,false,NONE"})
    void entryCorrectsOneProgressFirstAxis(double x, double z, boolean forward, boolean back, Strafe strafe) {
        var result = policy.decide(new PlayerObservation(x, 1, z, -90), fresh(lane(Direction.EAST), Stage.ENTER_POSITION), UNKNOWN);
        assertEquals(Status.ENTERING, result.status());
        assertControls(result, forward, back, strafe, false);
        assertEquals(1, result.nextStageElapsedTicks());
        assertEquals(1, result.nextEntryElapsedTicks());
    }

    @ParameterizedTest
    @CsvSource({"EAST,0,2.5,true,false,NONE", "EAST,1,2.5,false,true,NONE",
            "WEST,11,2.5,true,false,NONE", "WEST,10,2.5,false,true,NONE",
            "SOUTH,2.5,0,true,false,NONE", "SOUTH,2.5,1,false,true,NONE",
            "NORTH,2.5,11,true,false,NONE", "NORTH,2.5,10,false,true,NONE",
            "EAST,0.5,3,false,false,LEFT", "EAST,0.5,2,false,false,RIGHT",
            "WEST,10.5,3,false,false,RIGHT", "WEST,10.5,2,false,false,LEFT",
            "SOUTH,3,0.5,false,false,RIGHT", "SOUTH,2,0.5,false,false,LEFT",
            "NORTH,3,10.5,false,false,LEFT", "NORTH,2,10.5,false,false,RIGHT"})
    void entryCommandsUseLaneDirection(Direction direction, double x, double z, boolean forward, boolean back, Strafe strafe) {
        var result = policy.decide(new PlayerObservation(x, 1, z, direction.yawDegrees()), fresh(lane(direction), Stage.ENTER_POSITION), UNKNOWN);
        assertControls(result, forward, back, strafe, false);
        assertEquals(direction.yawDegrees(), result.desiredYaw());
    }

    @ParameterizedTest
    @CsvSource({"0.25,2.25", "0.25,2.75", "0.75,2.25", "0.75,2.75", "0.5,2.5"})
    void bothEntryCoordinatesHaveInclusiveQuarterBlockTolerance(double x, double z) {
        var result = policy.decide(new PlayerObservation(x, 1, z, 0), fresh(lane(Direction.EAST), Stage.ENTER_POSITION), UNKNOWN);
        assertEquals(Status.ALIGNING_YAW, result.status());
        assertEquals(Stage.ENTER_YAW, result.nextStage());
        assertEquals(0, result.nextStageElapsedTicks());
        assertEquals(1, result.nextEntryElapsedTicks());
        noControls(result);
    }

    @Test
    void justOutsideEitherEntryToleranceStillRequiresPosition() {
        for (PlayerObservation player : List.of(new PlayerObservation(Math.nextDown(.25), 1, 2.5, -90),
                new PlayerObservation(Math.nextUp(.75), 1, 2.5, -90),
                new PlayerObservation(.5, 1, Math.nextDown(2.25), -90),
                new PlayerObservation(.5, 1, Math.nextUp(2.75), -90))) {
            assertEquals(Status.ENTERING, policy.decide(player, fresh(lane(Direction.EAST), Stage.ENTER_POSITION), UNKNOWN).status());
        }
    }

    @Test
    void entryYawSuppressesCandidateButStillChecksSupport() {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(0, 0, 2));
        var result = policy.decide(new PlayerObservation(0, 1, 2.5, 0), fresh(lane, Stage.ENTER_POSITION), UNKNOWN);
        assertEquals(Status.ENTERING, result.status());
        assertEquals(1, result.nextSupportHoldTicks());
        noControls(result);
        var safe = policy.decide(new PlayerObservation(0, 1, 2.5, 0), fresh(lane, Stage.ENTER_POSITION), observed(lane, STONE));
        noControls(safe);
        assertEquals(0, safe.nextSupportHoldTicks());
    }

    @ParameterizedTest
    @CsvSource({"EAST,0,2.5,0,2", "EAST,0,2.5,1,2", "EAST,1,2.5,0,2",
            "WEST,11,2.5,10,2", "WEST,10,2.5,11,2",
            "SOUTH,2.5,0,2,1", "SOUTH,2.5,1,2,0",
            "NORTH,2.5,11,2,10", "NORTH,2.5,10,2,11",
            "EAST,0.5,3,0,2", "EAST,0.5,2,0,3",
            "WEST,10.5,3,10,2", "WEST,10.5,2,10,3",
            "SOUTH,3,0.5,2,0", "SOUTH,2,0.5,3,0",
            "NORTH,3,10.5,2,10", "NORTH,2,10.5,3,10"})
    void entryGatesCurrentAndActualCommandNeighborIncludingBackwardAndBothStrafes(Direction direction, double x, double z, int targetX, int targetZ) {
        Lane lane = withTargets(lane(direction), new GridPos(targetX, 0, targetZ));
        var result = policy.decide(new PlayerObservation(x, 1, z, direction.yawDegrees()), fresh(lane, Stage.ENTER_POSITION), UNKNOWN);
        assertEquals(Status.HOLDING_SUPPORT, result.status());
        assertEquals(1, result.nextSupportHoldTicks());
        noControls(result);
        var ready = policy.decide(new PlayerObservation(x, 1, z, direction.yawDegrees()), fresh(lane, Stage.ENTER_POSITION), observed(lane, STONE));
        assertEquals(Status.ENTERING, ready.status());
        assertTrue(ready.forward() || ready.back() || ready.strafe() != Strafe.NONE);
    }

    @Test
    void entryFloorsNegativeCoordinatesAndChecksRequiredTargetsOutsideCorridor() {
        var required = new GridPos(-1, 0, 2);
        Lane lane = withTargets(lane(Direction.EAST), required);
        var result = policy.decide(new PlayerObservation(-.4, 1, 2.5, -90), fresh(lane, Stage.ENTER_POSITION), UNKNOWN);
        assertEquals(Status.HOLDING_SUPPORT, result.status());
        assertEquals(1, result.nextSupportHoldTicks());
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void requiredEntrySupportRequiresObservedFullState(Evidence evidence) {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(0, 0, 2));
        var samples = new SupportObservation(Map.of(new GridPos(0, 0, 2), sample(evidence, STONE)));
        var result = policy.decide(new PlayerObservation(0, 1, 2.5, -90), fresh(lane, Stage.ENTER_POSITION), samples);
        assertEquals(evidence == Evidence.OBSERVED ? Status.ENTERING : Status.HOLDING_SUPPORT, result.status());
        assertEquals(Status.HOLDING_SUPPORT, policy.decide(new PlayerObservation(0, 1, 2.5, -90), fresh(lane, Stage.ENTER_POSITION), observed(lane, WRONG)).status());
    }

    @Test
    void noTranslationStageChangesPreserveSupportWithoutInventingEvaluation() {
        Lane lane = lane(Direction.EAST);
        var position = context(lane, Stage.ENTER_POSITION, 40, 41, 0, 70, 0, Set.of(), true);
        var centered = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), position, UNKNOWN);
        assertEquals(Status.ALIGNING_YAW, centered.status());
        assertEquals(70, centered.nextSupportHoldTicks());
        noControls(centered);
        var yaw = context(lane, Stage.ENTER_YAW, 5, 42, 1, 70, 0, Set.of(), true);
        var drifted = policy.decide(new PlayerObservation(1, 1, 2.5, -90), yaw, UNKNOWN);
        assertEquals(Stage.ENTER_POSITION, drifted.nextStage());
        assertEquals(43, drifted.nextEntryElapsedTicks());
        assertEquals(0, drifted.nextStageElapsedTicks());
        assertEquals(0, drifted.nextYawAlignedTicks());
        assertEquals(70, drifted.nextSupportHoldTicks());
        noControls(drifted);
    }

    @Test
    void positionAllowsFortyUnmetDecisionsThenFailsFortyFirst() {
        LaneEntry entry = fresh(lane(Direction.EAST), Stage.ENTER_POSITION);
        for (int i = 1; i <= 40; i++) {
            var result = policy.decide(new PlayerObservation(0, 1, 2.5, -90), entry, UNKNOWN);
            assertEquals(Status.ENTERING, result.status());
            assertEquals(i, result.nextStageElapsedTicks());
            entry = advance(entry, result);
        }
        terminal(policy.decide(new PlayerObservation(0, 1, 2.5, -90), entry, UNKNOWN), Failure.ENTRY_TIMEOUT);
    }

    @Test
    void yawRequiresTwoConsecutiveSamplesAndResetsAfterBadYaw() {
        var entry = fresh(lane(Direction.EAST), Stage.ENTER_YAW);
        var first = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), entry, UNKNOWN);
        assertEquals(Status.ALIGNING_YAW, first.status());
        assertEquals(1, first.nextYawAlignedTicks());
        var bad = policy.decide(new PlayerObservation(.5, 1, 2.5, -80), advance(entry, first), UNKNOWN);
        assertEquals(0, bad.nextYawAlignedTicks());
        var again = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), advance(entry, bad), UNKNOWN);
        var ready = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), advance(entry, again), UNKNOWN);
        assertEquals(Status.READY_TO_WALK, ready.status());
        assertEquals(Stage.WALK, ready.nextStage());
        clearedCounters(ready);
        noControls(ready);
    }

    @ParameterizedTest
    @CsvSource({"-91.5,true", "-88.5,true", "268.5,true", "270,true", "630,true", "-91.50001,false", "-88.49999,false"})
    void yawComparisonIsWrappedAndInclusive(double yaw, boolean ready) {
        var entry = context(lane(Direction.EAST), Stage.ENTER_YAW, 1, 2, 1, 17, 0, Set.of(), true);
        var result = policy.decide(new PlayerObservation(.5, 1, 2.5, yaw), entry, UNKNOWN);
        assertEquals(ready ? Status.READY_TO_WALK : Status.ALIGNING_YAW, result.status());
        assertEquals(17, result.nextSupportHoldTicks());
        noControls(result);
        if (ready) assertEquals(0, result.nextEntryElapsedTicks());
    }

    @Test
    void northAcceptsNegative180AndVeryLargeFiniteAngles() {
        Lane lane = lane(Direction.NORTH);
        var entry = context(lane, Stage.ENTER_YAW, 1, 2, 1, 0, 0, Set.of(), false);
        assertEquals(Status.READY_TO_WALK, policy.decide(new PlayerObservation(2.5, 1, 10.5, -180), entry, UNKNOWN).status());
        assertNotNull(policy.decide(new PlayerObservation(2.5, 1, 10.5, Double.MAX_VALUE), entry, UNKNOWN));
    }

    @Test
    void yawAllowsTenUnresolvedDecisionsAndReadinessOnEleventh() {
        Lane lane = lane(Direction.EAST);
        LaneEntry entry = fresh(lane, Stage.ENTER_YAW);
        for (int i = 1; i <= 10; i++) {
            var result = policy.decide(new PlayerObservation(.5, 1, 2.5, 0), entry, UNKNOWN);
            assertEquals(Status.ALIGNING_YAW, result.status());
            assertEquals(i, result.nextStageElapsedTicks());
            entry = advance(entry, result);
        }
        terminal(policy.decide(new PlayerObservation(.5, 1, 2.5, 0), entry, UNKNOWN), Failure.YAW_TIMEOUT);
        var deadline = context(lane, Stage.ENTER_YAW, 10, 51, 1, 5, 0, Set.of(), false);
        var ready = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), deadline, UNKNOWN);
        assertEquals(Status.READY_TO_WALK, ready.status());
        assertEquals(5, ready.nextSupportHoldTicks());
    }

    @Test
    void repeatedDriftCannotRenewTotalEntryBudgetAndPauseDoesNotAdvanceIt() {
        Lane lane = lane(Direction.EAST);
        LaneEntry entry = fresh(lane, Stage.ENTER_POSITION);
        for (int i = 1; i <= 52; i++) {
            var player = new PlayerObservation(entry.stage() == Stage.ENTER_POSITION ? .5 : 1, 1, 2.5, -90);
            var result = policy.decide(player, entry, UNKNOWN);
            assertEquals(i, result.nextEntryElapsedTicks());
            assertEquals(0, result.nextStageElapsedTicks());
            assertEquals(result, policy.decide(player, entry, UNKNOWN)); // Retaining context during pause consumes no decisions.
            entry = advance(entry, result);
        }
        terminal(policy.decide(new PlayerObservation(.5, 1, 2.5, -90), entry, UNKNOWN), Failure.ENTRY_TIMEOUT);
        var expired = context(lane, Stage.ENTER_YAW, 1, 52, 1, 0, 0, Set.of(), false);
        terminal(policy.decide(new PlayerObservation(.5, 1, 2.5, -90), expired, UNKNOWN), Failure.ENTRY_TIMEOUT);
    }

    @Test
    void entrySupportCanExpireBeforeLocalTimeoutAndTotalEntryCanPreemptUnknownSupport() {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(0, 0, 2));
        var due = context(lane, Stage.ENTER_POSITION, 40, 51, 0, 39, 0, Set.of(), false);
        terminal(policy.decide(new PlayerObservation(0, 1, 2.5, -90), due, observed(lane, AIR)), Failure.SUPPORT_TIMEOUT);
        var expired = context(lane, Stage.ENTER_POSITION, 0, 52, 0, 79, 0, Set.of(), false);
        terminal(policy.decide(new PlayerObservation(0, 1, 2.5, 0), expired, UNKNOWN), Failure.SUPPORT_TIMEOUT);
        terminal(policy.decide(new PlayerObservation(0, 1, 2.5, -90), context(lane, Stage.ENTER_POSITION, 40, 40, 0, 40, 0, Set.of(), false), UNKNOWN), Failure.ENTRY_TIMEOUT);
    }

    @ParameterizedTest
    @CsvSource({"EAST,5.5,2.7,LEFT", "EAST,5.5,2.3,RIGHT", "WEST,5.5,2.7,RIGHT", "WEST,5.5,2.3,LEFT",
            "SOUTH,2.7,5.5,RIGHT", "SOUTH,2.3,5.5,LEFT", "NORTH,2.7,5.5,LEFT", "NORTH,2.3,5.5,RIGHT"})
    void walkingNeverReturnsToStartAndUsesRelativeStrafing(Direction direction, double x, double z, Strafe strafe) {
        Lane lane = lane(direction);
        for (boolean sprint : List.of(true, false)) {
            var result = policy.decide(new PlayerObservation(x, 1, z, direction.yawDegrees()), context(lane, Stage.WALK, 0, 0, 0, 0, 0, Set.of(), sprint), UNKNOWN);
            assertEquals(Status.WALKING, result.status());
            assertControls(result, true, false, strafe, sprint);
            assertEquals(direction.yawDegrees(), result.desiredYaw());
        }
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    void strafeDeadbandHasExactInclusiveBoundaries(Direction direction) {
        Lane lane = lane(direction);
        for (double lateral : new double[]{2.5, 2.42, 2.58}) {
            var result = policy.decide(player(direction, 5.5, lateral, direction.yawDegrees()), fresh(lane, Stage.WALK), UNKNOWN);
            assertEquals(Strafe.NONE, result.strafe());
        }
        var high = policy.decide(player(direction, 5.5, Math.nextUp(2.58), direction.yawDegrees()), fresh(lane, Stage.WALK), UNKNOWN);
        var low = policy.decide(player(direction, 5.5, Math.nextDown(2.42), direction.yawDegrees()), fresh(lane, Stage.WALK), UNKNOWN);
        assertEquals(direction == Direction.EAST || direction == Direction.NORTH ? Strafe.LEFT : Strafe.RIGHT, high.strafe());
        assertEquals(direction == Direction.EAST || direction == Direction.NORTH ? Strafe.RIGHT : Strafe.LEFT, low.strafe());
    }

    @ParameterizedTest
    @CsvSource({"EAST,9.5,2.5", "EAST,11,2.5", "WEST,1.5,2.5", "WEST,-0.5,2.5",
            "SOUTH,2.5,9.5", "SOUTH,2.5,11", "NORTH,2.5,1.5", "NORTH,2.5,-0.5"})
    void endpointAndOvershootCompleteBeforeSupportAndYaw(Direction direction, double x, double z) {
        Lane lane = withTargets(lane(direction), new GridPos(5, 0, 2));
        var result = policy.decide(new PlayerObservation(x, 0, z, 45), context(lane, Stage.WALK, 0, 0, 0, 79, 19, Set.of(), true), UNKNOWN);
        assertEquals(Status.COMPLETE, result.status());
        assertEquals(Failure.NONE, result.failure());
        noControls(result);
        clearedCounters(result);
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void boundsPrecedeStageReadinessAndCompletionAndHaveInclusiveEnvelope(Stage stage) {
        Lane lane = lane(Direction.EAST);
        for (PlayerObservation player : List.of(new PlayerObservation(-.5, 0, 2.5, -90), new PlayerObservation(11.5, 0, 2.5, -90),
                new PlayerObservation(.5, 0, -.5, -90), new PlayerObservation(.5, 0, 5.5, -90))) {
            assertNotEquals(Status.FAILED, policy.decide(player, fresh(lane, stage), UNKNOWN).status());
        }
        for (PlayerObservation player : List.of(new PlayerObservation(Math.nextDown(-.5), 1, 2.5, -90),
                new PlayerObservation(Math.nextUp(11.5), 1, 2.5, -90), new PlayerObservation(.5, 1, Math.nextDown(-.5), -90),
                new PlayerObservation(.5, 1, Math.nextUp(5.5), -90))) {
            terminal(policy.decide(player, fresh(lane, stage), UNKNOWN), Failure.OUTSIDE_CORRIDOR);
        }
        terminal(policy.decide(new PlayerObservation(11, Math.nextDown(0.0), 2.5, -90), fresh(lane, stage), UNKNOWN), Failure.BELOW_BUILD_PLANE);
    }

    @Test
    void widthOneMayCompleteWithoutMovementButDoesNotClaimBuildSuccess() {
        Lane lane = new Lane(0, Direction.EAST, new GridPos(0, 0, 0), new GridPos(0, 0, 0), new Corridor(0, 0, 0, 0), new ExpectedFloor(Map.of()));
        assertEquals(Status.COMPLETE, policy.decide(new PlayerObservation(.5, 1, .5, 0), fresh(lane, Stage.WALK), UNKNOWN).status());
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void firstWalkIndependentlyRequiresObservedFullExpectedSupport(Evidence evidence) {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(1, 0, 2));
        var position = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), fresh(lane, Stage.ENTER_POSITION), UNKNOWN);
        var yawEntry = advance(fresh(lane, Stage.ENTER_POSITION), position);
        var yaw1 = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), yawEntry, UNKNOWN);
        var ready = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), advance(yawEntry, yaw1), UNKNOWN);
        assertEquals(Status.READY_TO_WALK, ready.status());
        var walk = advance(yawEntry, ready);
        var samples = new SupportObservation(Map.of(new GridPos(1, 0, 2), sample(evidence, STONE)));
        var result = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), walk, samples);
        assertEquals(evidence == Evidence.OBSERVED ? Status.WALKING : Status.HOLDING_SUPPORT, result.status());
        assertEquals(Status.HOLDING_SUPPORT, policy.decide(new PlayerObservation(.5, 1, 2.5, -90), walk, observed(lane, WRONG)).status());
    }

    @ParameterizedTest
    @EnumSource(Evidence.class)
    void trulyOffPlanAirIsExemptInEntryAndWalk(Evidence evidence) {
        Lane lane = lane(Direction.EAST);
        var support = new SupportObservation(Map.of(new GridPos(0, 0, 2), sample(evidence, AIR), new GridPos(1, 0, 2), sample(evidence, AIR)));
        assertEquals(Status.ENTERING, policy.decide(new PlayerObservation(0, 1, 2.5, -90), fresh(lane, Stage.ENTER_POSITION), support).status());
        assertEquals(Status.WALKING, policy.decide(new PlayerObservation(.5, 1, 2.5, -90), fresh(lane, Stage.WALK), support).status());
    }

    @Test
    void immutablePlanExpectednessSurvivesRealLedgerAttemptConfirmationAndLaterWrongOrUnknownEvidence() {
        var target = new Target(97, new GridPos(1, 0, 2), STONE);
        var plan = new BuildPlan("missed-attempt", new GridSize(11, 1, 5), List.of(target));
        Lane lane = new LanePlanner().plan(plan, 2, 5).getFirst();
        var ledger = new PlacementLedger(plan);
        var run = UUID.randomUUID();
        ledger.bindRun(run);
        assertTrue(ledger.recordAttempt(run, 12, 97, 1));
        assertTrue(ledger.observe(new PlacementObservation(run, 97, OptionalLong.of(12), 2, Evidence.OBSERVED, Optional.of(STONE))));
        assertTrue(ledger.unfinishedTargets().isEmpty());
        for (Evidence evidence : List.of(Evidence.OBSERVED, Evidence.UNKNOWN)) {
            assertTrue(ledger.observe(new PlacementObservation(run, 97, OptionalLong.empty(), evidence == Evidence.OBSERVED ? 3 : 4,
                    evidence, evidence == Evidence.UNKNOWN ? Optional.empty() : Optional.of(WRONG))));
            assertEquals(List.of(target), ledger.unfinishedTargets());
            var result = policy.decide(new PlayerObservation(.5, 1, 2.5, -90), fresh(lane, Stage.WALK), new SupportObservation(Map.of(target.relativePos(), sample(evidence, WRONG))));
            assertEquals(Status.HOLDING_SUPPORT, result.status());
            assertEquals(1, result.nextSupportHoldTicks());
            assertTrue(lane.expectedFloor().containsTargetId(97));
        }
    }

    @ParameterizedTest
    @CsvSource({"EAST,5.5,2.5,6,2", "WEST,5.5,2.5,4,2", "SOUTH,2.5,5.5,2,6", "NORTH,2.5,5.5,2,4",
            "EAST,5.5,3.2,6,2", "EAST,5.5,3.2,6,3", "EAST,5.5,3.2,6,4",
            "EAST,5.5,3.8,6,2", "EAST,5.5,3.8,6,3", "EAST,5.5,3.8,6,4",
            "WEST,-0.2,2.5,-2,2", "NORTH,2.5,-0.2,2,-2", "EAST,5.5,-0.2,6,-1", "EAST,5.5,-0.2,6,0"})
    void walkFootprintUsesNextProgressCenterActualAndOneAdjacentColumn(Direction direction, double x, double z, int tx, int tz) {
        // WEST/NORTH negative progress is kept nonterminal with negative lane endpoints.
        Lane base = lane(direction);
        if (direction == Direction.WEST) base = new Lane(0, direction, new GridPos(10, 0, 2), new GridPos(-10, 0, 2), new Corridor(-10, 10, 0, 4), new ExpectedFloor(Map.of()));
        if (direction == Direction.NORTH) base = new Lane(0, direction, new GridPos(2, 0, 10), new GridPos(2, 0, -10), new Corridor(0, 4, -10, 10), new ExpectedFloor(Map.of()));
        Lane lane = withTargets(base, new GridPos(tx, 0, tz));
        var result = policy.decide(new PlayerObservation(x, 1, z, direction.yawDegrees()), fresh(lane, Stage.WALK), UNKNOWN);
        // At fraction .2 the adjacent column is lower, at .8 it is higher.
        boolean included = !(x == 5.5 && z == 3.2 && tz == 4);
        assertEquals(included ? Status.HOLDING_SUPPORT : Status.WALKING, result.status());
    }

    @Test
    void fractionPointFourAndPointSixExcludeAdjacentButAdjacentRepresentableValuesIncludeIt() {
        Lane lower = withTargets(lane(Direction.EAST), new GridPos(6, 0, 0));
        Lane higher = withTargets(lane(Direction.EAST), new GridPos(6, 0, 2));
        assertEquals(Status.WALKING, policy.decide(new PlayerObservation(5.5, 1, 1.4, -90), fresh(lower, Stage.WALK), UNKNOWN).status());
        assertEquals(Status.HOLDING_SUPPORT, policy.decide(new PlayerObservation(5.5, 1, Math.nextDown(1.4), -90), fresh(lower, Stage.WALK), UNKNOWN).status());
        // Column 2 is centerline, so shift the lane centerline to 0 for the upper-edge fixture.
        Lane shifted = new Lane(0, Direction.EAST, new GridPos(0, 0, 0), new GridPos(10, 0, 0), higher.corridor(), higher.expectedFloor());
        assertEquals(Status.WALKING, policy.decide(new PlayerObservation(5.5, 1, 1.6, -90), fresh(shifted, Stage.WALK), UNKNOWN).status());
        assertEquals(Status.HOLDING_SUPPORT, policy.decide(new PlayerObservation(5.5, 1, Math.nextUp(1.6), -90), fresh(shifted, Stage.WALK), UNKNOWN).status());
    }

    @Test
    void walkFootprintDeduplicatesAndNeverClampsRequiredAdjacentTargets() {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(6, 0, 5));
        var result = policy.decide(new PlayerObservation(5.5, 1, 4.8, -90), fresh(lane, Stage.WALK), UNKNOWN);
        assertEquals(Status.HOLDING_SUPPORT, result.status());
        assertEquals(1, result.nextSupportHoldTicks());
        Lane duplicate = withTargets(lane(Direction.EAST), new GridPos(6, 0, 2));
        assertEquals(1, policy.decide(new PlayerObservation(5.5, 1, 2.5, -90), fresh(duplicate, Stage.WALK), UNKNOWN).nextSupportHoldTicks());
        assertEquals(Status.WALKING, policy.decide(new PlayerObservation(5.5, 1, 2.5, -90), fresh(duplicate, Stage.WALK), observed(duplicate, STONE)).status());
    }

    @ParameterizedTest
    @CsvSource({"OBSERVED,false,40", "OBSERVED,true,80", "UNKNOWN,false,80", "PREDICTED,false,80"})
    void supportWaitFailsOnExactFortiethOrEightiethDecision(Evidence evidence, boolean pending, int limit) {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(6, 0, 2));
        var entry = context(lane, Stage.WALK, 0, 0, 0, 0, 0, pending ? Set.of(0) : Set.of(), true);
        var support = new SupportObservation(Map.of(new GridPos(6, 0, 2), sample(evidence, WRONG)));
        for (int i = 1; i < limit; i++) {
            var result = policy.decide(new PlayerObservation(5.5, 1, 2.7, -90), entry, support);
            assertEquals(Status.HOLDING_SUPPORT, result.status());
            assertControls(result, false, false, Strafe.LEFT, false);
            assertEquals(i, result.nextSupportHoldTicks());
            entry = advance(entry, result);
        }
        terminal(policy.decide(new PlayerObservation(5.5, 1, 2.7, -90), entry, support), Failure.SUPPORT_TIMEOUT);
    }

    @Test
    void yawWaitFailsTwentiethAndAlignmentResetsItIndependently() {
        Lane lane = lane(Direction.EAST);
        var entry = fresh(lane, Stage.WALK);
        for (int i = 1; i < 20; i++) {
            var result = policy.decide(new PlayerObservation(5.5, 1, 2.7, -80), entry, UNKNOWN);
            assertEquals(Status.HOLDING_YAW, result.status());
            assertEquals(i, result.nextYawHoldTicks());
            noControls(result);
            entry = advance(entry, result);
        }
        terminal(policy.decide(new PlayerObservation(5.5, 1, 2.7, -80), entry, UNKNOWN), Failure.YAW_UNALIGNED);
        var aligned = policy.decide(new PlayerObservation(5.5, 1, 2.7, -90), entry, UNKNOWN);
        assertEquals(0, aligned.nextYawHoldTicks());
        assertEquals(1, policy.decide(new PlayerObservation(5.5, 1, 2.7, -80), advance(entry, aligned), UNKNOWN).nextYawHoldTicks());
    }

    @Test
    void supportCountSurvivesCellEvidencePendingClassAndDisplayedHoldChanges() {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(6, 0, 2), new GridPos(7, 0, 2));
        var entry = context(lane, Stage.WALK, 0, 0, 0, 38, 0, Set.of(0), true);
        var first = policy.decide(new PlayerObservation(5.5, 1, 2.5, -80), entry, observed(lane, WRONG));
        assertEquals(Status.HOLDING_YAW, first.status());
        assertEquals(39, first.nextSupportHoldTicks());
        var changed = context(lane, Stage.WALK, 0, 0, 0, first.nextSupportHoldTicks(), first.nextYawHoldTicks(), Set.of(), true);
        terminal(policy.decide(new PlayerObservation(6.5, 1, 2.5, -90), changed, observed(lane, AIR)), Failure.SUPPORT_TIMEOUT);
        assertEquals(40, policy.decide(new PlayerObservation(6.5, 1, 2.5, -90), changed, UNKNOWN).nextSupportHoldTicks());
        var safe = policy.decide(new PlayerObservation(6.5, 1, 2.5, -90), changed, observed(lane, STONE));
        assertEquals(0, safe.nextSupportHoldTicks());
        assertEquals(1, policy.decide(new PlayerObservation(6.5, 1, 2.5, -90), advance(changed, safe), UNKNOWN).nextSupportHoldTicks());
    }

    @Test
    void mixedFootprintSelectsStrictestSupportBound() {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(6, 0, 2), new GridPos(6, 0, 3));
        var entry = context(lane, Stage.WALK, 0, 0, 0, 39, 0, Set.of(0), true);
        var support = new SupportObservation(Map.of(new GridPos(6, 0, 3), sample(Evidence.OBSERVED, WRONG)));
        terminal(policy.decide(new PlayerObservation(5.5, 1, 3.5, -90), entry, support), Failure.SUPPORT_TIMEOUT);
    }

    @Test
    void supportAndYawExpireIndependentlyAndSupportWinsSimultaneousTimeout() {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(6, 0, 2));
        terminal(policy.decide(new PlayerObservation(5.5, 1, 2.7, -80), context(lane, Stage.WALK, 0, 0, 0, 39, 4, Set.of(), true), observed(lane, WRONG)), Failure.SUPPORT_TIMEOUT);
        terminal(policy.decide(new PlayerObservation(5.5, 1, 2.7, -80), context(lane, Stage.WALK, 0, 0, 0, 4, 19, Set.of(), true), UNKNOWN), Failure.YAW_UNALIGNED);
        terminal(policy.decide(new PlayerObservation(5.5, 1, 2.7, -80), context(lane, Stage.WALK, 0, 0, 0, 39, 19, Set.of(), true), observed(lane, WRONG)), Failure.SUPPORT_TIMEOUT);
        var entry = context(lane, Stage.WALK, 0, 0, 0, 30, 10, Set.of(), true);
        var supportReady = policy.decide(new PlayerObservation(5.5, 1, 2.7, -80), entry, observed(lane, STONE));
        assertEquals(Status.HOLDING_YAW, supportReady.status());
        assertEquals(0, supportReady.nextSupportHoldTicks());
        assertEquals(11, supportReady.nextYawHoldTicks());
        noControls(supportReady);
        var yawReady = policy.decide(new PlayerObservation(5.5, 1, 2.7, -90), entry, UNKNOWN);
        assertEquals(Status.HOLDING_SUPPORT, yawReady.status());
        assertEquals(31, yawReady.nextSupportHoldTicks());
        assertEquals(0, yawReady.nextYawHoldTicks());
        assertControls(yawReady, false, false, Strafe.LEFT, false);
    }

    @Test
    void finiteInputsOnlyAndNullsAreRejected() {
        for (double bad : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new PlayerObservation(bad, 1, 2, -90));
            assertThrows(IllegalArgumentException.class, () -> new PlayerObservation(1, bad, 2, -90));
            assertThrows(IllegalArgumentException.class, () -> new PlayerObservation(1, 1, bad, -90));
            assertThrows(IllegalArgumentException.class, () -> new PlayerObservation(1, 1, 2, bad));
        }
        assertThrows(NullPointerException.class, () -> policy.decide(null, fresh(lane(Direction.EAST), Stage.WALK), UNKNOWN));
        assertThrows(NullPointerException.class, () -> policy.decide(new PlayerObservation(1, 1, 2, -90), null, UNKNOWN));
        assertThrows(NullPointerException.class, () -> policy.decide(new PlayerObservation(1, 1, 2, -90), fresh(lane(Direction.EAST), Stage.WALK), null));
        assertThrows(NullPointerException.class, () -> fresh(null, Stage.WALK));
        assertThrows(NullPointerException.class, () -> fresh(lane(Direction.EAST), null));
        assertThrows(NullPointerException.class, () -> new SupportObservation(null));
        assertThrows(NullPointerException.class, () -> new SupportSample(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new SupportSample(Evidence.UNKNOWN, null));
    }

    @Test
    void evidenceStateShapeAndCollectionNullsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SupportSample(Evidence.UNKNOWN, Optional.of(STONE)));
        assertThrows(IllegalArgumentException.class, () -> new SupportSample(Evidence.PREDICTED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SupportSample(Evidence.OBSERVED, Optional.empty()));
        var nullValue = new HashMap<GridPos, SupportSample>();
        nullValue.put(new GridPos(0, 0, 0), null);
        assertThrows(NullPointerException.class, () -> new SupportObservation(nullValue));
        nullValue.clear();
        nullValue.put(null, sample(Evidence.UNKNOWN, STONE));
        assertThrows(NullPointerException.class, () -> new SupportObservation(nullValue));
        assertThrows(NullPointerException.class, () -> context(lane(Direction.EAST), Stage.WALK, 0, 0, 0, 0, 0, null, false));
        var nullId = new HashSet<Integer>();
        nullId.add(null);
        assertThrows(NullPointerException.class, () -> context(lane(Direction.EAST), Stage.WALK, 0, 0, 0, 0, 0, nullId, false));
    }

    @ParameterizedTest
    @CsvSource({"ENTER_POSITION,-1,0,0,0,0", "ENTER_POSITION,41,0,0,0,0", "ENTER_POSITION,0,53,0,0,0",
            "ENTER_YAW,11,0,0,0,0", "ENTER_YAW,0,0,1,0,0", "ENTER_YAW,2,2,2,0,0",
            "ENTER_POSITION,1,1,1,0,0", "WALK,1,0,0,0,0", "WALK,0,1,0,0,0",
            "WALK,0,0,0,80,0", "WALK,0,0,0,0,20", "ENTER_POSITION,0,0,0,0,1",
            "ENTER_YAW,0,0,0,0,1", "WALK,0,0,0,-1,0", "WALK,0,0,0,0,-1", "ENTER_POSITION,0,-1,0,0,0",
            "ENTER_YAW,1,1,-1,0,0", "ENTER_POSITION,2147483647,0,0,0,0", "ENTER_YAW,0,2147483647,0,0,0",
            "WALK,0,0,0,2147483647,0", "WALK,0,0,0,0,2147483647", "ENTER_YAW,1,1,2147483647,0,0"})
    void unreachableCounterCombinationsFailBeforeArithmetic(Stage stage, int local, int total, int aligned, int support, int yaw) {
        assertThrows(IllegalArgumentException.class, () -> context(lane(Direction.EAST), stage, local, total, aligned, support, yaw, Set.of(), false));
    }

    @Test
    void pendingIdsMustExistInDerivedWholeFloorIndexAndInputsAreDefensivelyCopied() {
        Lane lane = withTargets(lane(Direction.EAST), new GridPos(6, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> context(lane, Stage.WALK, 0, 0, 0, 0, 0, Set.of(-1), false));
        assertThrows(IllegalArgumentException.class, () -> context(lane, Stage.WALK, 0, 0, 0, 0, 0, Set.of(999), false));
        var ids = new HashSet<>(Set.of(0));
        var entry = context(lane, Stage.WALK, 0, 0, 0, 0, 0, ids, true);
        ids.clear();
        assertEquals(Set.of(0), entry.pendingTargetIds());
        assertThrows(UnsupportedOperationException.class, () -> entry.pendingTargetIds().clear());
        var cells = new HashMap<GridPos, SupportSample>();
        cells.put(new GridPos(6, 0, 2), sample(Evidence.OBSERVED, STONE));
        var support = new SupportObservation(cells);
        cells.clear();
        assertEquals(1, support.cells().size());
        assertThrows(UnsupportedOperationException.class, () -> support.cells().clear());
        var player = new PlayerObservation(5.5, 1, 2.5, -90);
        assertEquals(policy.decide(player, entry, support), policy.decide(player, entry, support));
        assertEquals(policy.decide(player, entry, support), new MovementPolicy().decide(player, entry, support));
    }

    @Test
    void extremeGridEdgesDoNotWrapFootprintIntoOppositeExpectedTargets() {
        var target = new Target(0, new GridPos(Integer.MIN_VALUE, 0, 2), STONE);
        Lane lane = new Lane(0, Direction.EAST, new GridPos(Integer.MAX_VALUE - 5, 0, 2), new GridPos(Integer.MAX_VALUE, 0, 2),
                new Corridor(Integer.MAX_VALUE - 5, Integer.MAX_VALUE, 0, 4), new ExpectedFloor(Map.of(target.relativePos(), target)));
        var result = policy.decide(new PlayerObservation((double) Integer.MAX_VALUE + 1, 1, 2.5, -90), fresh(lane, Stage.ENTER_POSITION), UNKNOWN);
        assertEquals(Status.ENTERING, result.status());
        assertTrue(result.back());
        assertEquals(0, result.nextSupportHoldTicks());
    }

    private static Lane lane(Direction direction) {
        return switch (direction) {
            case EAST -> new Lane(0, direction, new GridPos(0, 0, 2), new GridPos(10, 0, 2), new Corridor(0, 10, 0, 4), new ExpectedFloor(Map.of()));
            case WEST -> new Lane(0, direction, new GridPos(10, 0, 2), new GridPos(0, 0, 2), new Corridor(0, 10, 0, 4), new ExpectedFloor(Map.of()));
            case SOUTH -> new Lane(0, direction, new GridPos(2, 0, 0), new GridPos(2, 0, 10), new Corridor(0, 4, 0, 10), new ExpectedFloor(Map.of()));
            case NORTH -> new Lane(0, direction, new GridPos(2, 0, 10), new GridPos(2, 0, 0), new Corridor(0, 4, 0, 10), new ExpectedFloor(Map.of()));
        };
    }

    private static Lane withTargets(Lane lane, GridPos... positions) {
        var map = new HashMap<GridPos, Target>();
        for (int i = 0; i < positions.length; i++) map.put(positions[i], new Target(i, positions[i], STONE));
        return new Lane(lane.id(), lane.direction(), lane.start(), lane.end(), lane.corridor(), new ExpectedFloor(map));
    }

    private static PlayerObservation player(Direction direction, double progress, double lateral, double yaw) {
        return direction.alongX() ? new PlayerObservation(progress, 1, lateral, yaw) : new PlayerObservation(lateral, 1, progress, yaw);
    }

    private static LaneEntry fresh(Lane lane, Stage stage) {
        return context(lane, stage, 0, 0, 0, 0, 0, Set.of(), true);
    }

    private static LaneEntry context(Lane lane, Stage stage, int local, int total, int aligned, int support, int yaw, Set<Integer> pending, boolean sprint) {
        return new LaneEntry(lane, stage, local, total, aligned, support, yaw, pending, sprint);
    }

    private static LaneEntry advance(LaneEntry entry, MovementIntent intent) {
        return context(entry.lane(), intent.nextStage(), intent.nextStageElapsedTicks(), intent.nextEntryElapsedTicks(), intent.nextYawAlignedTicks(),
                intent.nextSupportHoldTicks(), intent.nextYawHoldTicks(), entry.pendingTargetIds(), entry.constantSprint());
    }

    private static SupportSample sample(Evidence evidence, BlockSpec state) {
        return new SupportSample(evidence, evidence == Evidence.UNKNOWN ? Optional.empty() : Optional.of(state));
    }

    private static SupportObservation observed(Lane lane, BlockSpec state) {
        var map = new HashMap<GridPos, SupportSample>();
        lane.expectedFloor().targetsByPosition().keySet().forEach(position -> map.put(position, sample(Evidence.OBSERVED, state)));
        return new SupportObservation(map);
    }

    private static void assertControls(MovementIntent result, boolean forward, boolean back, Strafe strafe, boolean sprint) {
        assertEquals(forward, result.forward());
        assertEquals(back, result.back());
        assertEquals(strafe, result.strafe());
        assertEquals(sprint, result.sprint());
    }

    private static void noControls(MovementIntent result) {
        assertControls(result, false, false, Strafe.NONE, false);
    }

    private static void clearedCounters(MovementIntent result) {
        assertEquals(0, result.nextStageElapsedTicks());
        assertEquals(0, result.nextEntryElapsedTicks());
        assertEquals(0, result.nextYawAlignedTicks());
        assertEquals(0, result.nextSupportHoldTicks());
        assertEquals(0, result.nextYawHoldTicks());
    }

    private static void terminal(MovementIntent result, Failure failure) {
        assertEquals(Status.FAILED, result.status());
        assertEquals(failure, result.failure());
        noControls(result);
        clearedCounters(result);
    }
}
