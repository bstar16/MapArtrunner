package com.example.mapart.plan.sweep.grounded;

import com.example.mapart.plan.sweep.LaneAxis;

import java.util.List;
import java.util.Objects;

public record GroundedLanePlan(
        GroundedSchematicBounds bounds,
        LaneAxis primaryAxis,
        List<GroundedSweepLane> lanes
) {
    public GroundedLanePlan {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(primaryAxis, "primaryAxis");
        lanes = List.copyOf(lanes);
        if (lanes.isEmpty()) {
            throw new IllegalArgumentException("lanes must not be empty");
        }
    }
}
