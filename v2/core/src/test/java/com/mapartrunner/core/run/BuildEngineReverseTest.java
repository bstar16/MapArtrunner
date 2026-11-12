package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.traversal.LanePlanner;
import com.mapartrunner.core.traversal.LanePlanner.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;

import static com.mapartrunner.core.run.BuildEngine.*;
import static com.mapartrunner.core.run.BuildEngineTest.*;
import static com.mapartrunner.core.run.ForwardTraversal.*;
import static com.mapartrunner.core.run.RunLifecycle.*;
import static org.junit.jupiter.api.Assertions.*;

/** Canonical pose samples exercise source policy only; these are not native travel evidence. */
class BuildEngineReverseTest {
    @Test void reverseTransitCleansCanonicalStripeButRefusesOtherStripe() {
        var h = new Harness(plan(12, 15, target(900, 0, 2), target(863, 4, 7), target(826, 4, 12)));
        forward(h, Set.of(863), true);
        assertEquals(Substage.TRANSIT, state(h).substage());
        for (int k = 0; k < 3; k++) drive(h, Set.of(863));
        h.available.put(863, PlacementAvailability.READY); h.available.put(826, PlacementAvailability.READY);
        var cleaned = sample(h, new PlayerObservation(4.5, 1, 12.5, 90), Set.of(863, 826));
        assertEquals(826, cleaned.placement().orElseThrow().targetId());
        var refused = sample(h, new PlayerObservation(4.5, 1, 12.5, 90), Set.of(863));
        assertTrue(refused.placement().isEmpty());
        assertEquals(List.of(863), refused.snapshot().unfinishedTargetIds());
        assertTrue(refused.snapshot().pendingTargetIds().isEmpty());
    }

    @Test void reverseStripeRefusesEarlierReadyWorkAndLaterIssuesIt() {
        var route = forward(12, 15, Set.of(900, 863)); var h = route.h;
        while (state(h).substage() != Substage.NEXT_ENTRY || state(h).laneIndex() != 1) drive(h, route.unfinished);
        h.available.put(900, PlacementAvailability.READY); h.available.put(863, PlacementAvailability.READY);
        assertEquals(863, drive(h, route.unfinished).placement().orElseThrow().targetId());
        drive(h, Set.of(900));
        var refused = drive(h, Set.of(900));
        assertTrue(refused.placement().isEmpty());
        assertEquals(List.of(900), refused.snapshot().unfinishedTargetIds());
        assertFalse(refused.snapshot().pendingTargetIds().contains(900));
        boolean issued = false;
        for (int k = 0; k < 60 && !issued; k++) {
            var d = drive(h, Set.of(900));
            if (d.placement().isPresent()) { assertEquals(900, d.placement().orElseThrow().targetId()); issued = true; }
        }
        assertTrue(issued);
        assertEquals(0, state(h).laneIndex());
    }

    @Test void northCheckpointRetainsCanonicalXStripeBeyondTurnRectangle() {
        var h = new Harness(plan(12, 20, target(900, 0, 2), target(863, 0, 7),
                target(826, 0, 12), target(789, 0, 17), target(10, 9, 12)));
        forward(h, Set.of(900), true);
        while (state(h).substage() != Substage.NEXT_ENTRY || state(h).laneIndex() != 2) drive(h, Set.of(900));
        assertEquals(Direction.NORTH, state(h).entry().lane().direction());
        assertEquals(10, state(h).entry().lane().corridor().minZ());
        h.available.put(10, PlacementAvailability.READY);
        var repaired = drive(h, Set.of(900, 10));
        assertEquals(10, repaired.placement().orElseThrow().targetId());
        assertTrue(repaired.snapshot().unfinishedTargetIds().contains(900));
        assertFalse(repaired.snapshot().pendingTargetIds().contains(900));
    }

