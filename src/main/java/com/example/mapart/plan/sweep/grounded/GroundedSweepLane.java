package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.sweep.LaneAxis;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;

public record GroundedSweepLane(
        int laneIndex,
        LaneAxis axis,
        GroundedSweepDirection direction,
        int centerlineCoordinate,
        BlockPos startPoint,
        BlockPos endPoint,
        int corridorMinSweepCoordinate,
        int corridorMaxSweepCoordinate,
        double endpointTolerance
) {
    public GroundedSweepLane {
        if (laneIndex < 0) {
            throw new IllegalArgumentException("laneIndex must be >= 0");
        }
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(startPoint, "startPoint");
        Objects.requireNonNull(endPoint, "endPoint");
        if (corridorMinSweepCoordinate > corridorMaxSweepCoordinate) {
            throw new IllegalArgumentException("corridorMinSweepCoordinate must be <= corridorMaxSweepCoordinate");
        }
        if (endpointTolerance < 0.0) {
            throw new IllegalArgumentException("endpointTolerance must be >= 0");
        }
    }
}
