package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.*;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.placement.PlacementObservation.Evidence;
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
                assertEquals(index == lanes.size() - 1, complete.forwardComplete());
                if (complete.forwardComplete()) break;

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
                assertFalse(advance(owner, shift.end(), shift.direction(), support, pending).forwardComplete());
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
            assertTrue(owner.advanceWithoutPlayer().forwardComplete());
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
                assertFalse(expired.forwardComplete());
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
        owner.advance(new PlayerObservation(12.0, 1, 2.5, -90), support, Set.of());
        var endpoint = owner.advance(new PlayerObservation(12.0, 1, 2.5, -90), support, Set.of()).movement().orElseThrow();
        assertTrue(endpoint.back());
        assertFalse(endpoint.forward());
        assertEquals(Strafe.NONE, endpoint.strafe());
        ready(owner, lanes.getFirst().end(), Direction.EAST, support, Set.of());
        var south = owner.advance(new PlayerObservation(12.0, 1, 2.5, 0), support, Set.of()).movement().orElseThrow();
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
        return owner.advance(new PlayerObservation(position.x() + .5, 1, position.z() + .5, direction.yawDegrees()), support, pending);
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