    @Test void middleOnlyReverseReachesWestXBeforeEastBuild() {
        var route = forward(12, 15, Set.of(863));
        var h = route.h;
        var source = state(h);
        assertEquals(Pass.REVERSE, source.pass());
        assertEquals("TRANSIT", source.substage().name());
        assertEquals(2, source.laneIndex());
        assertEquals(Direction.WEST, source.entry().lane().direction());
        assertEquals(new GridPos(11, 0, 12), source.entry().lane().start());
        assertEquals(new GridPos(0, 0, 12), source.entry().lane().end());
        assertEquals(Activity.SHIFT, h.engine.snapshot().lifecycle().activity().orElseThrow());
        for (int i = 0; i < 4; i++) drive(h, route.unfinished);
        assertEquals(Substage.ENDPOINT, state(h).substage());
        assertEquals(0, state(h).transitionTicks());
        while (state(h).substage() != Substage.NEXT_ENTRY) drive(h, route.unfinished);
        var destination = state(h).entry().lane();
        assertEquals(new GridPos(0, 0, 7), destination.start());
        assertEquals(Direction.EAST, destination.direction());
        sample(h, new PlayerObservation(.76, 1, 7.5, -90), route.unfinished);
        assertEquals(Stage.ENTER_POSITION, state(h).entry().stage());
        sample(h, new PlayerObservation(.5, 1, 7.76, -90), route.unfinished);
        assertEquals(Stage.ENTER_POSITION, state(h).entry().stage());
        assertEquals(Activity.SHIFT, h.engine.snapshot().lifecycle().activity().orElseThrow());
        sample(h, new PlayerObservation(.5, 1, 7.5, -90), route.unfinished);
        sample(h, new PlayerObservation(.5, 1, 7.5, -90), route.unfinished);
        assertEquals(Substage.NEXT_ENTRY, state(h).substage());
        assertEquals(1, state(h).entry().consecutiveYawAlignedTicks());
        sample(h, new PlayerObservation(.5, 1, 7.5, -90), route.unfinished);
        assertEquals(Substage.LANE, state(h).substage());
        assertEquals(Activity.BUILD, h.engine.snapshot().lifecycle().activity().orElseThrow());
        assertSame(source.entry().lane().expectedFloor(), state(h).entry().lane().expectedFloor());
    }

    @ParameterizedTest @ValueSource(ints = {3, 4})
    void onlyNeededXCorrectionsAndAdjacentCheckpoints(int last) {
        var route = forward(12, (last + 1) * 5, last == 3 ? Set.of(863) : Set.of(789, 863));
        var builds = new ArrayList<Integer>();
        var transitSources = new ArrayList<Integer>();
        var hops = new ArrayList<Integer>();
        String previous = "";
        while (activity(route.h) != Activity.VERIFY) {
            var s = state(route.h);
            String key = s.substage() + ":" + s.laneIndex();
            if (!key.equals(previous)) {
                if (s.substage().name().equals("TRANSIT")) transitSources.add(s.laneIndex());
                if (s.substage() == Substage.NEXT_ENTRY) hops.add(s.laneIndex());
                if (s.substage() == Substage.LANE) builds.add(s.laneIndex());
            }
            previous = key;
            drive(route.h, route.unfinished);
        }
        assertEquals(last == 3 ? List.of() : List.of(4, 3), transitSources);
        assertEquals(last == 3 ? List.of(1) : List.of(3, 1), builds);
        assertEquals(last == 3 ? List.of(2, 1) : List.of(3, 2, 1), hops);
    }

