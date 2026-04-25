package com.example.mapart.plan.sweep.grounded;

import java.util.Optional;

public record GroundedLaneWalkResult(
        GroundedLaneWalkerState finalState,
        int ticksElapsed,
        Optional<String> failureReason
) {
    public GroundedLaneWalkResult {
        if (ticksElapsed < 0) {
            throw new IllegalArgumentException("ticksElapsed must be >= 0");
        }
        failureReason = failureReason == null ? Optional.empty() : failureReason;
    }
}
