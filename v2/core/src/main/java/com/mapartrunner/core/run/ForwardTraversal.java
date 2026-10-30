package com.mapartrunner.core.run;

import com.mapartrunner.core.model.BuildPlan.GridPos;
import com.mapartrunner.core.model.BuildPlan.GridSize;
import com.mapartrunner.core.model.BuildPlan.Target;
import com.mapartrunner.core.movement.MovementPolicy;
import com.mapartrunner.core.movement.MovementPolicy.*;
import com.mapartrunner.core.traversal.LanePlanner.*;

import java.util.List;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owns forward/reverse entry, transition and supported-walk counters, advancing once per eligible callback. Creation
 * starts a fresh schedule; pause retains this owner without advancing it. The run owner captures
 * diagnostics and releases effects before discarding it on stop, failure or world replacement.
 */
public final class ForwardTraversal {
    private static final int TRANSITION_LIMIT = 120;
    private static final int WALK_NO_PROGRESS_LIMIT = 40;

    private final List<Lane> lanes;
    private final GridSize dimensions;
    private final boolean constantSprint;
    private final MovementPolicy policy = new MovementPolicy();
    private Snapshot state;
    private boolean traversalComplete;
    // Frozen until arrival; physical laneIndex descends through adjacent checkpoints toward it.
    private int reverseTargetIndex = -1;
    private double walkCreditedCoordinate;

    public ForwardTraversal(List<Lane> lanes, GridSize dimensions, boolean constantSprint) {
        this.lanes = List.copyOf(lanes);
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        this.constantSprint = constantSprint;
        if (this.lanes.isEmpty()) throw new IllegalArgumentException("Forward traversal requires a lane");
        state = new Snapshot(0, Substage.LANE, fresh(this.lanes.getFirst(), 0, Set.of(), constantSprint),
                0, Optional.empty(), Pass.SELECTING, 0);
    }

    public Snapshot snapshot() { return state; }