    @Test void literalLane20To0FinishesOneBoundedReverseRoute() {
        var route = forward(128, 105, Set.of(900));
        var h = route.h;
        assertEquals(Pass.REVERSE, state(h).pass());
        var destinations = new ArrayList<Integer>();
        var buildLanes = new ArrayList<Integer>();
        int xDistance = 0;
        while (activity(h) != Activity.VERIFY) {
            var before = state(h);
            if (before.substage().name().equals("TRANSIT") && before.entry().stage() == Stage.WALK) {
                xDistance += Math.abs(before.entry().lane().end().x() - before.entry().lane().start().x());
            }
            if (before.substage() == Substage.LANE && before.entry().stage() == Stage.WALK) buildLanes.add(before.laneIndex());
            if (before.substage() == Substage.SHIFT) {
                assertEquals(Direction.NORTH, before.entry().lane().direction());
                assertEquals(before.entry().lane().start().x(), before.entry().lane().end().x());
                assertTrue(before.entry().lane().start().z() - before.entry().lane().end().z() <= 5);
            }
            var d = drive(h, route.unfinished);
            var after = state(h);
            if (before.substage() == Substage.NEXT_ENTRY && d.movement().orElseThrow().status() == Status.READY_TO_WALK) {
                assertEquals(0, after.transitionTicks());
                assertEquals(before.entry().lane().start().x() + .5, h.player.orElseThrow().x());
                assertEquals(before.entry().lane().start().z() + .5, h.player.orElseThrow().z());
                assertEquals(1, before.entry().consecutiveYawAlignedTicks());
                destinations.add(before.laneIndex());
            }
        }
        assertEquals(java.util.stream.IntStream.iterate(19, i -> i >= 0, i -> i - 1).boxed().toList(), destinations);
        assertTrue(xDistance <= 127);
        assertEquals(List.of(0), buildLanes);
        assertEquals(List.of(900), h.engine.snapshot().unfinishedTargetIds());
        h.available.put(900, PlacementAvailability.READY);
        var issued = sample(h, h.player.orElseThrow(), route.unfinished).placement().orElseThrow();
        assertEquals(900, issued.targetId());
        h.observations = h.scan(STONE);
        var success = h.step();
        assertEquals(Outcome.SUCCESS, success.snapshot().lifecycle().outcome().orElseThrow());
        assertEquals(Reason.VERIFIED, success.snapshot().diagnostic().orElseThrow().reason());
        assertTrue(success.movement().isEmpty()); assertTrue(success.placement().isEmpty());
    }

    @Test void stationaryHopCannotRenew120() {
        var route = forward(12, 20, Set.of(863));
        var h = route.h;
        assertEquals(Substage.ENDPOINT, state(h).substage());
        while (state(h).substage() != Substage.SHIFT || state(h).entry().stage() != Stage.WALK) drive(h, route.unfinished);
        var stationary = h.player.orElseThrow();
        while (state(h).transitionTicks() < 120) {
            var held = sample(h, stationary, route.unfinished);
            assertEquals(Status.WALKING, held.movement().orElseThrow().status());
            assertEquals(0, state(h).walkNoProgressTicks());
        }
        h.available.put(863, PlacementAvailability.READY); observe(h, route.unfinished);
        assertTerminalRetained(h, h.step(), Reason.TRANSITION_TIMEOUT, List.of(863));
    }

    @Test void incompleteCheckpointEntryCannotRenew120() {
        var route = forward(12, 20, Set.of(863));
        var h = route.h;
        while (state(h).substage() != Substage.NEXT_ENTRY) drive(h, route.unfinished);
        h.player = Optional.empty();
        while (state(h).transitionTicks() < 112) { observe(h, route.unfinished); assertRunning(h.step()); }
        sample(h, new PlayerObservation(1.5, 1, 12.5, 180), route.unfinished);
        assertEquals(Stage.ENTER_POSITION, state(h).entry().stage());
        sample(h, new PlayerObservation(.5, 1, 12.76, 180), route.unfinished);
        assertEquals(Stage.ENTER_POSITION, state(h).entry().stage());
        sample(h, new PlayerObservation(.5, 1, 12.5, 0), route.unfinished);
        sample(h, new PlayerObservation(.5, 1, 12.5, 180), route.unfinished);
        assertEquals(1, state(h).entry().consecutiveYawAlignedTicks());
        assertEquals(116, state(h).transitionTicks());
        h.player = Optional.empty();
        while (state(h).transitionTicks() < 120) { observe(h, route.unfinished); assertRunning(h.step()); }
        assertEquals(Substage.NEXT_ENTRY, state(h).substage());
        h.at(new PlayerObservation(.5, 1, 12.5, 180)); observe(h, route.unfinished);
        assertTerminalRetained(h, h.step(), Reason.TRANSITION_TIMEOUT, List.of(863));
    }

