package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.model.BuildPlan.GridSize;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.traversal.LanePlanner.*;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owns forward entry and transition counters, advancing once per eligible callback. Creation
 * starts a fresh schedule; pause retains this owner without advancing it. The run owner captures
 * diagnostics and releases effects before discarding it on stop, failure or world replacement.
 */
public final class ForwardTraversal {
    private static final int TRANSITION_LIMIT = 120;

    private final List<Lane> lanes;
    private final GridSize dimensions;
    private final boolean constantSprint;
    private final MovementPolicy policy = new MovementPolicy();
    private Snapshot state;
    private boolean forwardComplete;

    public ForwardTraversal(List<Lane> lanes, GridSize dimensions, boolean constantSprint) {
        this.lanes = List.copyOf(lanes);
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        this.constantSprint = constantSprint;
        if (this.lanes.isEmpty()) throw new IllegalArgumentException("Forward traversal requires a lane");
        state = new Snapshot(0, Substage.LANE, fresh(this.lanes.getFirst(), 0, Set.of(), constantSprint),
                0, Optional.empty());
    }

    public Snapshot snapshot() { return state; }

    public Advance advance(PlayerObservation player, SupportObservation support, Set<Integer> pendingTargetIds) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(support, "support");
        if (forwardComplete) return new Advance(Optional.empty(), true, false);
        if (transitionExpired()) return new Advance(Optional.empty(), false, true);
        LaneEntry prior = state.entry();
        var input = new LaneEntry(prior.lane(), prior.stage(), prior.stageElapsedTicks(), prior.entryElapsedTicks(),
                prior.consecutiveYawAlignedTicks(), prior.supportHoldTicks(), prior.yawHoldTicks(),
                pendingTargetIds, prior.constantSprint());
        MovementIntent result = policy.decide(player, input, support);
        int ticks = state.substage() == Substage.LANE ? 0 : state.transitionTicks() + 1;
        var entry = new LaneEntry(input.lane(), result.nextStage(), result.nextStageElapsedTicks(),
                result.nextEntryElapsedTicks(), result.nextYawAlignedTicks(), result.nextSupportHoldTicks(),
                result.nextYawHoldTicks(), input.pendingTargetIds(), input.constantSprint());
        state = new Snapshot(state.laneIndex(), state.substage(), entry, ticks, state.transitionRegion());
        switch (state.substage()) {
            case LANE -> {
                if (result.status() == Status.COMPLETE) {
                    if (state.laneIndex() == lanes.size() - 1) {
                        forwardComplete = true;
                    } else {
                        Lane old = input.lane();
                        Lane next = lanes.get(state.laneIndex() + 1);
                        var point = new Lane(old.id(), old.direction(), old.end(), old.end(), old.corridor(), old.expectedFloor());
                        // COMPLETE did not evaluate support; retain its input hold across the handoff.
                        state = new Snapshot(state.laneIndex(), Substage.ENDPOINT,
                                fresh(point, input.supportHoldTicks(), input.pendingTargetIds(), false),
                                0, Optional.of(transitionRegion(old.end(), next.start())));
                    }
                }
            }
            case ENDPOINT -> {
                if (result.status() == Status.READY_TO_WALK) {
                    Lane old = lanes.get(state.laneIndex());
                    GridPos start = old.end(), end = lanes.get(state.laneIndex() + 1).start();
                    var shift = new Lane(old.id(), direction(start, end), start, end,
                            state.transitionRegion().orElseThrow(), old.expectedFloor());
                    state = new Snapshot(state.laneIndex(), Substage.SHIFT,
                            fresh(shift, entry.supportHoldTicks(), entry.pendingTargetIds(), false), ticks, state.transitionRegion());
                }
            }
            case SHIFT -> {
                if (result.status() == Status.COMPLETE) {
                    int next = state.laneIndex() + 1;
                    // The cardinal segment's COMPLETE also bypassed footprint evaluation.
                    state = new Snapshot(next, Substage.NEXT_ENTRY,
                            fresh(lanes.get(next), input.supportHoldTicks(), input.pendingTargetIds(), false), ticks, state.transitionRegion());
                }
            }
            case NEXT_ENTRY -> {
                if (result.status() == Status.READY_TO_WALK) {
                    var walking = new LaneEntry(entry.lane(), entry.stage(), entry.stageElapsedTicks(), entry.entryElapsedTicks(),
                            entry.consecutiveYawAlignedTicks(), entry.supportHoldTicks(), entry.yawHoldTicks(),
                            entry.pendingTargetIds(), constantSprint);
                    state = new Snapshot(state.laneIndex(), Substage.LANE, walking, 0, Optional.empty());
                }
            }
        }
        return new Advance(Optional.of(result), forwardComplete, false);
    }

    /** Missing player data consumes only the existing transition total, never policy counters. */
    public Advance advanceWithoutPlayer() {
        if (forwardComplete) return new Advance(Optional.empty(), true, false);
        if (transitionExpired()) return new Advance(Optional.empty(), false, true);
        if (state.substage() != Substage.LANE) {
            state = new Snapshot(state.laneIndex(), state.substage(), state.entry(),
                    state.transitionTicks() + 1, state.transitionRegion());
        }
        return new Advance(Optional.empty(), false, false);
    }

    private boolean transitionExpired() {
        return state.substage() != Substage.LANE && state.transitionTicks() >= TRANSITION_LIMIT;
    }

    private static LaneEntry fresh(Lane lane, int supportHoldTicks, Set<Integer> pendingTargetIds, boolean sprint) {
        return new LaneEntry(lane, Stage.ENTER_POSITION, 0, 0, 0, supportHoldTicks, 0, pendingTargetIds, sprint);
    }

    private Corridor transitionRegion(GridPos start, GridPos end) {
        return new Corridor((int) Math.max(0L, (long) Math.min(start.x(), end.x()) - 1),
                (int) Math.min(dimensions.width() - 1L, (long) Math.max(start.x(), end.x()) + 1),
                (int) Math.max(0L, (long) Math.min(start.z(), end.z()) - 1),
                (int) Math.min(dimensions.depth() - 1L, (long) Math.max(start.z(), end.z()) + 1));
    }

    private static Direction direction(GridPos start, GridPos end) {
        if (start.x() == end.x()) return start.z() <= end.z() ? Direction.SOUTH : Direction.NORTH;
        if (start.z() == end.z()) return start.x() <= end.x() ? Direction.EAST : Direction.WEST;
        throw new IllegalArgumentException("Lane transition must be cardinal");
    }

    public enum Substage { LANE, ENDPOINT, SHIFT, NEXT_ENTRY }

    public record Snapshot(int laneIndex, Substage substage, LaneEntry entry, int transitionTicks,
                           Optional<Corridor> transitionRegion) { }

    public record Advance(Optional<MovementIntent> movement, boolean forwardComplete, boolean transitionExpired) { }
}