    public Advance advance(PlayerObservation player, SupportObservation support, Set<Integer> pendingTargetIds,
                           List<Target> unfinishedTargets) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(unfinishedTargets, "unfinishedTargets");
        if (state.pass() == Pass.SELECTING && !select(player, unfinishedTargets)) {
            return new Advance(Optional.empty(), false, false, false, true, Optional.empty());
        }
        if (traversalComplete) return new Advance(Optional.empty(), true, false, false, false, Optional.empty());
        if (transitionExpired()) return new Advance(Optional.empty(), false, true, false, false, Optional.empty());
        if (state.walkNoProgressTicks() >= WALK_NO_PROGRESS_LIMIT) return new Advance(Optional.empty(), false, false, true, false, Optional.empty());
        LaneEntry prior = state.entry();
        var input = new LaneEntry(prior.lane(), prior.stage(), prior.stageElapsedTicks(), prior.entryElapsedTicks(),
                prior.consecutiveYawAlignedTicks(), prior.supportHoldTicks(), prior.yawHoldTicks(),
                pendingTargetIds, prior.constantSprint());
        MovementIntent result = policy.decide(player, input, support);
        int walkTicks = state.walkNoProgressTicks();
        if (state.substage() == Substage.LANE || state.substage() == Substage.TRANSIT) {
            double coordinate = input.lane().direction().progressCoordinate(player.x(), player.z())
                    * input.lane().direction().forwardSign();
            if (result.status() == Status.READY_TO_WALK) {
                walkCreditedCoordinate = coordinate;
                walkTicks = 0;
            }
            if (result.status() == Status.WALKING) {
                if (coordinate - walkCreditedCoordinate >= .25) {
                    walkCreditedCoordinate = coordinate;
                    walkTicks = 0;
                } else walkTicks++;
            }
        }
        int ticks = inHop() ? state.transitionTicks() + 1 : 0;
        var entry = new LaneEntry(input.lane(), result.nextStage(), result.nextStageElapsedTicks(),
                result.nextEntryElapsedTicks(), result.nextYawAlignedTicks(), result.nextSupportHoldTicks(),
                result.nextYawHoldTicks(), input.pendingTargetIds(), input.constantSprint());
        // COMPLETE bypasses support evaluation; its zero output cannot clear unresolved history.
        if (result.status() == Status.COMPLETE) entry = new LaneEntry(entry.lane(), entry.stage(),
                entry.stageElapsedTicks(), entry.entryElapsedTicks(), entry.consecutiveYawAlignedTicks(),
                input.supportHoldTicks(), entry.yawHoldTicks(), entry.pendingTargetIds(), entry.constantSprint());
        state = new Snapshot(state.laneIndex(), state.substage(), entry, ticks, state.transitionRegion(), state.pass(), walkTicks);
        switch (state.substage()) {
            case LANE -> {
                if (result.status() == Status.COMPLETE) {
                    if (state.pass() == Pass.REVERSE) {
                        selectReverse(state.laneIndex() - 1, input.lane().end(), input, unfinishedTargets);
                    } else if (state.laneIndex() == lanes.size() - 1) {
                        selectReverse(state.laneIndex(), input.lane().end(), input, unfinishedTargets);
                    } else {
                        beginHop(input.lane().end(), input);
                    }
                } else if (state.pass() == Pass.REVERSE && result.status() == Status.READY_TO_WALK
                        && unfinishedTargets.stream().noneMatch(t -> covers(state.entry().lane(), t))) {
                    selectReverse(state.laneIndex() - 1, entry.lane().start(), entry, unfinishedTargets);
                }
            }
            case TRANSIT -> {
                if (result.status() == Status.COMPLETE) beginHop(input.lane().end(), input);
            }
            case ENDPOINT -> {
                if (result.status() == Status.READY_TO_WALK) {
                    GridPos start = input.lane().start(), end = nextCheckpoint(start.x());
                    var shift = new Lane(input.lane().id(), direction(start, end), start, end,
                            state.transitionRegion().orElseThrow(), input.lane().expectedFloor());
                    state = new Snapshot(state.laneIndex(), Substage.SHIFT,
                            fresh(shift, entry.supportHoldTicks(), entry.pendingTargetIds(), false), ticks, state.transitionRegion(), state.pass(), state.walkNoProgressTicks());
                }
            }
            case SHIFT -> {
                if (result.status() == Status.COMPLETE) {
                    int next = state.laneIndex() + (state.pass() == Pass.REVERSE ? -1 : 1);
                    Lane nextLane = lanes.get(next);
                    if (state.pass() == Pass.REVERSE) nextLane = next == reverseTargetIndex ? reversed(next)
                            : new Lane(nextLane.id(), Direction.NORTH, input.lane().end(), input.lane().end(),
                            nextLane.corridor(), nextLane.expectedFloor());
                    // The cardinal segment's COMPLETE also bypassed footprint evaluation.
                    state = new Snapshot(next, Substage.NEXT_ENTRY,
                            fresh(nextLane, input.supportHoldTicks(), input.pendingTargetIds(), false), ticks, state.transitionRegion(), state.pass(), state.walkNoProgressTicks());
                }
            }
            case NEXT_ENTRY -> {
                if (result.status() == Status.READY_TO_WALK) {
                    if (state.pass() == Pass.REVERSE && state.laneIndex() > reverseTargetIndex) {
                        beginHop(entry.lane().start(), entry);
                        break;
                    }
                    if (state.pass() == Pass.REVERSE && unfinishedTargets.stream().noneMatch(t -> covers(state.entry().lane(), t))) {
                        selectReverse(state.laneIndex() - 1, entry.lane().start(), entry, unfinishedTargets);
                        break;
                    }
                    // Rebase only after actual destination entry; holds and entry jitter retain the old debt.
                    walkCreditedCoordinate = entry.lane().direction().progressCoordinate(player.x(), player.z())
                            * entry.lane().direction().forwardSign();
                    var walking = new LaneEntry(entry.lane(), entry.stage(), entry.stageElapsedTicks(), entry.entryElapsedTicks(),
                            entry.consecutiveYawAlignedTicks(), entry.supportHoldTicks(), entry.yawHoldTicks(),
                            entry.pendingTargetIds(), constantSprint);
                    state = new Snapshot(state.laneIndex(), Substage.LANE, walking, 0, Optional.empty(), state.pass(), 0);
                }
            }
        }
        return new Advance(Optional.of(result), traversalComplete, false, state.walkNoProgressTicks() >= WALK_NO_PROGRESS_LIMIT,
                false, Optional.of(input));
    }

    /** Missing player data consumes only the existing transition total, never policy counters. */
    public Advance advanceWithoutPlayer() {
        if (traversalComplete) return new Advance(Optional.empty(), true, false, false, false, Optional.empty());
        if (transitionExpired()) return new Advance(Optional.empty(), false, true, false, false, Optional.empty());
        if (state.walkNoProgressTicks() >= WALK_NO_PROGRESS_LIMIT) return new Advance(Optional.empty(), false, false, true, false, Optional.empty());
        if (inHop()) {
            state = new Snapshot(state.laneIndex(), state.substage(), state.entry(),
                    state.transitionTicks() + 1, state.transitionRegion(), state.pass(), state.walkNoProgressTicks());
        }
        return new Advance(Optional.empty(), false, false, false, false, Optional.empty());
    }

    private boolean select(PlayerObservation player, List<Target> unfinished) {
        if (unfinished.stream().anyMatch(t -> lanes.stream().noneMatch(l -> covers(l, t)))) return false;
        Lane selected = lanes.getFirst();
        int selectedIndex = 0;
        if (!unfinished.isEmpty() && unfinished.size() != selected.expectedFloor().targetsByPosition().size()) {
            double bestDistance = Double.POSITIVE_INFINITY;
            boolean bestPartial = false;
            for (int index = 0; index < lanes.size(); index++) {
                Lane lane = lanes.get(index);
                var earliest = unfinished.stream().filter(t -> covers(lane, t)).min(Comparator.comparingDouble(t ->
                        lane.direction().progressCoordinate(t.relativePos().x(), t.relativePos().z()) * lane.direction().forwardSign()));
                if (earliest.isEmpty()) continue;
                GridPos target = earliest.orElseThrow().relativePos();
                GridPos start = lane.direction().alongX() ? new GridPos(target.x(), lane.start().y(), lane.start().z())
                        : new GridPos(lane.start().x(), lane.start().y(), target.z());
                var partial = new Lane(lane.id(), lane.direction(), start, lane.end(), lane.corridor(), lane.expectedFloor());
                for (int candidate = 0; candidate < 2; candidate++) {
                    Lane entry = candidate == 0 ? lane : partial;
                    double dx = entry.start().x() + .5 - player.x(), dy = entry.start().y() + 1.0 - player.y();
                    double dz = entry.start().z() + .5 - player.z();
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (distance + 16.0 < bestDistance || Math.abs(distance - bestDistance) <= 16.0
                            && (index < selectedIndex || index == selectedIndex && candidate == 1 && !bestPartial)) {
                        selected = entry; selectedIndex = index; bestDistance = distance; bestPartial = candidate == 1;
                    }
                }
            }
        }
        traversalComplete = unfinished.isEmpty();
        // Select only before the first policy call; the clipped entry never replaces a canonical lane.
        state = new Snapshot(selectedIndex, state.substage(), fresh(selected, state.entry().supportHoldTicks(),
                state.entry().pendingTargetIds(), constantSprint), state.transitionTicks(), state.transitionRegion(),
                traversalComplete ? Pass.COMPLETE : Pass.FORWARD, state.walkNoProgressTicks());
        return true;
    }

    private void selectReverse(int ceiling, GridPos source, LaneEntry prior, List<Target> unfinished) {
        reverseTargetIndex = ceiling;
        while (reverseTargetIndex >= 0) {
            Lane candidate = lanes.get(reverseTargetIndex);
            if (unfinished.stream().anyMatch(t -> covers(candidate, t))) break;
            reverseTargetIndex--;
        }
        if (reverseTargetIndex < 0) {
            traversalComplete = true;
            state = new Snapshot(state.laneIndex(), state.substage(), state.entry(), 0,
                    Optional.empty(), Pass.COMPLETE, state.walkNoProgressTicks());
            return;
        }
        Lane selected = reversed(reverseTargetIndex);
        state = new Snapshot(state.laneIndex(), state.substage(), state.entry(), 0,
                Optional.empty(), Pass.REVERSE, state.walkNoProgressTicks());
        if (reverseTargetIndex == state.laneIndex()) {
            state = new Snapshot(state.laneIndex(), Substage.LANE,
                    fresh(selected, prior.supportHoldTicks(), prior.pendingTargetIds(), constantSprint),
                    0, Optional.empty(), Pass.REVERSE, 0);
        } else if (source.x() != selected.start().x()) {
            var end = new GridPos(selected.start().x(), source.y(), source.z());
            Lane canonical = lanes.get(state.laneIndex());
            var transit = new Lane(canonical.id(), direction(source, end), source, end,
                    canonical.corridor(), canonical.expectedFloor());
            state = new Snapshot(state.laneIndex(), Substage.TRANSIT,
                    fresh(transit, prior.supportHoldTicks(), prior.pendingTargetIds(), false),
                    0, Optional.empty(), Pass.REVERSE, state.walkNoProgressTicks());
        } else beginHop(source, prior);
    }

    private Lane reversed(int index) {
        Lane lane = lanes.get(index);
        Direction reverse = lane.direction() == Direction.EAST ? Direction.WEST : Direction.EAST;
        return new Lane(lane.id(), reverse, lane.end(), lane.start(), lane.corridor(), lane.expectedFloor());
    }

    private GridPos nextCheckpoint(int x) {
        if (state.pass() == Pass.FORWARD) return lanes.get(state.laneIndex() + 1).start();
        GridPos next = lanes.get(state.laneIndex() - 1).start();
        return new GridPos(x, next.y(), next.z());
    }

    private void beginHop(GridPos source, LaneEntry prior) {
        var point = new Lane(lanes.get(state.laneIndex()).id(), prior.lane().direction(), source, source,
                lanes.get(state.laneIndex()).corridor(), prior.lane().expectedFloor());
        // Only a completed destination entry (or the preceding X WALK) can begin another hop clock.
        state = new Snapshot(state.laneIndex(), Substage.ENDPOINT,
                fresh(point, prior.supportHoldTicks(), prior.pendingTargetIds(), false), 0,
                Optional.of(transitionRegion(source, nextCheckpoint(source.x()))), state.pass(), state.walkNoProgressTicks());
    }

    private static boolean covers(Lane lane, Target target) {
        GridPos p = target.relativePos();
        Corridor c = lane.corridor();
        double progress = lane.direction().progressCoordinate(p.x(), p.z()) * lane.direction().forwardSign();
        return p.y() == lane.start().y() && p.x() >= c.minX() && p.x() <= c.maxX() && p.z() >= c.minZ() && p.z() <= c.maxZ()
                && progress >= lane.direction().progressCoordinate(lane.start().x(), lane.start().z()) * lane.direction().forwardSign()
                && progress <= lane.direction().progressCoordinate(lane.end().x(), lane.end().z()) * lane.direction().forwardSign();
    }

    private boolean transitionExpired() {
        return inHop() && state.transitionTicks() >= TRANSITION_LIMIT;
    }

    private boolean inHop() {
        return state.substage() == Substage.ENDPOINT || state.substage() == Substage.SHIFT || state.substage() == Substage.NEXT_ENTRY;
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

    public enum Pass { SELECTING, FORWARD, REVERSE, COMPLETE }

    public enum Substage { LANE, TRANSIT, ENDPOINT, SHIFT, NEXT_ENTRY }

    public record Snapshot(int laneIndex, Substage substage, LaneEntry entry, int transitionTicks,
                           Optional<Corridor> transitionRegion, Pass pass, int walkNoProgressTicks) { }

    public record Advance(Optional<MovementIntent> movement, boolean traversalComplete, boolean transitionExpired, boolean walkStalled,
                          boolean uncoveredTargets, Optional<LaneEntry> decisionInput) { }
}
