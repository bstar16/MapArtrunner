package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementObservation;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.mapartrunner.core.run.BuildEngine.*;
import static com.mapartrunner.core.run.BuildEngineTest.*;
import static com.mapartrunner.core.run.RunLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildEngineLifecycleTest {
    @Test void emptyPlanSucceedsAndMultiLaneFailsBeforeAcquisition() {
        var empty = new Harness(plan(2, 5));
        assertEquals(Outcome.SUCCESS, empty.engine.snapshot().lifecycle().outcome().orElseThrow());
        assertReason(empty.step(), Reason.NOTHING_TO_BUILD);
        var multi = new Harness(plan(12, 6, target(7, 0, 0)));
        assertReason(multi.step(), Reason.UNSUPPORTED_PLAN);
    }

    @Test void outsideNativeEntryEnvelopeFailsBeforeMovementOrPlacement() {
        for (var player : List.of(new PlayerObservation(30, 1, 2.5, -90),
                new PlayerObservation(.5, 1, -1, -90), new PlayerObservation(.5, -.1, 2.5, -90))) {
            var h = new Harness(plan(12, 5, target(7, 0, 0)));
            h.at(player); h.available.put(7, PlacementAvailability.READY);
            var d = h.step(); assertReason(d, Reason.COARSE_TRAVEL_UNAVAILABLE);
            assertEquals(1, d.snapshot().eligibleTicks());
        }
    }

    @Test void approachConsumesOnePolicyResultAndAdvancesItsCountersExactlyOnce() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.at(new PlayerObservation(1.5, 1, 2.5, -90));
        LaneEntry before = h.engine.snapshot().laneEntry().orElseThrow();
        MovementIntent expected = new MovementPolicy().decide(h.player.orElseThrow(), before, h.support);
        var d = h.step();
        assertEquals(Activity.BUILD, d.snapshot().lifecycle().activity().orElseThrow());
        assertEquals(expected, d.movement().orElseThrow());
        var after = d.snapshot().laneEntry().orElseThrow();
        assertEquals(expected.nextStage(), after.stage());
        assertEquals(expected.nextStageElapsedTicks(), after.stageElapsedTicks());
        assertEquals(expected.nextEntryElapsedTicks(), after.entryElapsedTicks());
        assertEquals(expected.nextYawAlignedTicks(), after.consecutiveYawAlignedTicks());
        assertEquals(expected.nextSupportHoldTicks(), after.supportHoldTicks());
        assertEquals(expected.nextYawHoldTicks(), after.yawHoldTicks());
        assertEquals(1, after.stageElapsedTicks()); assertEquals(1, after.entryElapsedTicks());
        assertEquals(1, d.snapshot().eligibleTicks());
    }

    @Test void duplicateAndBackwardsTickNeverAdvanceOrReturnEffectsTwice() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY);
        TickInput input = h.input(); var first = h.engine.tick(input); var before = h.engine.snapshot();
        var repeated = h.engine.tick(input);
        assertEquals(before, repeated.snapshot());
        assertTrue(repeated.runId().isEmpty());
        assertTrue(first.placement().isPresent()); assertTrue(repeated.placement().isEmpty());
        assertTrue(repeated.movement().isEmpty()); assertEquals(ControlOwner.NONE, repeated.owner());
        h.step(); before = h.engine.snapshot(); h.engine.tick(input); assertEquals(before, h.engine.snapshot());
    }

    @Test void oldRunRoutingCannotCancelOrAdvanceNewRunEvenWithAbsentBinding() {
        var h = new Harness(plan(12, 5, target(7, 0, 0))); TickInput old = h.input();
        h.engine.stop();
        var fresh = new Context(UUID.randomUUID(), "new-connection", "new-world", "minecraft:overworld",
                h.plan.fingerprint(), h.context.origin(), h.settings.fingerprint());
        h.engine.start(h.plan, fresh, h.settings);
        var before = h.engine.snapshot();
        var stale = new TickInput(old.runId(), 99, Optional.empty(), old.player(), old.support(), old.observations(),
                old.availability(), old.effectResults());
        var d = h.engine.tick(stale); assertEquals(before, d.snapshot());
        assertTrue(d.runId().isEmpty());
        assertTrue(d.placement().isEmpty()); assertTrue(d.movement().isEmpty());
    }

    @Test void currentRunBindingLossWinsAllOtherFailuresAndNeverAutoResumes() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        var i = h.input();
        var d = h.engine.tick(new TickInput(i.runId(), i.tick(), Optional.empty(),
                Optional.of(new PlayerObservation(99, -1, 99, 0)), i.support(), i.observations(), i.availability(), i.effectResults()));
        assertReason(d, Reason.BINDING_LOST);
        var terminal = h.engine.snapshot(); h.step(); assertEquals(terminal, h.engine.snapshot());
    }

    @Test void worldReplacementOrRejoinStopsPausedAndActiveRuns() {
        for (boolean paused : List.of(false, true)) for (boolean rejoin : List.of(false, true)) {
            var h = new Harness(plan(12, 5, target(7, 0, 0))); h.step();
            if (paused) h.engine.pause();
            Context c = h.context;
            Context changed = new Context(c.runId(), rejoin ? "rejoin" : c.connectionId(),
                    rejoin ? c.worldId() : "replacement", c.dimension(), c.planFingerprint(), c.origin(), c.settingsFingerprint());
            var i = h.input();
            assertReason(h.engine.tick(new TickInput(i.runId(), i.tick(), Optional.of(changed), i.player(), i.support(),
                    i.observations(), i.availability(), i.effectResults())), Reason.BINDING_LOST);
        }
    }

    @Test void pauseRetainsWalkCountersAndTargetsButFreezesBudgetsAndEffects() {
        var h = new Harness(plan(12, 5, target(7, 1, 2))); h.ready();
        h.available.put(7, PlacementAvailability.READY); h.step();
        var before = h.engine.snapshot(); h.engine.pause();
        for (int i = 0; i < 200; i++) {
            var d = h.step(); assertTrue(d.placement().isEmpty()); assertTrue(d.movement().isEmpty());
            assertEquals(ControlOwner.NONE, d.owner());
            assertEquals(before.laneEntry(), d.snapshot().laneEntry());
            assertEquals(before.eligibleTicks(), d.snapshot().eligibleTicks());
            assertEquals(before.pendingTargetIds(), d.snapshot().pendingTargetIds());
        }
        h.engine.resume(h.context);
        var d = h.step(); assertEquals(before.eligibleTicks() + 1, d.snapshot().eligibleTicks());
        assertEquals(2, d.snapshot().laneEntry().orElseThrow().supportHoldTicks());
    }

    @Test void pausedEvidenceCanResolvePendingWithoutMovementOrDeadlineAdvance() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY); h.step(); h.engine.pause();
        h.observations = h.scan(STONE);
        var d = h.step(); assertEquals(1, d.snapshot().eligibleTicks());
        assertTrue(d.snapshot().pendingTargetIds().isEmpty());
        assertTrue(d.snapshot().unfinishedTargetIds().isEmpty()); assertTrue(d.placement().isEmpty());
    }

    @Test void pauseAtPendingBoundaryDoesNotSpendWallTicksAndResumeNeedsFreshSample() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY); h.step();
        for (int i = 0; i < 79; i++) h.step();
        h.engine.pause(); for (int i = 0; i < 100; i++) h.step();
        assertEquals(80, h.engine.snapshot().eligibleTicks());
        h.engine.resume(h.context); h.observations = h.scan(STONE);
        assertRunning(h.step()); assertTrue(h.engine.snapshot().pendingTargetIds().isEmpty());
    }

    @Test void pauseAtVerifyAndRunBoundaryFreezesBothAndAllowsFreshSuccess() {
        var h = new Harness(plan(2, 5, target(7, 0, 0))); h.player = Optional.empty();
        for (int i = 0; i < 1116; i++) h.step();
        h.at(new PlayerObservation(.5, 1, 2.5, -90)); h.ready(); h.step();
        for (int i = 0; i < 79; i++) h.step();
        h.engine.pause(); for (int i = 0; i < 100; i++) h.step();
        assertEquals(1199, h.engine.snapshot().eligibleTicks());
        assertEquals(79, h.engine.snapshot().verifyTicks());
        h.engine.resume(h.context); h.observations = h.scan(STONE);
        assertEquals(Outcome.SUCCESS, h.step().snapshot().lifecycle().outcome().orElseThrow());
    }

    @Test void stopFromBuildAndVerifyInvalidatesMutableOwnersAndPreservesFirstSummary() {
        for (boolean verify : List.of(false, true)) {
            var h = verify ? verifyHarness() : new Harness(plan(12, 5, target(7, 0, 0)));
            if (!verify) h.step();
            h.engine.stop(); var stopped = h.engine.snapshot();
            assertEquals(Outcome.CANCELLED, stopped.lifecycle().outcome().orElseThrow());
            assertTrue(stopped.laneEntry().isEmpty()); assertTrue(stopped.pendingTargetIds().isEmpty());
            h.engine.stop(); h.observations = h.scan(STONE); h.step(); assertEquals(stopped, h.engine.snapshot());
        }
    }

    @Test void staleOrForeignActionEvidenceAndEveryEffectIdentityCoordinateAreRejected() {
        var h = new Harness(plan(12, 5, target(7, 0, 0))); h.available.put(7, PlacementAvailability.READY);
        var a = h.step().placement().orElseThrow();
        h.observations = List.of(new PlacementObservation(h.context.runId(), 7, OptionalLong.of(a.actionId() + 1),
                h.nextTick, Evidence.OBSERVED, Optional.of(STONE)));
        h.results = List.of(new PlacementEffectResult(a.runId(), a.actionId(), 7, a.tick() + 1, EffectStatus.ACCEPTED),
                new PlacementEffectResult(a.runId(), a.actionId() + 1, 7, a.tick(), EffectStatus.ACCEPTED),
                new PlacementEffectResult(a.runId(), a.actionId(), 999, a.tick(), EffectStatus.ACCEPTED),
                new PlacementEffectResult(UUID.randomUUID(), a.actionId(), 7, a.tick(), EffectStatus.ACCEPTED));
        var rejected = h.step();
        assertEquals(Set.of(7), rejected.snapshot().pendingTargetIds());
        assertTrue(rejected.snapshot().effectResults().isEmpty());
        h.results = List.of(result(a, EffectStatus.ACCEPTED), result(a, EffectStatus.ACCEPTED));
        h.observations = List.of();
        var accepted = h.step();
        assertEquals(List.of(result(a, EffectStatus.ACCEPTED)), accepted.snapshot().effectResults());
        assertEquals(Set.of(7), accepted.snapshot().pendingTargetIds());
        h.results = List.of();
        h.observations = List.of(observation(h, 7, h.nextTick, STONE, Evidence.OBSERVED));
        var confirmed = h.step(); assertTrue(confirmed.snapshot().pendingTargetIds().isEmpty());
        assertTrue(confirmed.snapshot().effectResults().isEmpty());
    }

    @Test void sampleTimeGapsDoNotInventEligibleTicksOrRetryAge() {
        var h = new Harness(plan(12, 5, target(7, 0, 0))); h.available.put(7, PlacementAvailability.READY);
        h.step(); h.nextTick = 1_000_000;
        h.observations = List.of(observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED));
        var d = h.step(); assertEquals(2, d.snapshot().eligibleTicks()); assertTrue(d.placement().isEmpty());
        assertEquals(2, d.snapshot().laneEntry().orElseThrow().entryElapsedTicks());
    }

    @Test void changedResumeContextLeavesPausedStateUntouched() {
        var h = new Harness(plan(12, 5, target(7, 0, 0))); h.step(); h.engine.pause();
        var before = h.engine.snapshot(); var c = h.context;
        var changed = new Context(c.runId(), c.connectionId(), c.worldId(), c.dimension(), c.planFingerprint(),
                new GridPos(99, 65, 0), c.settingsFingerprint());
        assertThrows(IllegalArgumentException.class, () -> h.engine.resume(changed));
        assertEquals(before, h.engine.snapshot());
    }

    @Test void malformedFingerprintsAndSettingsFailAtomicallyBeforeStarting() {
        var engine = new BuildEngine(); var p = plan(12, 5, target(7, 0, 0)); var s = Settings.defaults();
        var c = new Context(UUID.randomUUID(), "c", "w", "d", "wrong-plan", new GridPos(0, 0, 0), s.fingerprint());
        assertThrows(IllegalArgumentException.class, () -> engine.start(p, c, s));
        assertEquals(Phase.IDLE, engine.snapshot().lifecycle().phase());
        var wrong = new Context(UUID.randomUUID(), "c", "w", "d", p.fingerprint(), new GridPos(0, 0, 0), "wrong-settings");
        assertThrows(IllegalArgumentException.class, () -> engine.start(p, wrong, s));
        assertThrows(IllegalArgumentException.class, () -> new Settings(-1, 5, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new Settings(2, 6, true, 0));
        assertThrows(IllegalArgumentException.class, () -> new Settings(2, 5, true, 9));
        assertEquals(new Settings(2, 5, true, 0), s);
    }

    @Test void inputAndSnapshotsDefensivelyCopyCollections() {
        var h = new Harness(plan(12, 5, target(7, 0, 0)));
        h.available.put(7, PlacementAvailability.READY); TickInput input = h.input();
        h.available.clear(); assertTrue(h.engine.tick(input).placement().isPresent());
        assertThrows(UnsupportedOperationException.class, () -> input.availability().clear());
        assertThrows(UnsupportedOperationException.class, () -> h.engine.snapshot().unfinishedTargetIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> h.engine.snapshot().pendingTargetIds().clear());
    }
}
