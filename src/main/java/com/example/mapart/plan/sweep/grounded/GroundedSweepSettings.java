package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.inventory.HotbarSlotReservations;

public record GroundedSweepSettings(
        int sweepHalfWidth,
        int sweepTotalWidth,
        int laneStride,
        int forwardLookaheadSteps,
        int trivialBehindCleanupSteps,
        boolean groundedSweepConstantSprint,
        int reservedHotbarSlots,
        double endpointTolerance
) {
    public GroundedSweepSettings(
            int sweepHalfWidth,
            int sweepTotalWidth,
            int laneStride,
            int forwardLookaheadSteps,
            int trivialBehindCleanupSteps,
            boolean groundedSweepConstantSprint,
            double endpointTolerance
    ) {
        this(
                sweepHalfWidth,
                sweepTotalWidth,
                laneStride,
                forwardLookaheadSteps,
                trivialBehindCleanupSteps,
                groundedSweepConstantSprint,
                0,
                endpointTolerance
        );
    }

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
        HotbarSlotReservations.validateReservedHotbarSlots(reservedHotbarSlots);
    }

    public static GroundedSweepSettings defaults() {
        return new GroundedSweepSettings(
                2,
                5,
                5,
                1,
                1,
                true,
                0,
                1.0
        );
    }
}
