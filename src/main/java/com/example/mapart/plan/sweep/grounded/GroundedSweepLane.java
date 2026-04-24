package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;

public record GroundedSweepLane(
        int laneIndex,
        int centerlineCoordinate,
        GroundedLaneDirection direction,
        BlockPos startPoint,
        BlockPos endPoint,
        GroundedLaneCorridorBounds corridorBounds,
        double endpointTolerance
) {
    public GroundedSweepLane {
        if (laneIndex < 0) {
            throw new IllegalArgumentException("laneIndex must be >= 0");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(startPoint, "startPoint");
        Objects.requireNonNull(endPoint, "endPoint");
        Objects.requireNonNull(corridorBounds, "corridorBounds");
        if (endpointTolerance < 0.0) {
            throw new IllegalArgumentException("endpointTolerance must be >= 0");
        }
    }
}
