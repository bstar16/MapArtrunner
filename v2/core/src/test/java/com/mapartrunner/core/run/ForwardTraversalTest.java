package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
import com.mapartrunner.core.run.ForwardTraversal.Pass;
import com.mapartrunner.core.run.ForwardTraversal.Substage;
import com.mapartrunner.core.traversal.LanePlanner;
import com.mapartrunner.core.traversal.LanePlanner.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ForwardTraversalTest {
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private static final SupportObservation UNKNOWN = new SupportObservation(Map.of());

    @Test void uncoveredUsefulTargetCannotBecomeCompletion() {
        var plan = plan(10);
        var lanes = new LanePlanner().plan(plan, 2, 5);
        var owner = new ForwardTraversal(List.of(lanes.getFirst()), plan.dimensions(), true);
        var outside = plan.targets().stream().filter(t -> t.relativePos().z() == 7).findFirst().orElseThrow();
        var result = owner.advance(new PlayerObservation(.5, 1, 2.5, -90), UNKNOWN, Set.of(), List.of(outside));
        assertTrue(result.uncoveredTargets()); assertFalse(result.traversalComplete());
        assertTrue(result.movement().isEmpty()); assertTrue(result.decisionInput().isEmpty());
        assertEquals(Pass.SELECTING, owner.snapshot().pass());
    }

    @Test void selectedDecisionInputRetainsGeometryAndPreFailureCounters() {
        var plan = plan(10); var lanes = new LanePlanner().plan(plan, 2, 5);
        var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
        assertTrue(owner.advanceWithoutPlayer().decisionInput().isEmpty());
        var target = plan.targets().stream().filter(t -> t.relativePos().equals(new GridPos(6, 0, 7))).findFirst().orElseThrow();
        var player = new PlayerObservation(6.5, 1, 7.5, 90);
        var selected = owner.advance(player, UNKNOWN, Set.of(), List.of(target));
        var canonical = lanes.get(1);
        var partial = new Lane(1, Direction.WEST, new GridPos(6, 0, 7), new GridPos(0, 0, 7),
                canonical.corridor(), canonical.expectedFloor());
        assertEquals(new LaneEntry(partial, Stage.ENTER_POSITION, 0, 0, 0, 0, 0, Set.of(), true),
                selected.decisionInput().orElseThrow());
        assertEquals(1, owner.snapshot().laneIndex());
        assertEquals(Stage.ENTER_YAW, owner.snapshot().entry().stage());
        owner.advance(player, UNKNOWN, Set.of(), List.of(target));
        owner.advance(player, UNKNOWN, Set.of(), List.of(target));
        var missing = observed(plan, AIR);
        for (int i = 0; i < 39; i++) owner.advance(player, missing, Set.of(), List.of(target));
        var before = owner.snapshot().entry();
        assertEquals(39, before.supportHoldTicks());
        var failed = owner.advance(player, missing, Set.of(), List.of(target));
        assertEquals(Failure.SUPPORT_TIMEOUT, failed.movement().orElseThrow().failure());
        assertEquals(before, failed.decisionInput().orElseThrow());
        assertEquals(partial, failed.decisionInput().orElseThrow().lane());
        assertEquals(0, failed.movement().orElseThrow().nextSupportHoldTicks());
        assertEquals(0, owner.snapshot().entry().supportHoldTicks());
        assertEquals(new GridPos(11, 0, 7), canonical.start());
    }

    @Test void partialEntryStillContinuesThroughCleanCanonicalLanes() {
        var plan = plan(10); var lanes = new LanePlanner().plan(plan, 2, 5);
        var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
        var target = plan.targets().stream().filter(t -> t.relativePos().equals(new GridPos(6, 0, 2))).findFirst().orElseThrow();
        var floor = observed(plan, STONE);
        owner.advance(new PlayerObservation(6.5, 1, 2.5, -90), floor, Set.of(), List.of(target));
        assertEquals(new GridPos(6, 0, 2), owner.snapshot().entry().lane().start());
        assertEquals(new GridPos(0, 0, 2), lanes.getFirst().start());
        advance(owner, new GridPos(6, 0, 2), Direction.EAST, floor, Set.of());
        advance(owner, new GridPos(6, 0, 2), Direction.EAST, floor, Set.of());
        advance(owner, lanes.getFirst().end(), Direction.EAST, floor, Set.of());
        ready(owner, lanes.getFirst().end(), Direction.EAST, floor, Set.of());
        ready(owner, lanes.getFirst().end(), Direction.SOUTH, floor, Set.of());
        owner.advance(new PlayerObservation(11.5, 1, 7.5, 0), floor, Set.of(), List.of());
        assertEquals(Substage.NEXT_ENTRY, owner.snapshot().substage());
        assertEquals(lanes.get(1), owner.snapshot().entry().lane());
        assertEquals(new GridPos(11, 0, 7), owner.snapshot().entry().lane().start());
    }

    @Test void movementDebtSurvivesHoldsAbsenceAndEntryUntilNextLaneIsReady() {
        var plan = plan(10); var lanes = new LanePlanner().plan(plan, 2, 5);
        var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
        var floor = observed(plan, STONE);
        ready(owner, lanes.getFirst().start(), Direction.EAST, floor, Set.of());
        for (int i = 0; i < 10; i++) advance(owner, lanes.getFirst().start(), Direction.EAST, floor, Set.of());
        assertEquals(10, owner.snapshot().walkNoProgressTicks());
        for (int i = 0; i < 10; i++) owner.advanceWithoutPlayer();
        for (int i = 0; i < 3; i++) {
            var held = advance(owner, lanes.getFirst().start(), Direction.EAST, UNKNOWN, Set.of());
            assertEquals(Status.HOLDING_SUPPORT, held.movement().orElseThrow().status());
            assertEquals(i + 1, owner.snapshot().entry().supportHoldTicks());
            assertEquals(10, owner.snapshot().walkNoProgressTicks());
        }
        for (int i = 0; i < 3; i++) {
            var held = owner.advance(new PlayerObservation(.5, 1, 2.5, 0), floor, Set.of(), plan.targets());
            assertEquals(Status.HOLDING_YAW, held.movement().orElseThrow().status());
            assertEquals(i + 1, owner.snapshot().entry().yawHoldTicks());
            assertEquals(10, owner.snapshot().walkNoProgressTicks());
        }
        advance(owner, lanes.getFirst().start(), Direction.EAST, floor, Set.of());
        assertEquals(11, owner.snapshot().walkNoProgressTicks());
        advance(owner, lanes.getFirst().end(), Direction.EAST, floor, Set.of());
        owner.advance(new PlayerObservation(11, 1, 2.5, -90), floor, Set.of(), plan.targets());
        assertEquals(11, owner.snapshot().walkNoProgressTicks());
        ready(owner, lanes.getFirst().end(), Direction.EAST, floor, Set.of());
        ready(owner, lanes.getFirst().end(), Direction.SOUTH, floor, Set.of());
        for (int i = 0; i < 40; i++) {
            var shift = advance(owner, lanes.getFirst().end(), Direction.SOUTH, floor, Set.of());
            assertEquals(Status.WALKING, shift.movement().orElseThrow().status());
            assertFalse(shift.walkStalled());
            assertEquals(11, owner.snapshot().walkNoProgressTicks());
        }
        advance(owner, lanes.get(1).start(), Direction.SOUTH, floor, Set.of());
        owner.advance(new PlayerObservation(11, 1, 7.5, 90), floor, Set.of(), plan.targets());
        assertEquals(Substage.NEXT_ENTRY, owner.snapshot().substage());
        assertEquals(11, owner.snapshot().walkNoProgressTicks());
        ready(owner, lanes.get(1).start(), Direction.WEST, floor, Set.of());
        assertEquals(0, owner.snapshot().walkNoProgressTicks());
        for (int i = 0; i < 39; i++) advance(owner, lanes.get(1).start(), Direction.WEST, floor, Set.of());
        assertEquals(39, owner.snapshot().walkNoProgressTicks());
        owner.advance(new PlayerObservation(11.25, 1, 7.5, 90), floor, Set.of(), plan.targets());
        assertEquals(0, owner.snapshot().walkNoProgressTicks());
        owner.advance(new PlayerObservation(11.375, 1, 7.5, 90), floor, Set.of(), plan.targets());
        assertEquals(1, owner.snapshot().walkNoProgressTicks());
    }

    @Test void holdsCannotEarnCreditAndPolicyFailuresKeepPrecedence() {
        var plan = plan(10); var lanes = new LanePlanner().plan(plan, 2, 5);
        for (int guard = 0; guard < 4; guard++) {
            var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
            var floor = observed(plan, STONE);
            var pending = guard == 2 ? ids(plan) : Set.<Integer>of();
            ready(owner, lanes.getFirst().start(), Direction.EAST, floor, pending);
            for (int i = 0; i < 39; i++) advance(owner, lanes.getFirst().start(), Direction.EAST, floor, pending);
            int limit = guard == 0 ? 40 : guard == 3 ? 20 : 80;
            for (int i = 0; i < limit; i++) {
                var result = owner.advance(new PlayerObservation(.75, 1, 2.5, guard == 3 ? 0 : -90),
                        guard == 3 ? floor : guard == 1 ? UNKNOWN : observed(plan, AIR), pending, plan.targets());
                assertFalse(result.walkStalled());
                assertEquals(39, owner.snapshot().walkNoProgressTicks());
                var movement = result.movement().orElseThrow();
                assertEquals(i == limit - 1 ? Status.FAILED : guard == 3 ? Status.HOLDING_YAW : Status.HOLDING_SUPPORT,
                        movement.status());
                if (i == limit - 1) assertEquals(guard == 3 ? Failure.YAW_UNALIGNED : Failure.SUPPORT_TIMEOUT, movement.failure());
            }
        }
        var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
        var floor = observed(plan, STONE);
        ready(owner, lanes.getFirst().start(), Direction.EAST, floor, Set.of());
        for (int i = 0; i < 39; i++) advance(owner, lanes.getFirst().start(), Direction.EAST, floor, Set.of());
        owner.advance(new PlayerObservation(.75, 1, 2.5, -90), UNKNOWN, Set.of(), plan.targets());
        var stalled = advance(owner, lanes.getFirst().start(), Direction.EAST, floor, Set.of());
        assertTrue(stalled.walkStalled());
        assertEquals(40, owner.snapshot().walkNoProgressTicks());
        var terminal = owner.snapshot();
        assertTrue(owner.advanceWithoutPlayer().walkStalled());
        var repeated = advance(owner, lanes.getFirst().start(), Direction.EAST, floor, Set.of());
        assertTrue(repeated.walkStalled()); assertTrue(repeated.movement().isEmpty());
        assertEquals(terminal, owner.snapshot());
    }

    @Test
    void passDiagnosticsRetainForwardGeometryAndCounters() {
        BuildPlan plan = plan(10);
        var lanes = new LanePlanner().plan(plan, 2, 5);
        var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
        var support = observed(plan, STONE);
        var pending = ids(plan);
        var initial = owner.snapshot();
        assertEquals(Pass.SELECTING, initial.pass());
        assertEquals(0, initial.walkNoProgressTicks());
        assertEquals(new LaneEntry(lanes.getFirst(), Stage.ENTER_POSITION, 0, 0, 0, 0, 0, Set.of(), true), initial.entry());
        assertFalse(owner.advanceWithoutPlayer().walkStalled());
        assertEquals(initial, owner.snapshot());

        assertFalse(advance(owner, lanes.getFirst().start(), Direction.EAST, support, pending).walkStalled());
        assertEquals(Pass.FORWARD, owner.snapshot().pass());
        assertEquals(0, owner.snapshot().walkNoProgressTicks());
        assertEquals(new LaneEntry(lanes.getFirst(), Stage.ENTER_YAW, 0, 1, 0, 0, 0, pending, true), owner.snapshot().entry());
        assertFalse(advance(owner, lanes.getFirst().start(), Direction.EAST, support, pending).walkStalled());
        assertFalse(advance(owner, lanes.getFirst().start(), Direction.EAST, support, pending).walkStalled());
        assertFalse(advance(owner, lanes.getFirst().end(), Direction.EAST, support, pending).walkStalled());
        var endpoint = owner.snapshot();
        assertEquals(Pass.FORWARD, endpoint.pass());
        assertEquals(0, endpoint.walkNoProgressTicks());
        assertEquals(Substage.ENDPOINT, endpoint.substage());
        assertEquals(0, endpoint.transitionTicks());
        Lane first = lanes.getFirst();
        var point = new Lane(first.id(), first.direction(), first.end(), first.end(), first.corridor(), first.expectedFloor());
        assertEquals(new LaneEntry(point, Stage.ENTER_POSITION, 0, 0, 0, 0, 0, pending, false), endpoint.entry());
        var region = new Corridor(10, 11, 1, 8);
        assertEquals(Optional.of(region), endpoint.transitionRegion());
        assertFalse(owner.advanceWithoutPlayer().walkStalled());
        assertEquals(Pass.FORWARD, owner.snapshot().pass());
        assertEquals(0, owner.snapshot().walkNoProgressTicks());
        assertEquals(endpoint.entry(), owner.snapshot().entry());
        assertEquals(1, owner.snapshot().transitionTicks());

        ready(owner, first.end(), Direction.EAST, support, pending);
        var shift = owner.snapshot();
        assertEquals(Pass.FORWARD, shift.pass());
        assertEquals(0, shift.walkNoProgressTicks());
        assertEquals(Substage.SHIFT, shift.substage());
        assertEquals(4, shift.transitionTicks());
        var south = new Lane(first.id(), Direction.SOUTH, first.end(), lanes.get(1).start(), region, first.expectedFloor());
        assertEquals(new LaneEntry(south, Stage.ENTER_POSITION, 0, 0, 0, 0, 0, pending, false), shift.entry());
        ready(owner, south.start(), Direction.SOUTH, support, pending);
        assertFalse(advance(owner, south.end(), Direction.SOUTH, support, pending).walkStalled());
        assertEquals(Pass.FORWARD, owner.snapshot().pass());
        assertEquals(0, owner.snapshot().walkNoProgressTicks());
        assertEquals(Substage.NEXT_ENTRY, owner.snapshot().substage());
        assertEquals(8, owner.snapshot().transitionTicks());
        assertEquals(new LaneEntry(lanes.get(1), Stage.ENTER_POSITION, 0, 0, 0, 0, 0, pending, false), owner.snapshot().entry());

        ready(owner, south.end(), Direction.WEST, support, pending);
        assertEquals(Pass.FORWARD, owner.snapshot().pass());
        assertEquals(0, owner.snapshot().walkNoProgressTicks());
        assertEquals(Substage.LANE, owner.snapshot().substage());
        assertEquals(1, owner.snapshot().laneIndex());
        assertEquals(0, owner.snapshot().transitionTicks());
        assertTrue(owner.snapshot().transitionRegion().isEmpty());
        assertEquals(new LaneEntry(lanes.get(1), Stage.WALK, 0, 0, 0, 0, 0, pending, true), owner.snapshot().entry());
    }

    @Test
    void forwardScheduleUsesSharedFloorAndCompleteEntry() {
        for (int depth : List.of(10, 15)) {
            BuildPlan plan = plan(depth);
            List<Lane> lanes = new LanePlanner().plan(plan, 2, 5);
            assertEquals(depth / 5, lanes.size());
            assertEquals(new GridPos(0, 0, 2), lanes.getFirst().start());
            assertEquals(new GridPos(11, 0, 2), lanes.getFirst().end());
            assertEquals(Direction.EAST, lanes.getFirst().direction());
            assertEquals(new GridPos(11, 0, 7), lanes.get(1).start());
            assertEquals(new GridPos(0, 0, 7), lanes.get(1).end());
            assertEquals(Direction.WEST, lanes.get(1).direction());
            var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
            var pending = ids(plan);
            var support = observed(plan, STONE);
            for (int index = 0; index < lanes.size(); index++) {
                Lane lane = lanes.get(index);
                assertEquals(index, owner.snapshot().laneIndex());
                assertEquals(Substage.LANE, owner.snapshot().substage());
                assertSame(lane.expectedFloor(), owner.snapshot().entry().lane().expectedFloor());
                if (index == 0) ready(owner, lane.start(), lane.direction(), support, pending);
                assertEquals(Stage.WALK, owner.snapshot().entry().stage());
                var walk = advance(owner, lane.start(), lane.direction(), support, pending);
                assertEquals(Status.WALKING, walk.movement().orElseThrow().status());
                assertTrue(walk.movement().orElseThrow().sprint());
                var complete = advance(owner, lane.end(), lane.direction(), support, pending);
                assertEquals(index == lanes.size() - 1, complete.traversalComplete());
                if (complete.traversalComplete()) break;

                assertEquals(Substage.ENDPOINT, owner.snapshot().substage());
                assertEquals(0, owner.snapshot().transitionTicks());
                Lane point = owner.snapshot().entry().lane();
                assertEquals(lane.end(), point.start());
                assertEquals(point.start(), point.end());
                assertEquals(lane.corridor(), point.corridor());
                assertEquals(lane.direction(), point.direction());
                assertSame(lane.expectedFloor(), point.expectedFloor());
                assertFalse(owner.snapshot().entry().constantSprint());
                ready(owner, lane.end(), lane.direction(), support, pending);
                assertEquals(Substage.SHIFT, owner.snapshot().substage());
                assertEquals(Stage.ENTER_POSITION, owner.snapshot().entry().stage());
                Lane shift = owner.snapshot().entry().lane();
                assertEquals(lane.end(), shift.start());
                assertEquals(lanes.get(index + 1).start(), shift.end());
                assertEquals(Direction.SOUTH, shift.direction());
                assertSame(lane.expectedFloor(), shift.expectedFloor());
                assertEquals(new Corridor(index % 2 == 0 ? 10 : 0, index % 2 == 0 ? 11 : 1,
                        lane.end().z() - 1, shift.end().z() + 1), shift.corridor());
                assertEquals(Optional.of(shift.corridor()), owner.snapshot().transitionRegion());
                ready(owner, shift.start(), shift.direction(), support, pending);
                assertFalse(advance(owner, shift.start(), shift.direction(), support, pending)
                        .movement().orElseThrow().sprint());
                assertFalse(advance(owner, shift.end(), shift.direction(), support, pending).traversalComplete());
                assertEquals(Substage.NEXT_ENTRY, owner.snapshot().substage());
                assertEquals(index + 1, owner.snapshot().laneIndex());
                assertEquals(lanes.get(index + 1), owner.snapshot().entry().lane());
                assertSame(lane.expectedFloor(), owner.snapshot().entry().lane().expectedFloor());
                assertFalse(owner.snapshot().entry().constantSprint());
                ready(owner, shift.end(), lanes.get(index + 1).direction(), support, pending);
                assertEquals(Substage.LANE, owner.snapshot().substage());
                assertTrue(owner.snapshot().transitionRegion().isEmpty());
                assertEquals(pending, owner.snapshot().entry().pendingTargetIds());
            }
            assertTrue(owner.advanceWithoutPlayer().traversalComplete());
        }
    }

    @Test
    void nonFinalCompleteRetainsUnevaluatedSupportHold() {
        BuildPlan plan = plan(10);
        var lanes = new LanePlanner().plan(plan, 2, 5);
        for (boolean southComplete : List.of(false, true)) {
            for (int supportClass = 0; supportClass < 3; supportClass++) {
                for (boolean readyFootprint : List.of(false, true)) {
                    var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
                    var pending = supportClass == 2 ? ids(plan) : Set.<Integer>of();
                    var missing = supportClass == 1 ? UNKNOWN : observed(plan, AIR);
                    int retained = supportClass == 0 ? 39 : 79;
                    ready(owner, lanes.getFirst().start(), Direction.EAST, observed(plan, STONE), pending);
                    GridPos hold = new GridPos(9, 0, 2);
                    Direction direction = Direction.EAST;
                    if (southComplete) {
                        advance(owner, lanes.getFirst().end(), Direction.EAST, UNKNOWN, pending);
                        ready(owner, lanes.getFirst().end(), Direction.EAST, UNKNOWN, pending);
                        ready(owner, lanes.getFirst().end(), Direction.SOUTH, UNKNOWN, pending);
                        hold = new GridPos(11, 0, 3);
                        direction = Direction.SOUTH;
                    }
                    for (int count = 1; count <= retained; count++) {
                        var held = advance(owner, hold, direction, missing, pending);
                        assertEquals(Status.HOLDING_SUPPORT, held.movement().orElseThrow().status());
                        assertEquals(count, owner.snapshot().entry().supportHoldTicks());
                    }
                    GridPos endpoint = southComplete ? lanes.get(1).start() : lanes.getFirst().end();
                    var complete = advance(owner, endpoint, direction, missing, pending);
                    assertEquals(Status.COMPLETE, complete.movement().orElseThrow().status());
                    assertEquals(0, complete.movement().orElseThrow().nextSupportHoldTicks());
                    assertEquals(retained, owner.snapshot().entry().supportHoldTicks());
                    assertEquals(southComplete ? Substage.NEXT_ENTRY : Substage.ENDPOINT, owner.snapshot().substage());
                    Direction staged = southComplete ? Direction.WEST : Direction.EAST;
                    ready(owner, endpoint, staged, missing, pending);
                    assertEquals(retained, owner.snapshot().entry().supportHoldTicks());
                    if (!southComplete) {
                        ready(owner, endpoint, Direction.SOUTH, missing, pending);
                        assertEquals(retained, owner.snapshot().entry().supportHoldTicks());
                    }
                    Direction walking = southComplete ? Direction.WEST : Direction.SOUTH;
                    var evaluated = advance(owner, endpoint, walking,
                            readyFootprint ? observed(plan, STONE) : missing, pending).movement().orElseThrow();
                    assertEquals(readyFootprint ? Status.WALKING : Status.FAILED, evaluated.status());
                    assertEquals(readyFootprint ? Failure.NONE : Failure.SUPPORT_TIMEOUT, evaluated.failure());
                    assertEquals(0, owner.snapshot().entry().supportHoldTicks());
                }
            }
        }
    }

    @Test
    void transitionTotalCannotRenewAcrossSubstages() {
        BuildPlan plan = plan(10);
        var lanes = new LanePlanner().plan(plan, 2, 5);
        for (Substage expireIn : List.of(Substage.ENDPOINT, Substage.SHIFT, Substage.NEXT_ENTRY)) {
            var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
            var support = observed(plan, STONE);
            var pending = ids(plan);
            var initial = owner.snapshot();
            assertTrue(owner.advanceWithoutPlayer().movement().isEmpty());
            assertEquals(initial, owner.snapshot());
            ready(owner, lanes.getFirst().start(), Direction.EAST, support, pending);
            advance(owner, lanes.getFirst().end(), Direction.EAST, support, pending);
            for (int count = 0; count < 100; count++) assertFalse(owner.advanceWithoutPlayer().transitionExpired());
            if (expireIn != Substage.ENDPOINT) {
                ready(owner, lanes.getFirst().end(), Direction.EAST, support, pending);
                assertEquals(103, owner.snapshot().transitionTicks());
            }
            if (expireIn == Substage.NEXT_ENTRY) {
                ready(owner, lanes.getFirst().end(), Direction.SOUTH, support, pending);
                advance(owner, lanes.get(1).start(), Direction.SOUTH, support, pending);
                assertEquals(107, owner.snapshot().transitionTicks());
            }
            assertEquals(expireIn, owner.snapshot().substage());
            LaneEntry beforeAbsent = owner.snapshot().entry();
            while (owner.snapshot().transitionTicks() < 120) {
                var absent = owner.advanceWithoutPlayer();
                assertFalse(absent.transitionExpired());
                assertTrue(absent.movement().isEmpty());
                assertEquals(beforeAbsent, owner.snapshot().entry());
            }
            var beforeExpiry = owner.snapshot();
            for (int repeat = 0; repeat < 3; repeat++) {
                var expired = repeat == 0 ? advance(owner, owner.snapshot().entry().lane().start(),
                        owner.snapshot().entry().lane().direction(), support, pending) : owner.advanceWithoutPlayer();
                assertTrue(expired.transitionExpired());
                assertFalse(expired.traversalComplete());
                assertTrue(expired.movement().isEmpty());
                assertEquals(beforeExpiry, owner.snapshot());
            }
        }
    }

    @Test
    void overshootUsesExistingCardinalCorrection() {
        BuildPlan plan = plan(10);
        var lanes = new LanePlanner().plan(plan, 2, 5);
        var owner = new ForwardTraversal(lanes, plan.dimensions(), true);
        var support = observed(plan, STONE);
        ready(owner, lanes.getFirst().start(), Direction.EAST, support, Set.of());
        owner.advance(new PlayerObservation(12.0, 1, 2.5, -90), support, Set.of(), plan.targets());
        var endpoint = owner.advance(new PlayerObservation(12.0, 1, 2.5, -90), support, Set.of(), plan.targets()).movement().orElseThrow();
        assertTrue(endpoint.back());
        assertFalse(endpoint.forward());
        assertEquals(Strafe.NONE, endpoint.strafe());
        ready(owner, lanes.getFirst().end(), Direction.EAST, support, Set.of());
        var south = owner.advance(new PlayerObservation(12.0, 1, 2.5, 0), support, Set.of(), plan.targets()).movement().orElseThrow();
        assertEquals(Strafe.RIGHT, south.strafe());
        assertFalse(south.forward());
        assertFalse(south.back());
        assertFalse(south.sprint());
    }

    @Test
    void snapshotIsImmutable() {
        BuildPlan plan = plan(10);
        var lanes = new ArrayList<>(new LanePlanner().plan(plan, 2, 5));
        var owner = new ForwardTraversal(lanes, plan.dimensions(), false);
        lanes.clear();
        var pending = new HashSet<>(ids(plan));
        advance(owner, new GridPos(0, 0, 2), Direction.EAST, UNKNOWN, pending);
        var old = owner.snapshot();
        pending.clear();
        assertEquals(ids(plan), old.entry().pendingTargetIds());
        assertThrows(UnsupportedOperationException.class, () -> old.entry().pendingTargetIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> old.entry().lane().expectedFloor().targetsByPosition().clear());
        advance(owner, new GridPos(0, 0, 2), Direction.EAST, UNKNOWN, Set.of());
        assertEquals(0, old.entry().stageElapsedTicks());
        assertEquals(ids(plan), old.entry().pendingTargetIds());
        assertTrue(owner.snapshot().entry().pendingTargetIds().isEmpty());
        assertEquals(Status.READY_TO_WALK, advance(owner, new GridPos(0, 0, 2), Direction.EAST, UNKNOWN, Set.of())
                .movement().orElseThrow().status());
        advance(owner, new GridPos(11, 0, 2), Direction.EAST, UNKNOWN, Set.of());
        assertEquals(Substage.ENDPOINT, owner.snapshot().substage());
        assertEquals(Substage.LANE, old.substage());
        assertEquals(0, old.transitionTicks());
        assertTrue(old.transitionRegion().isEmpty());
    }

    private static void ready(ForwardTraversal owner, GridPos position, Direction direction,
                              SupportObservation support, Set<Integer> pending) {
        assertEquals(Status.ALIGNING_YAW, advance(owner, position, direction, support, pending).movement().orElseThrow().status());
        assertEquals(Status.ALIGNING_YAW, advance(owner, position, direction, support, pending).movement().orElseThrow().status());
        var ready = advance(owner, position, direction, support, pending).movement().orElseThrow();
        assertEquals(Status.READY_TO_WALK, ready.status());
        assertFalse(ready.forward());
        assertFalse(ready.back());
        assertEquals(Strafe.NONE, ready.strafe());
        assertFalse(ready.sprint());
    }

    private static ForwardTraversal.Advance advance(ForwardTraversal owner, GridPos position, Direction direction,
                                                     SupportObservation support, Set<Integer> pending) {
        return owner.advance(new PlayerObservation(position.x() + .5, 1, position.z() + .5, direction.yawDegrees()), support, pending,
                List.copyOf(owner.snapshot().entry().lane().expectedFloor().targetsByPosition().values()));
    }

    private static Set<Integer> ids(BuildPlan plan) {
        var ids = new HashSet<Integer>();
        plan.targets().forEach(target -> ids.add(target.id()));
        return Set.copyOf(ids);
    }

    private static SupportObservation observed(BuildPlan plan, BlockSpec block) {
        var cells = new HashMap<GridPos, SupportSample>();
        plan.targets().forEach(target -> cells.put(target.relativePos(), new SupportSample(Evidence.OBSERVED, Optional.of(block))));
        return new SupportObservation(cells);
    }

    private static BuildPlan plan(int depth) {
        var targets = new ArrayList<Target>();
        for (int z = 0; z < depth; z++) {
            for (int x = 0; x < 12; x++) targets.add(new Target(z * 12 + x, new GridPos(x, 0, z), STONE));
        }
        return new BuildPlan("forward-" + depth, new GridSize(12, 1, depth), targets);
    }
}
