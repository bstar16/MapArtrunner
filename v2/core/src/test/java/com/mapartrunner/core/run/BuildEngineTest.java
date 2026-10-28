package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.*;

import static com.mapartrunner.core.run.BuildEngine.*;
import static com.mapartrunner.core.run.RunLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildEngineTest {
    static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());

    @Test void firstCompleteSelectsShiftAndLastSelectsVerify() {
        var h = new Harness(plan(12, 10, target(7, 0, 0)));
        assertEquals(Phase.RUNNING, h.engine.snapshot().lifecycle().phase());
        h.ready(); h.at(new PlayerObservation(11.5, 1, 2.5, -90));
        var first = h.step();
        assertEquals(Activity.SHIFT, first.snapshot().lifecycle().activity().orElseThrow());
        assertEquals(Stage.ENTER_POSITION, first.snapshot().laneEntry().orElseThrow().stage());
        assertEquals(11, first.snapshot().laneEntry().orElseThrow().lane().start().x());
        h.ready(); h.at(new PlayerObservation(11.5, 1, 2.5, 0)); h.ready();
        h.at(new PlayerObservation(11.5, 1, 7.5, 0)); h.step();
        h.at(new PlayerObservation(11.5, 1, 7.5, 90)); h.ready();
        assertEquals(Activity.BUILD, h.engine.snapshot().lifecycle().activity().orElseThrow());
        h.at(new PlayerObservation(.5, 1, 7.5, 90));
        var last = h.step();
        assertEquals(Activity.VERIFY, last.snapshot().lifecycle().activity().orElseThrow());
        assertEquals(ControlOwner.NONE, last.owner());
        assertTrue(last.movement().isEmpty());
    }

    @Test void shiftSupportEligibilityAndPriority() {
        var h = shiftHarness(target(7, 0, 2), target(90, 11, 3), target(2, 11, 8), target(1, 0, 8));
        h.available.replaceAll((id, value) -> PlacementAvailability.READY);
        assertEquals(90, h.step().placement().orElseThrow().targetId());
        assertEquals(7, h.step().placement().orElseThrow().targetId());
        assertEquals(2, h.step().placement().orElseThrow().targetId());
        assertTrue(h.step().placement().isEmpty()); // Remote work outside the rectangle stays ineligible.

        var ordered = shiftHarness(target(1, 10, 3), target(90, 11, 3), target(91, 11, 2),
                target(92, 11, 1), target(2, 0, 2), target(3, 11, 8));
        ordered.available.replaceAll((id, value) -> PlacementAvailability.READY);
        for (int id : List.of(90, 1, 91, 92, 2, 3)) assertEquals(id, ordered.step().placement().orElseThrow().targetId());
        var tied = shiftHarness(target(90, 10, 8), target(1, 10, 7));
        tied.available.replaceAll((id, value) -> PlacementAvailability.READY);
        assertEquals(1, tied.step().placement().orElseThrow().targetId());

        var west = shiftHarness(target(90, 10, 7), target(1, 11, 7));
        west.at(new PlayerObservation(11.5, 1, 7.5, 0)); west.step();
        west.available.replaceAll((id, value) -> PlacementAvailability.READY);
        west.at(new PlayerObservation(11.5, 1, 7.5, 90));
        assertEquals(90, west.step().placement().orElseThrow().targetId()); // Signed WEST next beats current.
    }

    @Test void holdingSupportStillIssuesPlacement() {
        var h = shiftHarness(target(90, 11, 3));
        h.support = support(h.plan, AIR, Evidence.OBSERVED);
        h.available.put(90, PlacementAvailability.READY);
        var d = h.step();
        assertEquals(Status.HOLDING_SUPPORT, d.movement().orElseThrow().status());
        assertEquals(90, d.placement().orElseThrow().targetId());
        assertEquals(Set.of(90), d.snapshot().pendingTargetIds());
        assertEquals(List.of(90), d.snapshot().unfinishedTargetIds());
        for (int i = 0; i < 78; i++) {
            var held = h.step(); assertRunning(held); assertTrue(held.placement().isEmpty());
        }
        assertReason(h.step(), Reason.MOVEMENT_FAILED);
        assertTrue(h.engine.snapshot().diagnostic().orElseThrow().detail().contains("SUPPORT_TIMEOUT"));
    }

    @ParameterizedTest @EnumSource(value = PlacementAvailability.class, names = {"NO_USABLE_ITEM", "NO_VALID_HIT"})
    void shiftCannotFabricateUnavailableSupport(PlacementAvailability availability) {
        var h = shiftHarness(target(90, 11, 3));
        h.support = support(h.plan, AIR, Evidence.OBSERVED); h.available.put(90, availability);
        for (int i = 0; i < 39; i++) {
            var held = h.step(); assertRunning(held);
            assertEquals(Status.HOLDING_SUPPORT, held.movement().orElseThrow().status());
            assertTrue(held.placement().isEmpty()); assertTrue(held.snapshot().pendingTargetIds().isEmpty());
        }
        assertReason(h.step(), Reason.MOVEMENT_FAILED);
        assertEquals(List.of(90), h.engine.snapshot().unfinishedTargetIds());
    }

    @Test void shiftKeepsRetrySpacingAndPendingDeadline() {
        var h = shiftHarness(target(90, 11, 8));
        h.available.put(90, PlacementAvailability.READY);
        var first = h.step().placement().orElseThrow();
        for (int i = 0; i < 3; i++) {
            h.observations = h.scan(AIR); assertTrue(h.step().placement().isEmpty());
        }
        h.observations = h.scan(AIR);
        var second = h.step().placement().orElseThrow();
        assertEquals(first.actionId() + 1, second.actionId());
        h.observations = List.of();
        for (int i = 0; i < 79; i++) assertTrue(h.step().placement().isEmpty());
        assertReason(h.step(), Reason.PENDING_EVIDENCE_TIMEOUT);
    }

    @Test void transitionTimeoutRetainsUnfinishedDiagnostic() {
        for (var stage : List.of(ForwardTraversal.Substage.ENDPOINT, ForwardTraversal.Substage.SHIFT,
                ForwardTraversal.Substage.NEXT_ENTRY)) {
            var h = stage == ForwardTraversal.Substage.ENDPOINT
                    ? new Harness(plan(12, 10, target(90, 11, 8))) : shiftHarness(target(90, 11, 8));
            if (stage == ForwardTraversal.Substage.ENDPOINT) {
                h.ready(); h.at(new PlayerObservation(11.5, 1, 2.5, -90)); h.step();
            } else if (stage == ForwardTraversal.Substage.NEXT_ENTRY) {
                h.at(new PlayerObservation(11.5, 1, 7.5, 0)); h.step();
            }
            h.player = Optional.empty();
            var entry = h.engine.snapshot().laneEntry().orElseThrow();
            while (h.engine.snapshot().traversal().orElseThrow().transitionTicks() < 120) {
                var absent = h.step(); assertRunning(absent);
                assertTrue(absent.movement().isEmpty()); assertTrue(absent.placement().isEmpty());
                assertEquals(entry, absent.snapshot().laneEntry().orElseThrow());
            }
            var last = h.engine.snapshot().traversal().orElseThrow();
            assertEquals(stage, last.substage());
            var failed = h.step(); assertReason(failed, Reason.TRANSITION_TIMEOUT);
            assertEquals(List.of(90), failed.snapshot().diagnostic().orElseThrow().targetIds());
            assertEquals(List.of(90), failed.snapshot().unfinishedTargetIds());
            assertTrue(failed.snapshot().traversal().isEmpty()); assertTrue(failed.snapshot().laneEntry().isEmpty());
            var detail = failed.snapshot().diagnostic().orElseThrow().detail();
            assertTrue(detail.contains("laneIndex=" + last.laneIndex()));
            assertTrue(detail.contains("substage=" + stage)); assertTrue(detail.contains("transitionTicks=120"));
            assertTrue(detail.contains("stage=" + entry.stage()));
            assertTrue(detail.contains("supportHoldTicks=" + entry.supportHoldTicks()));
        }
    }

    @Test void completeTraversalStillNeedsLaterFullObservedScan() {
        var h = shiftHarness(target(7, 0, 0), target(90, 11, 8));
        var floor = h.engine.snapshot().laneEntry().orElseThrow().lane().expectedFloor();
        h.observations = h.scan(STONE); h.step();
        assertTrue(h.engine.snapshot().unfinishedTargetIds().isEmpty());
        h.observations = List.of(); h.at(new PlayerObservation(11.5, 1, 7.5, 0)); h.step();
        h.at(new PlayerObservation(11.5, 1, 7.5, 90)); h.ready();
        h.at(new PlayerObservation(.5, 1, 7.5, 90)); h.observations = h.scan(STONE);
        var complete = h.step(); assertRunning(complete);
        assertEquals(Activity.VERIFY, complete.snapshot().lifecycle().activity().orElseThrow());
        assertSame(floor, complete.snapshot().laneEntry().orElseThrow().lane().expectedFloor());
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED)); assertRunning(h.step());
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED),
                observation(h, 90, h.nextTick, STONE, Evidence.PREDICTED)); assertRunning(h.step());
        h.observations = h.scan(STONE);
        var success = h.step(); assertEquals(Outcome.SUCCESS, success.snapshot().lifecycle().outcome().orElseThrow());
        assertTrue(success.snapshot().traversal().isEmpty()); assertTrue(success.snapshot().laneEntry().isEmpty());
    }

    static Harness shiftHarness(Target... targets) {
        var h = new Harness(plan(12, 10, targets));
        assertEquals(Phase.RUNNING, h.engine.snapshot().lifecycle().phase());
        h.support = support(h.plan, STONE, Evidence.OBSERVED);
        h.ready(); h.at(new PlayerObservation(11.5, 1, 2.5, -90)); h.step();
        h.ready(); h.at(new PlayerObservation(11.5, 1, 2.5, 0)); h.ready();
        return h;
    }

    @Test void stagedEntryRequiresBothCoordinatesAndTwoRealYawSamples() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        var first = h.at(new PlayerObservation(.5, 1, 1.5, -90)).step();
        assertEquals(Stage.ENTER_POSITION, first.snapshot().laneEntry().orElseThrow().stage());
        assertEquals(1, first.snapshot().laneEntry().orElseThrow().stageElapsedTicks());
        assertFalse(first.movement().orElseThrow().forward());
        h.at(new PlayerObservation(.5, 1, 2.5, 0));
        assertEquals(Stage.ENTER_YAW, h.step().snapshot().laneEntry().orElseThrow().stage());
        assertEquals(Status.ALIGNING_YAW, h.step().movement().orElseThrow().status());
        h.at(new PlayerObservation(.5, 1, 2.5, -90));
        assertEquals(Status.ALIGNING_YAW, h.step().movement().orElseThrow().status());
        assertEquals(Status.READY_TO_WALK, h.step().movement().orElseThrow().status());
        assertTrue(h.step().movement().orElseThrow().forward());
    }

    @Test void storesEveryReturnedMovementCounterOnce() {
        var h = new Harness(plan(12, 5, target(7, 1, 2)));
        h.ready();
        for (int i = 0; i < 8; i++) {
            TickDecision d = h.step();
            MovementIntent m = d.movement().orElseThrow();
            LaneEntry e = d.snapshot().laneEntry().orElseThrow();
            assertEquals(m.nextStage(), e.stage());
            assertEquals(m.nextStageElapsedTicks(), e.stageElapsedTicks());
            assertEquals(m.nextEntryElapsedTicks(), e.entryElapsedTicks());
            assertEquals(m.nextYawAlignedTicks(), e.consecutiveYawAlignedTicks());
            assertEquals(m.nextSupportHoldTicks(), e.supportHoldTicks());
            assertEquals(m.nextYawHoldTicks(), e.yawHoldTicks());
        }
        assertEquals(8, h.engine.snapshot().laneEntry().orElseThrow().supportHoldTicks());
    }

    @Test void placementContinuesDuringSupportHoldAndDoesNotRemoveExpectations() {
        var h = new Harness(plan(12, 5, target(7, 1, 2)));
        h.ready(); h.available.put(7, PlacementAvailability.READY);
        var d = h.step();
        assertEquals(Status.HOLDING_SUPPORT, d.movement().orElseThrow().status());
        assertEquals(7, d.placement().orElseThrow().targetId());
        assertEquals(List.of(7), d.snapshot().unfinishedTargetIds());
        assertEquals(Set.of(7), d.snapshot().pendingTargetIds());
    }

    @Test void entrySupportHoldAlsoPermitsRequiredPlacement() {
        var h = new Harness(plan(12, 5, target(7, 0, 2)));
        h.at(new PlayerObservation(1.5, 1, 2.5, -90));
        h.available.put(7, PlacementAvailability.READY);
        var d = h.step();
        assertEquals(Status.HOLDING_SUPPORT, d.movement().orElseThrow().status());
        assertEquals(7, d.placement().orElseThrow().targetId());
        assertEquals(1, d.snapshot().laneEntry().orElseThrow().supportHoldTicks());
    }

    @Test void yawAndEntryCountersAreStoredWithoutAnExtraSimulation() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.at(new PlayerObservation(.5, 1, 2.5, 0));
        for (int i = 0; i < 5; i++) {
            var d = h.step(); var m = d.movement().orElseThrow(); var e = d.snapshot().laneEntry().orElseThrow();
            assertEquals(m.nextStageElapsedTicks(), e.stageElapsedTicks());
            assertEquals(m.nextEntryElapsedTicks(), e.entryElapsedTicks());
            assertEquals(i + 1, e.entryElapsedTicks());
        }
        h.at(new PlayerObservation(.5, 1, 2.5, -90)); h.step();
        assertEquals(1, h.engine.snapshot().laneEntry().orElseThrow().consecutiveYawAlignedTicks());
        h.step();
        h.at(new PlayerObservation(.5, 1, 2.5, 0));
        for (int i = 1; i <= 19; i++) {
            var d = h.step();
            assertEquals(i, d.snapshot().laneEntry().orElseThrow().yawHoldTicks());
            assertEquals(i, d.movement().orElseThrow().nextYawHoldTicks());
        }
        assertReason(h.step(), Reason.MOVEMENT_FAILED);
    }

    @Test void ranksCurrentThenForwardThenBehindAndLeftToRightWithSparseIds() {
        var h = new Harness(plan(12, 5, target(99, 1, 0), target(88, 0, 4),
                target(55, 0, 2), target(33, 0, 0)));
        h.available.replaceAll((id, value) -> PlacementAvailability.READY);
        assertEquals(33, h.step().placement().orElseThrow().targetId());
        assertEquals(55, h.step().placement().orElseThrow().targetId());
        assertEquals(88, h.step().placement().orElseThrow().targetId());
        assertEquals(99, h.step().placement().orElseThrow().targetId());
        var behind = new Harness(plan(12, 5, target(2, 0, 0), target(8, 2, 0), target(6, 1, 0)));
        behind.ready(); behind.at(new PlayerObservation(1.5, 1, 2.5, -90));
        behind.available.replaceAll((id, value) -> PlacementAvailability.READY);
        assertEquals(6, behind.step().placement().orElseThrow().targetId());
        assertEquals(8, behind.step().placement().orElseThrow().targetId());
        assertEquals(2, behind.step().placement().orElseThrow().targetId());
    }

    @ParameterizedTest @EnumSource(value = PlacementAvailability.class, names = {"OUT_OF_REACH", "NO_USABLE_ITEM", "NO_VALID_HIT", "UNSUPPORTED"})
    void unavailablePlacementStaysExpected(PlacementAvailability availability) {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, availability);
        assertTrue(h.step().placement().isEmpty());
        assertEquals(List.of(7), h.engine.snapshot().unfinishedTargetIds());
    }

    @Test void effectAcceptanceAndSameTickEvidenceCannotConfirm() {
        var h = new Harness(plan(2, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY);
        var issued = h.step().placement().orElseThrow();
        h.results = List.of(result(issued, EffectStatus.ACCEPTED));
        h.observations = List.of(observation(h, 7, issued.tick(), STONE, Evidence.OBSERVED));
        var d = h.step();
        assertEquals(List.of(7), d.snapshot().unfinishedTargetIds());
        assertEquals(Set.of(7), d.snapshot().pendingTargetIds());
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED));
        assertTrue(h.step().snapshot().unfinishedTargetIds().isEmpty());
    }

    @Test void traversalAndPriorConfirmationNeedLaterFullScan() {
        var h = new Harness(plan(2, 5, target(7, 0, 0), target(100, 1, 4)));
        h.observations = h.scan(STONE);
        h.step(); h.observations = List.of();
        h.step(); h.step();
        var completion = h.step();
        assertEquals(Activity.VERIFY, completion.snapshot().lifecycle().activity().orElseThrow());
        assertEquals(ControlOwner.NONE, completion.owner());
        assertEquals(Phase.RUNNING, completion.snapshot().lifecycle().phase());
        assertTrue(h.step().snapshot().unfinishedTargetIds().isEmpty());
        h.observations = h.scan(STONE);
        var success = h.step();
        assertEquals(Outcome.SUCCESS, success.snapshot().lifecycle().outcome().orElseThrow());
        assertTrue(success.placement().isEmpty()); assertTrue(success.movement().isEmpty());
    }

    @Test void completionTickFullScanCannotBeFinalScan() {
        var h = new Harness(plan(2, 5, target(7, 0, 0)));
        h.ready(); h.observations = h.scan(STONE);
        assertEquals(Phase.RUNNING, h.step().snapshot().lifecycle().phase());
        h.observations = h.scan(STONE);
        assertEquals(Outcome.SUCCESS, h.step().snapshot().lifecycle().outcome().orElseThrow());
    }

    @Test void duplicateMissingUnknownForeignFutureAndStaleSamplesCannotForgeFinalSet() {
        var h = verifyHarness();
        PlacementObservation good7 = observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED);
        h.observations = List.of(good7, good7);
        assertRunning(h.step());
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED));
        assertRunning(h.step());
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED),
                observation(h, 999, h.nextTick, STONE, Evidence.OBSERVED));
        assertRunning(h.step());
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED),
                observation(h, 100, h.nextTick + 1, STONE, Evidence.OBSERVED));
        assertRunning(h.step());
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED),
                new PlacementObservation(UUID.randomUUID(), 100, OptionalLong.empty(), h.nextTick,
                        Evidence.OBSERVED, Optional.of(STONE)));
        assertRunning(h.step());
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED),
                observation(h, 100, h.nextTick - 1, STONE, Evidence.OBSERVED));
        assertRunning(h.step());
        h.observations = h.scan(STONE);
        assertEquals(Outcome.SUCCESS, h.step().snapshot().lifecycle().outcome().orElseThrow());
    }

    @Test void extraMalformedEntriesInvalidateAnOtherwiseCompleteCurrentScan() {
        var h = verifyHarness();
        var batch = new ArrayList<>(h.scan(STONE));
        batch.add(observation(h, 999, h.nextTick, STONE, Evidence.OBSERVED));
        h.observations = batch; assertRunning(h.step());
        h.observations = h.scan(STONE);
        assertEquals(Outcome.SUCCESS, h.step().snapshot().lifecycle().outcome().orElseThrow());
    }

    @Test void allOccurrencesOfDuplicateIdsAreRejectedButOtherUniqueSamplesAreRouted() {
        var h = new Harness(plan(2, 5, target(7, 0, 0), target(100, 1, 4)));
        var duplicate = observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED);
        h.observations = List.of(duplicate, duplicate, observation(h, 100, h.nextTick, STONE, Evidence.OBSERVED));
        assertEquals(List.of(7), h.step().snapshot().unfinishedTargetIds());
    }

    @ParameterizedTest @EnumSource(value = Evidence.class, names = {"UNKNOWN", "PREDICTED"})
    void nonObservedEvidenceReopensConfirmedTarget(Evidence evidence) {
        var h = verifyHarness();
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, evidence),
                observation(h, 100, h.nextTick, STONE, Evidence.OBSERVED));
        assertEquals(List.of(7), h.step().snapshot().unfinishedTargetIds());
    }

    @Test void fullPropertiesMatterAndVerifyCanPlaceReachableLeftovers() {
        var stair = new BlockSpec("minecraft:oak_stairs", Map.of("facing", "east"));
        var h = new Harness(new BuildPlan("stairs", new GridSize(2, 1, 5),
                List.of(new Target(7, new GridPos(0, 0, 0), stair))));
        h.ready(); h.step();
        h.available.put(7, PlacementAvailability.READY);
        h.observations = List.of(observation(h, 7, h.nextTick,
                new BlockSpec("minecraft:oak_stairs", Map.of("facing", "west")), Evidence.OBSERVED));
        var d = h.step();
        assertEquals(ControlOwner.NONE, d.owner());
        assertEquals(7, d.placement().orElseThrow().targetId());
        assertEquals(List.of(7), d.snapshot().unfinishedTargetIds());
    }

    @Test void retrySpacingUsesIssuanceAndRequiresCurrentObservedMismatch() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY);
        assertTrue(h.step().placement().isPresent()); // eligible 1
        h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
        assertTrue(h.step().placement().isEmpty()); // age 1
        h.observations = List.of(); h.step(); h.step();
        assertTrue(h.step().placement().isEmpty()); // age 4, but no current mismatch
        h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
        assertTrue(h.step().placement().isPresent()); // late mismatch, no resolution delay
    }

    @Test void retryAtExactlyFourEligibleTicks() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY); h.step();
        for (int i = 0; i < 3; i++) {
            h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
            assertTrue(h.step().placement().isEmpty());
        }
        h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
        assertTrue(h.step().placement().isPresent());
    }

    @ParameterizedTest @EnumSource(EffectStatus.class)
    void feedbackNeverResolvesPendingOrPermitsRetry(EffectStatus status) {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY);
        var intent = h.step().placement().orElseThrow();
        h.results = List.of(result(intent, status));
        for (int i = 0; i < 79; i++) {
            assertTrue(h.step().placement().isEmpty());
            assertEquals(Set.of(7), h.engine.snapshot().pendingTargetIds());
        }
        assertReason(h.step(), Reason.PENDING_EVIDENCE_TIMEOUT);
    }

    @Test void thirdIssuanceGetsFullWaitAndNeverFourthAttempt() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertTrue(h.step().placement().isPresent());
            if (attempt < 2) for (int wait = 0; wait < 3; wait++) {
                h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
                assertTrue(h.step().placement().isEmpty());
            }
            h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
        }
        h.observations = List.of();
        for (int i = 0; i < 79; i++) assertRunning(h.step());
        assertReason(h.step(), Reason.PENDING_EVIDENCE_TIMEOUT);
    }

    @Test void resolvedThirdMismatchIsExhaustedImmediately() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY);
        int attempts = 0;
        while (h.engine.snapshot().lifecycle().phase() == Phase.RUNNING) {
            h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
            if (h.step().placement().isPresent()) attempts++;
        }
        assertEquals(3, attempts);
        assertEquals(Reason.ATTEMPTS_EXHAUSTED, h.engine.snapshot().diagnostic().orElseThrow().reason());
    }

    @ParameterizedTest @EnumSource(value = Evidence.class, names = {"UNKNOWN", "PREDICTED"})
    void confirmedThirdAttemptCanReopenWithUncertaintyAndLaterVerifyWithoutFourthAttempt(Evidence evidence) {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY);
        for (int attempt = 0; attempt < 3; attempt++) {
            assertTrue(h.step().placement().isPresent());
            if (attempt < 2) for (int wait = 0; wait < 3; wait++) {
                h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
                assertTrue(h.step().placement().isEmpty());
            }
            h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
        }
        h.observations = h.scan(STONE);
        var confirmed = h.step();
        assertTrue(confirmed.snapshot().unfinishedTargetIds().isEmpty());
        assertTrue(confirmed.snapshot().pendingTargetIds().isEmpty());
        assertTrue(confirmed.placement().isEmpty());
        for (int i = 0; i < 5; i++) {
            h.observations = List.of(observation(h, 7, h.nextTick, STONE, evidence));
            var uncertain = h.step();
            assertRunning(uncertain);
            assertEquals(List.of(7), uncertain.snapshot().unfinishedTargetIds());
            assertTrue(uncertain.snapshot().pendingTargetIds().isEmpty());
            assertTrue(uncertain.placement().isEmpty());
        }
        h.at(new PlayerObservation(11.5, 1, 2.5, -90));
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, evidence));
        var completion = h.step();
        assertEquals(Activity.VERIFY, completion.snapshot().lifecycle().activity().orElseThrow());
        assertTrue(completion.placement().isEmpty());
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, evidence));
        var verifying = h.step();
        assertRunning(verifying); assertTrue(verifying.placement().isEmpty());
        h.observations = h.scan(STONE);
        var success = h.step();
        assertEquals(Outcome.SUCCESS, success.snapshot().lifecycle().outcome().orElseThrow());
        assertTrue(success.placement().isEmpty());
    }

    @Test void genuineEvidenceAtPendingBoundaryResolvesBeforeTimeout() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY); h.step();
        for (int i = 0; i < 79; i++) h.step();
        h.observations = h.scan(STONE);
        var d = h.step();
        assertRunning(d); assertTrue(d.snapshot().pendingTargetIds().isEmpty());
    }

    @ParameterizedTest @EnumSource(value = Evidence.class, names = {"UNKNOWN", "PREDICTED"})
    void nonObservedEvidenceCannotResolvePendingEvenAtBoundary(Evidence evidence) {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY); h.step();
        for (int i = 0; i < 79; i++) {
            h.observations = List.of(observation(h, 7, h.nextTick, STONE, evidence));
            assertTrue(h.step().placement().isEmpty());
        }
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, evidence));
        assertReason(h.step(), Reason.PENDING_EVIDENCE_TIMEOUT);
    }

    @Test void genuineFinalScanAtPendingBoundaryCanSucceed() {
        var h = new Harness(plan(2, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY); h.step();
        for (int i = 0; i < 79; i++) h.step();
        h.observations = h.scan(STONE);
        assertEquals(Outcome.SUCCESS, h.step().snapshot().lifecycle().outcome().orElseThrow());
    }

    @Test void observedSupportFortyWinsBeforePendingEighty() {
        var h = new Harness(plan(12, 5, target(7, 1, 2)));
        h.support = support(h.plan, AIR, Evidence.OBSERVED); h.ready();
        for (int i = 0; i < 39; i++) assertRunning(h.step());
        assertReason(h.step(), Reason.MOVEMENT_FAILED);
        assertTrue(h.engine.snapshot().diagnostic().orElseThrow().detail().contains("SUPPORT_TIMEOUT"));
    }

    @Test void pendingSupportEightyIsNotExtendedByPlacementOrFeedback() {
        var h = new Harness(plan(12, 5, target(7, 1, 2)));
        h.support = support(h.plan, AIR, Evidence.OBSERVED); h.ready();
        h.available.put(7, PlacementAvailability.READY);
        var first = h.step().placement().orElseThrow(); h.results = List.of(result(first, EffectStatus.ACCEPTED));
        for (int i = 0; i < 78; i++) assertRunning(h.step());
        assertReason(h.step(), Reason.MOVEMENT_FAILED);
        assertEquals(83, h.engine.snapshot().eligibleTicks());
    }

    @Test void resolvedPendingClassDoesNotResetThePriorSupportCounter() {
        var h = new Harness(plan(12, 5, target(7, 1, 2)));
        h.support = support(h.plan, AIR, Evidence.OBSERVED); h.ready();
        h.available.put(7, PlacementAvailability.READY); h.step();
        for (int i = 0; i < 38; i++) h.step();
        assertEquals(39, h.engine.snapshot().laneEntry().orElseThrow().supportHoldTicks());
        h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
        assertReason(h.step(), Reason.MOVEMENT_FAILED);
        assertEquals(43, h.engine.snapshot().eligibleTicks());
    }

    @Test void supportResolutionAtItsEightyBoundaryContinuesMovement() {
        var h = new Harness(plan(12, 5, target(7, 1, 2)));
        h.support = support(h.plan, AIR, Evidence.OBSERVED); h.ready();
        h.available.put(7, PlacementAvailability.READY); h.step();
        for (int i = 0; i < 78; i++) h.step();
        h.support = support(h.plan, STONE, Evidence.OBSERVED); h.observations = h.scan(STONE);
        var d = h.step(); assertRunning(d);
        assertEquals(Status.WALKING, d.movement().orElseThrow().status());
        assertEquals(0, d.snapshot().laneEntry().orElseThrow().supportHoldTicks());
    }

    @Test void entryFailureRemainsEarlierThanPlacementWait() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.at(new PlayerObservation(1.5, 1, 2.5, -90));
        h.available.put(7, PlacementAvailability.READY);
        for (int i = 0; i < 40; i++) assertRunning(h.step());
        assertReason(h.step(), Reason.MOVEMENT_FAILED);
        assertTrue(h.engine.snapshot().diagnostic().orElseThrow().detail().contains("ENTRY_TIMEOUT"));
    }

    @Test void movementWinsSimultaneousPendingAndSupportTimeout() {
        var h = new Harness(plan(12, 5, target(7, 1, 2)));
        h.step(); h.step(); h.available.put(7, PlacementAvailability.READY); h.step(); // issue at 3
        for (int i = 0; i < 79; i++) assertRunning(h.step());
        assertReason(h.step(), Reason.MOVEMENT_FAILED); // support 80 and pending age 80
    }

    @Test void missingIssuedOuterTargetFailsAfterTraversalDespiteOtherConfirmedTargets() {
        var h = new Harness(plan(2, 5, target(7, 0, 0), target(100, 1, 4)));
        h.available.put(7, PlacementAvailability.READY); h.step();
        while (h.engine.snapshot().lifecycle().phase() == Phase.RUNNING) {
            h.observations = List.of(observation(h, 100, h.nextTick, STONE, Evidence.OBSERVED));
            h.step();
        }
        assertEquals(Reason.PENDING_EVIDENCE_TIMEOUT, h.engine.snapshot().diagnostic().orElseThrow().reason());
        assertEquals(List.of(7), h.engine.snapshot().diagnostic().orElseThrow().targetIds());
    }

    @Test void priorConfirmedButMissingFinalSampleTimesOutWithMissingId() {
        var h = verifyHarness();
        for (int i = 0; i < 79; i++) {
            h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED));
            assertRunning(h.step());
        }
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED));
        var d = h.step(); assertReason(d, Reason.VERIFY_TIMEOUT);
        assertEquals(List.of(100), d.snapshot().diagnostic().orElseThrow().targetIds());
    }

    @Test void genuineFullScanAtVerifyEightyWins() {
        var h = verifyHarness();
        for (int i = 0; i < 79; i++) h.step();
        h.observations = h.scan(STONE);
        assertEquals(Outcome.SUCCESS, h.step().snapshot().lifecycle().outcome().orElseThrow());
        assertEquals(80, h.engine.snapshot().verifyTicks());
    }

    @Test void verifyBoundaryFailurePrecedesAnyNewReadyPlacement() {
        var h = verifyHarness();
        for (int i = 0; i < 79; i++) h.step();
        h.available.replaceAll((id, value) -> PlacementAvailability.READY);
        h.observations = h.scan(AIR);
        assertReason(h.step(), Reason.VERIFY_TIMEOUT);
    }

    @Test void verifyLeftoversUseDistanceThenStableIdWithMovementReleased() {
        var h = new Harness(plan(2, 5, target(100, 0, 0), target(7, 0, 4)));
        h.ready(); h.step(); h.available.replaceAll((id, value) -> PlacementAvailability.READY);
        var first = h.step(); assertEquals(7, first.placement().orElseThrow().targetId());
        assertEquals(ControlOwner.NONE, first.owner()); assertTrue(first.movement().isEmpty());
        assertEquals(100, h.step().placement().orElseThrow().targetId());
    }

    @Test void absentPlayerConsumesExactlyOneActiveTickAndFailsAt1200WithoutEffect() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.player = Optional.empty(); h.available.put(7, PlacementAvailability.READY);
        for (int i = 0; i < 1199; i++) {
            var d = h.step(); assertRunning(d);
            assertTrue(d.placement().isEmpty()); assertTrue(d.movement().isEmpty());
        }
        assertReason(h.step(), Reason.RUN_TIMEOUT);
        assertEquals(1200, h.engine.snapshot().eligibleTicks());
    }

    @Test void validFinalResultAtBothRun1200AndVerify80Wins() {
        var h = new Harness(plan(2, 5, target(7, 0, 0)));
        h.player = Optional.empty();
        for (int i = 0; i < 1116; i++) h.step();
        h.at(new PlayerObservation(.5, 1, 2.5, -90)); h.ready(); h.step();
        assertEquals(1120, h.engine.snapshot().eligibleTicks());
        for (int i = 0; i < 79; i++) h.step();
        h.observations = h.scan(STONE);
        var d = h.step();
        assertEquals(Outcome.SUCCESS, d.snapshot().lifecycle().outcome().orElseThrow());
        assertEquals(1200, d.snapshot().eligibleTicks());
    }

    @Test void pendingWinsVerifyAndRunTimeoutWhenAllDue() {
        var h = new Harness(plan(2, 5, target(7, 0, 0)));
        h.player = Optional.empty();
        for (int i = 0; i < 1116; i++) h.step();
        h.at(new PlayerObservation(.5, 1, 2.5, -90)); h.ready();
        h.available.put(7, PlacementAvailability.READY); h.step(); // issue at1120, enter verify
        for (int i = 0; i < 79; i++) h.step();
        assertReason(h.step(), Reason.PENDING_EVIDENCE_TIMEOUT);
    }

    static Harness verifyHarness() {
        var h = new Harness(plan(2, 5, target(7, 0, 0), target(100, 1, 4)));
        h.observations = h.scan(STONE); h.step(); h.observations = List.of();
        h.step(); h.step(); h.step(); return h;
    }
    static BuildPlan plan(int width, int depth, Target... targets) {
        return new BuildPlan("plan", new GridSize(width, 1, depth), List.of(targets));
    }
    static Target target(int id, int x, int z) { return new Target(id, new GridPos(x, 0, z), STONE); }
    static PlacementObservation observation(Harness h, int id, long tick, BlockSpec block, Evidence evidence) {
        return new PlacementObservation(h.context.runId(), id, OptionalLong.empty(), tick, evidence,
                evidence == Evidence.UNKNOWN ? Optional.empty() : Optional.of(block));
    }
    static PlacementEffectResult result(PlacementIntent intent, EffectStatus status) {
        return new PlacementEffectResult(intent.runId(), intent.actionId(), intent.targetId(), intent.tick(), status);
    }
    static SupportObservation support(BuildPlan plan, BlockSpec state, Evidence evidence) {
        var cells = new HashMap<GridPos, SupportSample>();
        for (Target t : plan.targets()) cells.put(t.relativePos(), new SupportSample(evidence,
                evidence == Evidence.UNKNOWN ? Optional.empty() : Optional.of(state)));
        return new SupportObservation(cells);
    }
    static void assertRunning(TickDecision d) { assertEquals(Phase.RUNNING, d.snapshot().lifecycle().phase()); }
    static void assertReason(TickDecision d, Reason reason) {
        assertEquals(Phase.TERMINAL, d.snapshot().lifecycle().phase());
        assertEquals(reason, d.snapshot().diagnostic().orElseThrow().reason());
        assertEquals(ControlOwner.NONE, d.owner()); assertTrue(d.placement().isEmpty()); assertTrue(d.movement().isEmpty());
    }
    static final class Harness {
        final BuildEngine engine = new BuildEngine();
        final BuildPlan plan;
        final Settings settings = Settings.defaults();
        final Context context;
        long nextTick;
        Optional<PlayerObservation> player = Optional.of(new PlayerObservation(.5, 1, 2.5, -90));
        SupportObservation support = new SupportObservation(Map.of());
        List<PlacementObservation> observations = List.of();
        List<PlacementEffectResult> results = List.of();
        final Map<Integer, PlacementAvailability> available = new HashMap<>();
        Harness(BuildPlan plan) {
            this.plan = plan;
            context = new Context(UUID.randomUUID(), "connection", "world", "minecraft:overworld",
                    plan.fingerprint(), new GridPos(0, 65, 0), settings.fingerprint());
            for (Target t : plan.targets()) available.put(t.id(), PlacementAvailability.NO_USABLE_ITEM);
            engine.start(plan, context, settings);
        }
        Harness at(PlayerObservation value) { player = Optional.of(value); return this; }
        TickInput input() { return new TickInput(context.runId(), nextTick++, Optional.of(context), player,
                support, observations, available, results); }
        TickDecision step() { return engine.tick(input()); }
        void ready() { step(); step(); step(); }
        List<PlacementObservation> scan(BlockSpec state) {
            return plan.targets().stream().map(t -> observation(this, t.id(), nextTick, state, Evidence.OBSERVED)).toList();
        }
    }
}
