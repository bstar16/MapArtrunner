package com.example.mapart.plan.sweep.grounded;

public record GroundedCorridorBounds(int minX, int maxX, int minZ, int maxZ) {
    public GroundedCorridorBounds {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("corridor bounds min must be <= max");
        }
    }
}
