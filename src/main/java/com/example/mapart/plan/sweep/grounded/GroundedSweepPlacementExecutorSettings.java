package com.example.mapart.plan.sweep.grounded;

public record GroundedSweepPlacementExecutorSettings(
        int corridorHalfWidth,
        int forwardLookaheadSteps,
        int trivialBehindCleanupSteps,
        long placementFailureGraceTicks
) {
    public GroundedSweepPlacementExecutorSettings {
        if (corridorHalfWidth < 0) {
            throw new IllegalArgumentException("corridorHalfWidth must be >= 0");
        }
        if (forwardLookaheadSteps < 0) {
            throw new IllegalArgumentException("forwardLookaheadSteps must be >= 0");
        }
        if (trivialBehindCleanupSteps < 0) {
            throw new IllegalArgumentException("trivialBehindCleanupSteps must be >= 0");
        }
        if (placementFailureGraceTicks < 0) {
            throw new IllegalArgumentException("placementFailureGraceTicks must be >= 0");
        }
    }

    public static GroundedSweepPlacementExecutorSettings defaults() {
        return new GroundedSweepPlacementExecutorSettings(2, 1, 1, 4);
    }
}
