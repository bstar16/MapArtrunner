package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

public record GroundedLanePlan(
        int laneIndex,
        GroundedLaneDirection direction,
        int centerlineCoordinate,
        BlockPos startPoint,
        BlockPos endPoint,
        GroundedCorridorBounds corridorBounds,
        double endpointTolerance
) {
    public GroundedLanePlan {
        if (laneIndex < 0) {
            throw new IllegalArgumentException("laneIndex must be >= 0");
        }
        if (endpointTolerance < 0.0) {
            throw new IllegalArgumentException("endpointTolerance must be >= 0");
        }
    }
}
