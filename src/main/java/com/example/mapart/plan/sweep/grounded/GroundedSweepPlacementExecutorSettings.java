package com.example.mapart.plan.sweep.grounded;

public record GroundedSweepPlacementExecutorSettings(
        int corridorHalfWidth,
        int forwardLookaheadSteps,
        int trivialBehindCleanupSteps,
        long placementFailureGraceTicks
) {
    private static final long DEFAULT_PLACEMENT_FAILURE_GRACE_TICKS = 4;

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
        return new GroundedSweepPlacementExecutorSettings(2, 1, 1, DEFAULT_PLACEMENT_FAILURE_GRACE_TICKS);
    }

    public static GroundedSweepPlacementExecutorSettings fromGroundedSweepSettings(GroundedSweepSettings settings) {
        return new GroundedSweepPlacementExecutorSettings(
                settings.sweepHalfWidth(),
                settings.forwardLookaheadSteps(),
                settings.trivialBehindCleanupSteps(),
                DEFAULT_PLACEMENT_FAILURE_GRACE_TICKS
        );
    }
}
