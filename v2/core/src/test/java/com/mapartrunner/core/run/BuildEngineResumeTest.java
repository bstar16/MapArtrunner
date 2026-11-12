package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.traversal.LanePlanner;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.mapartrunner.core.run.BuildEngine.*;
import static com.mapartrunner.core.run.BuildEngineTest.*;
import static com.mapartrunner.core.run.RunLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildEngineResumeTest {
    @Test void clippedEntriesRepairBehindStartButRetainOtherStripeForItsOwnPass() {
        for (int index : List.of(0, 1)) {
            int start = index == 0 ? 40 : 60, z = index == 0 ? 2 : 7, other = index == 0 ? 7 : 2;
            var h = new Harness(plan(100, 10, target(1, start, z), target(2, start + (index == 0 ? -1 : 1), z),
                    target(3, index == 0 ? 70 : 20, z), target(4, start, other), target(900, 0, other)));
            h.support = support(h.plan, STONE, Evidence.OBSERVED);
            h.observations = h.plan.targets().stream().map(t -> observation(h, t.id(), h.nextTick,
                    Set.of(1, 3, 900).contains(t.id()) ? AIR : STONE, Evidence.OBSERVED)).toList();
            h.at(new PlayerObservation(start + .5, 1, z + .5, index == 0 ? -90 : 90));
            h.step();
            assertEquals(new GridPos(start, 0, z), h.engine.snapshot().laneEntry().orElseThrow().lane().start());
            h.observations = List.of(); h.step(); h.step();
            h.observations = List.of(observation(h, 2, h.nextTick, AIR, Evidence.OBSERVED),
                    observation(h, 4, h.nextTick, AIR, Evidence.OBSERVED));
            h.available.put(2, PlacementAvailability.READY); h.available.put(4, PlacementAvailability.READY);
            assertEquals(2, h.step().placement().orElseThrow().targetId());
            h.observations = List.of(observation(h, 2, h.nextTick, STONE, Evidence.OBSERVED));
            var refused = h.step(); assertTrue(refused.placement().isEmpty());
            assertTrue(refused.snapshot().unfinishedTargetIds().containsAll(List.of(4, 900)));
            assertFalse(refused.snapshot().pendingTargetIds().contains(4));
            h.available.replaceAll((id, value) -> id == 900 ? PlacementAvailability.READY : PlacementAvailability.NO_VALID_HIT);
            boolean issued = false;
            for (int k = 0; k < 80 && !issued; k++) {
                var entry = h.engine.snapshot().laneEntry().orElseThrow();
                var at = entry.stage() == Stage.WALK ? entry.lane().end() : entry.lane().start();
                h.at(new PlayerObservation(at.x() + .5, 1, at.z() + .5, entry.lane().direction().yawDegrees()));
                h.observations = h.plan.targets().stream().map(t -> observation(h, t.id(), h.nextTick,
                        t.id() == 900 ? AIR : STONE, Evidence.OBSERVED)).toList();
                var d = h.step(); assertRunning(d);
                if (d.placement().isPresent()) {
                    assertEquals(900, d.placement().orElseThrow().targetId()); issued = true;
                }
            }
            assertTrue(issued, "Earlier retained work must remain issuable on its rightful stripe/reverse");
            for (int k = 0; k < 80 && h.engine.snapshot().lifecycle().activity().orElseThrow() != Activity.VERIFY; k++) {
                var entry = h.engine.snapshot().laneEntry().orElseThrow();
                var at = entry.stage() == Stage.WALK ? entry.lane().end() : entry.lane().start();
                h.at(new PlayerObservation(at.x() + .5, 1, at.z() + .5, entry.lane().direction().yawDegrees()));
                h.observations = h.scan(STONE); assertRunning(h.step());
            }
            assertEquals(Activity.VERIFY, h.engine.snapshot().lifecycle().activity().orElseThrow());
            h.observations = h.scan(STONE);
            assertEquals(Outcome.SUCCESS, h.step().snapshot().lifecycle().outcome().orElseThrow());
        }
    }

    @Test void nearLane20RetainsLane0() {
        var h = new Harness(twentyOneLanes());
        var canonical = new LanePlanner().plan(h.plan, 2, 5);
        h.observations = h.plan.targets().stream().map(t -> observation(h, t.id(), h.nextTick,
                t.id() == 900 || t.id() == 160 ? AIR : STONE, Evidence.OBSERVED)).toList();
        h.at(new PlayerObservation(1.5, 1, 102.5, -90));
        var selected = h.step(); assertRunning(selected);
        var traversal = selected.snapshot().traversal().orElseThrow();
        assertEquals(20, traversal.laneIndex());
        assertEquals(20, traversal.entry().lane().id());
        assertEquals(List.of(160, 900), selected.snapshot().unfinishedTargetIds());
        assertEquals(canonical.get(20).expectedFloor(), traversal.entry().lane().expectedFloor());
        assertTrue(traversal.entry().lane().expectedFloor().containsTargetId(900));
        assertTrue(traversal.entry().lane().expectedFloor().containsTargetId(160));
        assertEquals(21, traversal.entry().lane().expectedFloor().targetsByPosition().size());
        assertEquals(Stage.ENTER_YAW, traversal.entry().stage());
        assertEquals(1, traversal.entry().entryElapsedTicks());
        h.observations = List.of(); h.step(); h.step();
        h.at(new PlayerObservation(2.5, 1, 102.5, -90));
        var exhausted = h.step(); assertRunning(exhausted);
        assertEquals(Activity.BUILD, exhausted.snapshot().lifecycle().activity().orElseThrow());
        var reverse = exhausted.snapshot().traversal().orElseThrow();
        assertEquals(ForwardTraversal.Pass.REVERSE, reverse.pass());
        assertEquals(20, reverse.laneIndex());
        assertEquals(canonical.get(20).end(), reverse.entry().lane().start());
        assertEquals(canonical.get(20).start(), reverse.entry().lane().end());
        assertEquals(canonical.get(20).expectedFloor(), reverse.entry().lane().expectedFloor());
        assertTrue(exhausted.snapshot().unfinishedTargetIds().contains(900));
        assertEquals(List.of(160, 900), exhausted.snapshot().unfinishedTargetIds());
        assertTrue(exhausted.snapshot().lifecycle().outcome().isEmpty());
        assertTrue(exhausted.movement().isEmpty());
    }

    @Test void freshStartRemainsLane0() {
        var h = new Harness(twentyOneLanes());
        h.at(new PlayerObservation(1.5, 1, 102.5, -90));
        var selected = h.step();
        assertReason(selected, Reason.COARSE_TRAVEL_UNAVAILABLE);
        assertTrue(selected.snapshot().diagnostic().orElseThrow().traversalDetail().startsWith("laneIndex=0,"));
        assertEquals(21, selected.snapshot().unfinishedTargetIds().size());
    }

    @Test void partialEastAndWestKeepCanonicalEnds() {
        var plan = plan(100, 10, target(71, 0, 2), target(3, 40, 2), target(902, 70, 2),
                target(4, 99, 7), target(61, 60, 7), target(8, 20, 7));
        var canonical = new LanePlanner().plan(plan, 2, 5);
        for (int index : List.of(0, 1)) {
            var h = new Harness(plan);
            Set<Integer> unfinished = index == 0 ? Set.of(3, 902) : Set.of(61, 8);
            h.observations = plan.targets().stream().map(t -> observation(h, t.id(), h.nextTick,
                    unfinished.contains(t.id()) ? AIR : STONE, Evidence.OBSERVED)).toList();
            h.at(new PlayerObservation(index == 0 ? 40.5 : 60.5, 1, index == 0 ? 2.5 : 7.5,
                    index == 0 ? -90 : 90));
            var selected = h.step(); assertRunning(selected);
            var lane = selected.snapshot().laneEntry().orElseThrow().lane();
            assertEquals(index, selected.snapshot().traversal().orElseThrow().laneIndex());
            assertEquals(index == 0 ? new GridPos(40, 0, 2) : new GridPos(60, 0, 7), lane.start());
            assertEquals(index == 0 ? new GridPos(99, 0, 2) : new GridPos(0, 0, 7), lane.end());
            assertEquals(canonical.get(index).direction(), lane.direction());
            assertEquals(canonical.get(index).corridor(), lane.corridor());
            assertEquals(canonical.get(index).expectedFloor(), lane.expectedFloor());
            assertEquals(unfinished, new HashSet<>(selected.snapshot().unfinishedTargetIds()));
            h.engine.pause(); var paused = h.engine.snapshot().traversal();
            h.engine.resume(h.context); h.observations = List.of(); h.step();
            assertEquals(paused.orElseThrow().entry().lane(), h.engine.snapshot().laneEntry().orElseThrow().lane());
        }
    }

    @Test void distanceTieIsSixteenBlocks() {
        for (double[] sample : List.of(new double[]{20.0, 1, 0}, new double[]{20.5, 1, 0},
                new double[]{20.625, 1, 4}, new double[]{22.5, 100, 0})) {
            var h = new Harness(plan(3, 25, target(91, 1, 2), target(7, 1, 22), target(40, 1, 12)));
            h.observations = List.of(observation(h, 91, h.nextTick, AIR, Evidence.OBSERVED),
                    observation(h, 7, h.nextTick, AIR, Evidence.OBSERVED), observation(h, 40, h.nextTick, STONE, Evidence.OBSERVED));
            h.at(new PlayerObservation(1.5, sample[1], sample[0], -90));
            var selected = h.step();
            if (sample[2] == 0) {
                assertReason(selected, Reason.COARSE_TRAVEL_UNAVAILABLE);
                assertTrue(selected.snapshot().diagnostic().orElseThrow().traversalDetail().startsWith("laneIndex=0,"));
            } else {
                assertRunning(selected);
                assertEquals(4, selected.snapshot().traversal().orElseThrow().laneIndex());
            }
        }
        for (int progress : List.of(16, 17)) {
            var h = new Harness(plan(100, 5, target(9, progress, 2), target(8, 99, 2)));
            h.observations = List.of(observation(h, 9, h.nextTick, AIR, Evidence.OBSERVED),
                    observation(h, 8, h.nextTick, STONE, Evidence.OBSERVED));
            var selected = h.step(); assertRunning(selected);
            assertEquals(progress == 16 ? 16 : 0, selected.snapshot().laneEntry().orElseThrow().lane().start().x());
        }
    }

    @Test void firstPlayerSelectsAfterCurrentObservationRouting() {
        for (int uncertain = 0; uncertain < 3; uncertain++) {
            var h = new Harness(twentyOneLanes());
            h.player = Optional.empty(); h.observations = h.scan(STONE);
            var waiting = h.step(); assertRunning(waiting);
            assertTrue(waiting.snapshot().unfinishedTargetIds().isEmpty());
            assertEquals(0, waiting.snapshot().laneEntry().orElseThrow().entryElapsedTicks());
            int mode = uncertain;
            h.observations = h.plan.targets().stream().filter(t -> mode != 2 || t.id() != 160)
                    .map(t -> observation(h, t.id(), h.nextTick, STONE, t.id() != 160 ? Evidence.OBSERVED
                            : mode == 0 ? Evidence.UNKNOWN : Evidence.PREDICTED)).toList();
            h.at(new PlayerObservation(1.5, 1, 102.5, -90));
            var selected = h.step(); assertRunning(selected);
            assertEquals(20, selected.snapshot().traversal().orElseThrow().laneIndex());
            assertEquals(Activity.BUILD, selected.snapshot().lifecycle().activity().orElseThrow());
            assertEquals(2, selected.snapshot().eligibleTicks());
            assertEquals(mode == 2 ? List.of() : List.of(160), selected.snapshot().unfinishedTargetIds());
            assertEquals(Stage.ENTER_YAW, selected.snapshot().laneEntry().orElseThrow().stage());
        }
    }

    @Test void allCorrectStartNeedsLaterFullScan() {
        var h = new Harness(plan(12, 5, target(91, 0, 0), target(7, 11, 4)));
        h.observations = h.scan(STONE);
        var selected = h.step(); assertRunning(selected);
        assertEquals(Activity.VERIFY, selected.snapshot().lifecycle().activity().orElseThrow());
        assertEquals(0, selected.snapshot().verifyTicks());
        assertTrue(selected.movement().isEmpty()); assertTrue(selected.placement().isEmpty());
        h.observations = List.of(observation(h, 91, h.nextTick, STONE, Evidence.OBSERVED));
        assertRunning(h.step());
        h.observations = List.of(observation(h, 91, h.nextTick, STONE, Evidence.OBSERVED),
                observation(h, 7, h.nextTick, STONE, Evidence.PREDICTED));
        assertRunning(h.step());
        h.observations = h.scan(STONE);
        assertEquals(Outcome.SUCCESS, h.step().snapshot().lifecycle().outcome().orElseThrow());
    }

    @Test void firstSelectionCoarseFailureReportsSelectedLane() {
        var h = new Harness(twentyOneLanes());
        h.observations = h.plan.targets().stream().map(t -> observation(h, t.id(), h.nextTick,
                t.id() == 160 ? AIR : STONE, Evidence.OBSERVED)).toList();
        h.at(new PlayerObservation(10, 1, 102.5, -90));
        var failed = h.step(); assertReason(failed, Reason.COARSE_TRAVEL_UNAVAILABLE);
        var detail = failed.snapshot().diagnostic().orElseThrow().traversalDetail();
        assertTrue(detail.startsWith("laneIndex=20, substage=LANE, transitionTicks=0, stage=ENTER_POSITION,"));
        assertTrue(detail.contains("stageElapsedTicks=0, entryElapsedTicks=0,"));
        assertEquals(List.of(160), failed.snapshot().diagnostic().orElseThrow().targetIds());
    }

    private static BuildPlan twentyOneLanes() {
        var targets = new ArrayList<Target>();
        for (int i = 0; i < 21; i++) targets.add(target(900 - i * 37, 1, 2 + i * 5));
        return plan(3, 105, targets.toArray(Target[]::new));
    }
}