    @Test void stationaryTransitFailsWalkNoProgress40() {
        var route = forward(12, 15, Set.of(863));
        var h = route.h;
        for (int i = 0; i < 3; i++) drive(h, route.unfinished);
        var stationary = h.player.orElseThrow();
        for (int i = 1; i <= 39; i++) {
            sample(h, stationary, route.unfinished);
            assertEquals(i, state(h).walkNoProgressTicks());
            assertEquals(0, state(h).transitionTicks());
            assertFalse(state(h).entry().constantSprint());
        }
        h.available.put(863, PlacementAvailability.READY); observe(h, route.unfinished);
        assertTerminalRetained(h, h.step(), Reason.WALK_NO_PROGRESS, List.of(863));
    }

    @Test void progressingWideTransitCanExceed120ButFollowingHopCannot() {
        var route = forward(128, 15, Set.of(863));
        var h = route.h;
        for (int i = 0; i < 3; i++) drive(h, route.unfinished);
        for (int i = 1; i <= 130; i++) {
            var d = sample(h, new PlayerObservation(127.5 - i * .5, 1, 12.5, 90), route.unfinished);
            assertEquals(Status.WALKING, d.movement().orElseThrow().status());
            assertFalse(d.movement().orElseThrow().sprint());
            assertEquals(Substage.TRANSIT, state(h).substage());
            assertEquals(0, state(h).transitionTicks());
            assertEquals(0, state(h).walkNoProgressTicks());
        }
        sample(h, new PlayerObservation(1.5, 1, 12.5, 90), route.unfinished);
        assertEquals(Substage.ENDPOINT, state(h).substage());
        assertEquals(0, state(h).transitionTicks());
        var correcting = sample(h, new PlayerObservation(1.5, 1, 12.5, 90), route.unfinished);
        assertTrue(correcting.movement().orElseThrow().forward());
        assertEquals(1, state(h).transitionTicks());
        h.player = Optional.empty();
        while (state(h).transitionTicks() < 120) { observe(h, route.unfinished); assertRunning(h.step()); }
        observe(h, route.unfinished);
        assertTerminalRetained(h, h.step(), Reason.TRANSITION_TIMEOUT, List.of(863));
    }

    @Test void wholeAllowanceCannotRenewAcrossSuccessfulHops() {
        var route = forward(3, 15, Set.of(900));
        var h = route.h;
        var destinations = new ArrayList<Integer>();
        while (!(state(h).substage() == Substage.LANE && state(h).entry().stage() == Stage.WALK)) {
            var before = state(h);
            var d = drive(h, route.unfinished);
            if (before.substage() == Substage.NEXT_ENTRY && d.movement().orElseThrow().status() == Status.READY_TO_WALK) destinations.add(before.laneIndex());
        }
        assertEquals(List.of(1, 0), destinations);
        assertEquals(1200, BuildEngine.runLimit(h.plan, h.settings));
        h.player = Optional.empty();
        while (h.engine.snapshot().eligibleTicks() < 1199) { observe(h, route.unfinished); assertRunning(h.step()); }
        observe(h, route.unfinished);
        assertTerminalRetained(h, h.step(), Reason.RUN_TIMEOUT, List.of(900));
        assertEquals(1200, h.engine.snapshot().eligibleTicks());
    }

