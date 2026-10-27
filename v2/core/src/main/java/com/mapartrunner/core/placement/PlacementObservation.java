package com.mapartrunner.core.placement;

import com.mapartrunner.core.model.BuildPlan.BlockSpec;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Immutable target evidence sampled at a nonnegative run-local simulation tick, not arrival time. */
public record PlacementObservation(UUID runId, int targetId, OptionalLong actionId, long tick,
                                   Evidence evidence, Optional<BlockSpec> observedState) {
    public PlacementObservation {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(observedState, "observedState");
        if (targetId < 0 || tick < 0 || (actionId.isPresent() && actionId.getAsLong() < 0)) {
            throw new IllegalArgumentException("Target ID, tick and action ID must be nonnegative");
        }
        if ((evidence == Evidence.UNKNOWN) != observedState.isEmpty()) {
            throw new IllegalArgumentException("UNKNOWN requires no state; PREDICTED and OBSERVED require state");
        }
    }

    /**
     * OBSERVED requires producer-established client world evidence that is not speculative local
     * placement prediction. This value alone proves neither server truth nor final run success.
     * UNKNOWN includes unloaded/unavailable state; it must not be synthesized for unvisited targets.
     */
    public enum Evidence { UNKNOWN, PREDICTED, OBSERVED }
}
