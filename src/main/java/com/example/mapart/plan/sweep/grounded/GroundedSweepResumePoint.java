package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;

public record GroundedSweepResumePoint(
        int laneIndex,
        SweepPhase sweepPhase,
        GroundedLaneDirection laneDirection,
        int centerlineCoordinate,
        int progressCoordinate,
        BlockPos approachTarget,
        float yawDegrees,
        Reason reason,
        int usefulPlacementCount,
        int unfinishedPlacementCount
) {
    public GroundedSweepResumePoint {
        if (laneIndex < 0) {
            throw new IllegalArgumentException("laneIndex must be >= 0");
        }
        Objects.requireNonNull(sweepPhase, "sweepPhase");
        Objects.requireNonNull(laneDirection, "laneDirection");
        Objects.requireNonNull(approachTarget, "approachTarget");
        Objects.requireNonNull(reason, "reason");
    }

    public enum SweepPhase {
        FORWARD
    }

    public enum Reason {
        FRESH_START,
        FIRST_UNFINISHED,
        CLOSEST_USEFUL,
        PARTIAL_LANE,
        NO_UNFINISHED_PLACEMENTS
    }
}