    @ParameterizedTest @ValueSource(ints = {39, 79})
    void completeDoesNotClearSupport39Or79(int debt) {
        var targets = new ArrayList<Target>();
        for (int z = 0; z < 15; z++) for (int x = 0; x < 12; x++) targets.add(target(z * 12 + x, x, z));
        for (String handoff : List.of("orientation", "transit", "hop", "final")) {
            var h = new Harness(new BuildPlan("support handoffs", new GridSize(12, 1, 15), targets));
            var unfinished = handoff.equals("orientation") || handoff.equals("final") ? Set.of(145) : Set.of(84);
            var route = forward(h, unfinished, false);
            var floor = state(h).entry().lane().expectedFloor();
            assertEquals(route.lanes.getFirst().expectedFloor(), floor);
            if (!handoff.equals("orientation")) drive(h, unfinished);
            if (handoff.equals("transit")) for (int i = 0; i < 3; i++) drive(h, unfinished);
            if (handoff.equals("hop")) {
                while (state(h).substage() != Substage.SHIFT || state(h).entry().stage() != Stage.WALK) drive(h, unfinished);
            }
            if (handoff.equals("final")) for (int i = 0; i < 3; i++) drive(h, unfinished);
            h.support = support(h.plan, AIR, debt == 39 ? Evidence.OBSERVED : Evidence.UNKNOWN);
            Lane lane = state(h).entry().lane();
            var held = handoff.equals("orientation") ? new PlayerObservation(9.5, 1, 12.5, -90)
                    : handoff.equals("hop") ? pose(lane.start(), lane.direction()) : new PlayerObservation(2.5, 1, 12.5, 90);
            for (int i = 1; i <= debt; i++) {
                var d = sample(h, held, unfinished);
                assertEquals(Status.HOLDING_SUPPORT, d.movement().orElseThrow().status());
                assertEquals(i, state(h).entry().supportHoldTicks());
            }
            var completed = sample(h, pose(lane.end(), lane.direction()), unfinished);
            assertTrue(completed.movement().isEmpty());
            assertEquals(debt, state(h).entry().supportHoldTicks(), handoff);
            assertSame(floor, state(h).entry().lane().expectedFloor());
            if (!handoff.equals("final")) {
                for (int i = 0; i < 3; i++) drive(h, unfinished);
                if (handoff.equals("transit")) for (int i = 0; i < 3; i++) drive(h, unfinished);
                assertEquals(debt, state(h).entry().supportHoldTicks(), handoff);
                observe(h, unfinished);
                assertTerminalRetained(h, h.step(), Reason.MOVEMENT_FAILED, unfinished.stream().sorted().toList());
                assertEquals("SUPPORT_TIMEOUT", h.engine.snapshot().diagnostic().orElseThrow().detail());
            } else assertEquals(Activity.VERIFY, activity(h));
        }
    }

    @Test void pauseRetainsReverseEntry() {
        var route = forward(12, 20, Set.of(863));
        var h = route.h;
        while (state(h).substage() != Substage.NEXT_ENTRY) drive(h, route.unfinished);
        drive(h, route.unfinished); drive(h, route.unfinished);
        var before = h.engine.snapshot();
        assertEquals(1, state(h).entry().consecutiveYawAlignedTicks());
        h.engine.pause();
        for (int i = 0; i < 200; i++) {
            observe(h, route.unfinished); var paused = h.step();
            assertEquals(before.traversal(), paused.snapshot().traversal());
            assertEquals(before.eligibleTicks(), paused.snapshot().eligibleTicks());
            assertTrue(paused.movement().isEmpty()); assertTrue(paused.placement().isEmpty());
        }
        h.engine.resume(h.context); drive(h, route.unfinished);
        assertEquals(before.eligibleTicks() + 1, h.engine.snapshot().eligibleTicks());
        assertEquals(0, state(h).transitionTicks());
        assertEquals(Substage.ENDPOINT, state(h).substage()); // Clean checkpoint2, still targeting1.
    }

    @Test void noLeftoversBypassesReverse() {
        var route = forward(12, 15, Set.of());
        assertEquals(Activity.VERIFY, activity(route.h));
        assertEquals(Pass.COMPLETE, state(route.h).pass());
        assertEquals(0, route.h.engine.snapshot().verifyTicks());
        assertTrue(route.h.engine.snapshot().lifecycle().outcome().isEmpty());
        route.h.observations = route.h.scan(STONE);
        assertEquals(Outcome.SUCCESS, route.h.step().snapshot().lifecycle().outcome().orElseThrow());
    }

