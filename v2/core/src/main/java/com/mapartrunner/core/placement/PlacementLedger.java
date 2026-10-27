package com.mapartrunner.core.placement;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.Target;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Single-threaded accounting for every original plan target; confirmation is reversible.
 * Create unbound, then bind to the coordinator's selected run before routing inputs. The caller
 * checks the plan fingerprint against its lifecycle context; this ledger owns no world lifecycle.
 * Retain this instance across same-context pause. On stop/cancel/world change, the coordinator
 * stops routing inputs and discards this ledger before installing a fresh bound one.
 */
public final class PlacementLedger {
    private final BuildPlan plan;
    private final Map<Integer, TargetState> targets = new HashMap<>();
    private UUID runId;

    public PlacementLedger(BuildPlan plan) {
        this.plan = Objects.requireNonNull(plan, "plan");
        for (Target target : plan.targets()) {
            targets.put(target.id(), new TargetState(target));
        }
    }

    public void bindRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        if (this.runId != null && !this.runId.equals(runId)) {
            throw new IllegalStateException("A new run requires a fresh ledger");
        }
        this.runId = runId;
    }

    /** An attempt never confirms a target; an exact latest repeat has no effect on any history. */
    public boolean recordAttempt(UUID runId, long actionId, int targetId, long tick) {
        Objects.requireNonNull(runId, "runId");
        if (actionId < 0 || targetId < 0 || tick < 0) {
            throw new IllegalArgumentException("Action ID, target ID and tick must be nonnegative");
        }
        requireBound();
        TargetState state = targets.get(targetId);
        if (!this.runId.equals(runId) || state == null) {
            return false;
        }
        // Check the exact duplicate before freshness: confirmation may have arrived since issuance.
        if (actionId == state.actionId && tick == state.attemptTick) {
            return true;
        }
        if (actionId <= state.actionId || tick < state.attemptTick || tick < state.observationTick) {
            return false;
        }
        state.actionId = actionId;
        state.attemptTick = tick;
        state.confirmed = false;
        return true;
    }

    /** Accept only fresh evidence with valid correlation; rejected inputs change no history. */
    public boolean observe(PlacementObservation observation) {
        Objects.requireNonNull(observation, "observation");
        requireBound();
        TargetState state = targets.get(observation.targetId());
        if (!runId.equals(observation.runId()) || state == null) {
            return false;
        }
        if (observation.tick() <= state.observationTick || observation.tick() <= state.attemptTick) {
            return false;
        }
        if (observation.actionId().isPresent() && observation.actionId().getAsLong() != state.actionId) {
            return false;
        }
        state.observationTick = observation.tick();
        state.confirmed = observation.evidence() == PlacementObservation.Evidence.OBSERVED
                && observation.observedState().orElseThrow().equals(state.target.block());
        return true;
    }

    public List<Target> unfinishedTargets() {
        return plan.targets().stream().filter(target -> !targets.get(target.id()).confirmed).toList();
    }

    /** ALL_CONFIRMED is target accounting, not a simultaneous final-world scan or terminal success. */
    public Outcome outcome() {
        if (plan.targets().isEmpty()) {
            return Outcome.NOTHING_TO_BUILD;
        }
        return targets.values().stream().allMatch(state -> state.confirmed) ? Outcome.ALL_CONFIRMED : Outcome.INCOMPLETE;
    }

    private void requireBound() {
        if (runId == null) {
            throw new IllegalStateException("Bind the run before routing attempts or observations");
        }
    }

    public enum Outcome { NOTHING_TO_BUILD, INCOMPLETE, ALL_CONFIRMED }

    private static final class TargetState {
        private final Target target;
        // Histories survive confirmation, so superseded actions and stale samples stay rejected.
        private long actionId = -1;
        private long attemptTick = -1;
        private long observationTick = -1;
        private boolean confirmed;

        private TargetState(Target target) {
            this.target = target;
        }
    }
}
