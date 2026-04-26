package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;

public record GroundedSweepResumePoint(
        int laneIndex,
        SweepPhase phase,
        GroundedLaneDirection laneDirection,
        int centerlineCoordinate,
        int progressCoordinate,
        BlockPos standingPosition,
        float yaw,
        ResumeReason reason,
        int usefulPlacementCount,
        int unfinishedPlacementCount
) {
    public GroundedSweepResumePoint {
        if (laneIndex < 0) {
            throw new IllegalArgumentException("laneIndex must be >= 0");
        }
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(laneDirection, "laneDirection");
        Objects.requireNonNull(standingPosition, "standingPosition");
        Objects.requireNonNull(reason, "reason");
        if (usefulPlacementCount < 0) {
            throw new IllegalArgumentException("usefulPlacementCount must be >= 0");
        }
        if (unfinishedPlacementCount < 0) {
            throw new IllegalArgumentException("unfinishedPlacementCount must be >= 0");
        }
    }

    public enum SweepPhase {
        FORWARD
    }

    public enum ResumeReason {
        FRESH_START,
        FIRST_UNFINISHED,
        CLOSEST_USEFUL,
        PARTIAL_LANE,
        NO_UNFINISHED_PLACEMENTS
    }
}