    @Test void overlapRepairSkipsBuildOnArrival() {
        var canceled = forward(12, 20, Set.of(863, 900));
        var h = canceled.h;
        while (state(h).substage() != Substage.NEXT_ENTRY || state(h).laneIndex() != 1) drive(h, canceled.unfinished);
        h.available.put(863, PlacementAvailability.READY);
        assertEquals(863, drive(h, canceled.unfinished).placement().orElseThrow().targetId());
        drive(h, Set.of(900));
        var arrived = drive(h, Set.of(900));
        assertEquals(Activity.SHIFT, arrived.snapshot().lifecycle().activity().orElseThrow());
        assertEquals(Substage.TRANSIT, state(h).substage());
        assertEquals(1, state(h).laneIndex());
        assertEquals(List.of(900), h.engine.snapshot().unfinishedTargetIds());
        while (activity(h) != Activity.VERIFY) drive(h, Set.of(900));

        // The shortened last stride overlaps both canonical corridors at z4.
        var overlap = new Harness(plan(12, 9, target(900, 0, 0), target(863, 0, 4)));
        forward(overlap, Set.of(900, 863), false);
        overlap.available.put(863, PlacementAvailability.READY);
        assertEquals(863, drive(overlap, Set.of(900, 863)).placement().orElseThrow().targetId());
        assertEquals(1, state(overlap).laneIndex());
        assertEquals(Pass.REVERSE, state(overlap).pass());
        for (int i = 0; i < 3; i++) drive(overlap, Set.of(900));
        assertEquals(Substage.TRANSIT, state(overlap).substage());
        assertEquals(Stage.ENTER_POSITION, state(overlap).entry().stage());
        assertEquals(List.of(900), overlap.engine.snapshot().unfinishedTargetIds());
    }

