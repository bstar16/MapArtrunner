package com.example.mapart.plan.sweep.grounded;

public record GroundedLaneCorridorBounds(
        int minX,
        int maxX,
        int minZ,
        int maxZ
) {
    public GroundedLaneCorridorBounds {
        if (minX > maxX) {
            throw new IllegalArgumentException("minX must be <= maxX");
        }
        if (minZ > maxZ) {
            throw new IllegalArgumentException("minZ must be <= maxZ");
        }
    }
}
