package com.example.mapart.plan.sweep.grounded;

public record GroundedSweepSettings(
        boolean preferLongerAxis,
        int sweepHalfWidth,
        int sweepTotalWidth,
        int laneStride,
        int forwardLookaheadSteps,
        int trivialBehindCleanupSteps,
        boolean groundedSweepConstantSprint,
        double endpointTolerance
) {
    public GroundedSweepSettings {
        if (sweepHalfWidth < 0) {
            throw new IllegalArgumentException("sweepHalfWidth must be >= 0");
        }
        if (sweepTotalWidth <= 0) {
            throw new IllegalArgumentException("sweepTotalWidth must be > 0");
        }
        if (sweepTotalWidth != (sweepHalfWidth * 2) + 1) {
            throw new IllegalArgumentException("sweepTotalWidth must equal (sweepHalfWidth * 2) + 1");
        }
        if (laneStride <= 0) {
            throw new IllegalArgumentException("laneStride must be > 0");
        }
        if (forwardLookaheadSteps < 0) {
            throw new IllegalArgumentException("forwardLookaheadSteps must be >= 0");
        }
        if (trivialBehindCleanupSteps < 0) {
            throw new IllegalArgumentException("trivialBehindCleanupSteps must be >= 0");
        }
        if (endpointTolerance < 0.0) {
            throw new IllegalArgumentException("endpointTolerance must be >= 0");
        }
    }

    public static GroundedSweepSettings defaults() {
        return new GroundedSweepSettings(
                false,
                2,
                5,
                5,
                1,
                1,
                true,
                1.0
        );
    }
}
