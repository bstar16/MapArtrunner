package com.example.mapart.plan.sweep.grounded;

public record GroundedLaneWalkResult(
        GroundedLaneWalkState state,
        int ticksElapsed,
        String failureReason
) {
}
