package com.mapartrunner.core.placement;

import com.mapartrunner.core.model.BuildPlan;
import com.mapartrunner.core.model.BuildPlan.Target;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Single-threaded accounting for every original plan target; confirmation is reversible.
 * Create unbound, then bind to the coordinator's selected run before routing inputs. The caller
 * checks the plan fingerprint against its lifecycle context; this ledger owns no world lifecycle.
 * Retain this instance across same-context pause. On stop/cancel/world change, the coordinator
 * invalidates routing/effects, then discards this ledger before installing a fresh bound one.
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
        return recordAttempt(runId, actionId, targetId, tick, OptionalLong.empty());
    }

    /** Sample freshness and active eligible time are independent clocks; neither implies evidence. */
    public boolean recordIssuedAttempt(UUID runId, long actionId, int targetId,
                                       long sampleTick, long issuedEligibleTick) {
        return recordAttempt(runId, actionId, targetId, sampleTick, OptionalLong.of(issuedEligibleTick));
    }

    private boolean recordAttempt(UUID runId, long actionId, int targetId, long tick,
                                  OptionalLong issuedEligibleTick) {
        Objects.requireNonNull(runId, "runId");
        if (actionId < 0 || targetId < 0 || tick < 0
                || (issuedEligibleTick.isPresent() && issuedEligibleTick.getAsLong() < 0)) {
            throw new IllegalArgumentException("Action ID, target ID, tick and eligible tick must be nonnegative");
        }
        requireBound();
        TargetState state = targets.get(targetId);
        if (!this.runId.equals(runId) || state == null) {
            return false;
        }
        // Check the exact duplicate before freshness: confirmation may have arrived since issuance.
        if (actionId == state.actionId && tick == state.attemptTick) {
            return issuedEligibleTick.isEmpty() || issuedEligibleTick.equals(state.issuedEligibleTick);
        }
        if (actionId <= state.actionId || tick < state.attemptTick || tick < state.observationTick) {
            return false;
        }
        if (issuedEligibleTick.isPresent() && issuedEligibleTick.getAsLong() < state.eligibleTickHighWater) {
            return false;
        }
        long count = Math.incrementExact(state.attemptCount);
        state.actionId = actionId;
        state.attemptTick = tick;
        state.issuedEligibleTick = issuedEligibleTick;
        if (issuedEligibleTick.isPresent()) {
            state.eligibleTickHighWater = issuedEligibleTick.getAsLong();
        }
        state.attemptCount = count;
        state.awaitingObservation = true;
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
        if (observation.evidence() == PlacementObservation.Evidence.OBSERVED) {
            state.awaitingObservation = false;
        }
        return true;
    }

    /** Owned attempts awaiting fresh OBSERVED evidence, independently of target confirmation. */
    public Set<Integer> pendingTargetIds() {
        requireBound();
        return Set.copyOf(targets.values().stream().filter(state -> state.awaitingObservation)
                .map(state -> state.target.id()).toList());
    }

    public Optional<AttemptSnapshot> latestAttempt(int targetId) {
        requireBound();
        TargetState state = targets.get(targetId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown target ID: " + targetId);
        }
        return state.attemptCount == 0 ? Optional.empty() : Optional.of(new AttemptSnapshot(
                state.actionId, state.attemptTick, state.issuedEligibleTick, state.attemptCount, state.awaitingObservation));
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

    public record AttemptSnapshot(long actionId, long tick, OptionalLong issuedEligibleTick,
                                  long count, boolean awaitingObservation) {
        public AttemptSnapshot {
            Objects.requireNonNull(issuedEligibleTick, "issuedEligibleTick");
            if (actionId < 0 || tick < 0 || count <= 0
                    || (issuedEligibleTick.isPresent() && issuedEligibleTick.getAsLong() < 0)) {
                throw new IllegalArgumentException("Action ID, tick and eligible tick must be nonnegative; count must be positive");
            }
        }
    }

    private static final class TargetState {
        private final Target target;
        // Histories survive confirmation, so superseded actions and stale samples stay rejected.
        private long actionId = -1;
        private long attemptTick = -1;
        private long observationTick = -1;
        private OptionalLong issuedEligibleTick = OptionalLong.empty();
        // Retained across legacy attempts and pause; legacy snapshots still expose no eligible time.
        private long eligibleTickHighWater = -1;
        private long attemptCount;
        private boolean awaitingObservation;
        private boolean confirmed;

        private TargetState(Target target) {
            this.target = target;
        }
    }
}