    @Test void reopenedHigherLaneDoesNotRestartReverse() {
        var route = forward(12, 20, Set.of(863));
        var h = route.h;
        while (state(h).substage() != Substage.LANE) drive(h, route.unfinished);
        assertEquals(1, state(h).laneIndex());
        var done = drive(h, Set.of(863, 789));
        assertEquals(Activity.VERIFY, done.snapshot().lifecycle().activity().orElseThrow());
        assertEquals(List.of(789, 863), done.snapshot().unfinishedTargetIds());
        sample(h, h.player.orElseThrow(), Set.of(863, 789));
        assertEquals(Activity.VERIFY, activity(h));
        assertEquals(Pass.COMPLETE, state(h).pass());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void reverseExhaustionNeedsLaterCompleteObservedEvidence(int mode) {
        var invalid = forward(3, 15, Set.of(900)); var h = invalid.h;
        while (activity(h) != Activity.VERIFY) drive(h, invalid.unfinished);
        for (int i = 0; i < 79; i++) {
            Harness current = h;
            h.observations = h.plan.targets().stream().filter(t -> mode != 0 || t.id() != 900)
                    .map(t -> observation(current, t.id(), current.nextTick, STONE, t.id() != 900 ? Evidence.OBSERVED
                            : mode == 1 ? Evidence.UNKNOWN : Evidence.PREDICTED)).toList();
            assertRunning(h.step());
        }
        observe(h, Set.of(900));
        assertTerminalRetained(h, h.step(), Reason.VERIFY_TIMEOUT, List.of(900));
    }

    @ParameterizedTest @CsvSource({"1,1", "3,1", "1,2", "3,2", "1,3", "3,3", "1,4", "3,4", "1,5", "3,5", "1,9", "3,9", "1,11", "3,11"})
    void shortAndNarrowReversePreservesCanonicalGeometry(int width, int depth) {
        var targets = new ArrayList<Target>();
        int first = Math.min(2, depth - 1), last = depth <= 5 ? first : depth - 3;
        for (int z = first, i = 0;; z = Math.min(z + 5, last), i++) {
            targets.add(target(900 - i * 37, 0, z)); if (z == last) break;
        }
        var route = forward(new Harness(new BuildPlan("narrow", new GridSize(width, 1, depth), targets)), Set.of(900), true);
        var h = route.h;
        var floor = state(h).entry().lane().expectedFloor();
        assertEquals(route.lanes.getFirst().expectedFloor(), floor);
        while (activity(h) != Activity.VERIFY) {
            assertSame(floor, state(h).entry().lane().expectedFloor());
            assertEquals(route.lanes.get(state(h).laneIndex()).id(), state(h).entry().lane().id());
            if (state(h).substage() == Substage.LANE) {
                Lane canonical = route.lanes.get(state(h).laneIndex());
                assertEquals(canonical.end(), state(h).entry().lane().start());
                assertEquals(canonical.start(), state(h).entry().lane().end());
                assertEquals(canonical.corridor(), state(h).entry().lane().corridor());
            }
            drive(h, route.unfinished);
        }
        assertEquals(List.of(900), h.engine.snapshot().unfinishedTargetIds());
    }

    private static void assertTerminalRetained(Harness h, TickDecision failed, Reason reason, List<Integer> ids) {
        assertReason(failed, reason);
        assertEquals(ids, failed.snapshot().unfinishedTargetIds());
        assertEquals(ids, failed.snapshot().diagnostic().orElseThrow().targetIds());
        var terminal = h.engine.snapshot();
        for (int i = 0; i < 3; i++) {
            var ignored = h.step(); assertEquals(terminal, ignored.snapshot());
            assertTrue(ignored.movement().isEmpty()); assertTrue(ignored.placement().isEmpty());
        }
    }

    private record Route(Harness h, List<Lane> lanes, Set<Integer> unfinished) { }

    private static Route forward(int width, int depth, Set<Integer> unfinished) {
        var targets = new ArrayList<Target>();
        for (int z = 2, i = 0; z < depth; z += 5, i++) targets.add(target(900 - 37 * i, 0, z));
        var h = new Harness(new BuildPlan("reverse", new GridSize(width, 1, depth), targets));
        return forward(h, unfinished, true);
    }

    private static Route forward(Harness h, Set<Integer> unfinished, boolean exhaust) {
        var lanes = new LanePlanner().plan(h.plan, 2, 5);
        h.support = support(h.plan, STONE, Evidence.OBSERVED);
        h.at(pose(lanes.getFirst().start(), lanes.getFirst().direction()));
        Set<Integer> all = new HashSet<>(); h.plan.targets().forEach(t -> all.add(t.id()));
        while (!(state(h).pass() == Pass.FORWARD && state(h).laneIndex() == lanes.size() - 1
                && state(h).substage() == Substage.LANE && state(h).entry().stage() == Stage.WALK)) drive(h, all);
        if (exhaust) sample(h, pose(lanes.getLast().end(), lanes.getLast().direction()), unfinished);
        return new Route(h, lanes, unfinished);
    }

    private static ForwardTraversal.Snapshot state(Harness h) { return h.engine.snapshot().traversal().orElseThrow(); }
    private static Activity activity(Harness h) { return h.engine.snapshot().lifecycle().activity().orElseThrow(); }
    private static PlayerObservation pose(GridPos p, Direction d) { return new PlayerObservation(p.x() + .5, p.y() + 1, p.z() + .5, d.yawDegrees()); }
    private static TickDecision drive(Harness h, Set<Integer> unfinished) {
        LaneEntry e = state(h).entry();
        return sample(h, pose(e.stage() == Stage.WALK ? e.lane().end() : e.lane().start(), e.lane().direction()), unfinished);
    }
    private static TickDecision sample(Harness h, PlayerObservation player, Set<Integer> unfinished) {
        h.at(player);
        observe(h, unfinished);
        var d = h.step(); assertRunning(d); return d;
    }
    private static void observe(Harness h, Set<Integer> unfinished) {
        h.observations = h.plan.targets().stream().map(t -> observation(h, t.id(), h.nextTick,
                unfinished.contains(t.id()) ? AIR : STONE, Evidence.OBSERVED)).toList();
    }
}
