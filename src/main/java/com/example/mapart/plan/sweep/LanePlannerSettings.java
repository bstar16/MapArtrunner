package com.example.mapart.plan.sweep;

public record LanePlannerSettings(
        int primaryHalfWidth,
        int edgeHalfWidth,
        double endpointTolerance
) {
    public LanePlannerSettings {
        if (primaryHalfWidth < 0) {
            throw new IllegalArgumentException("primaryHalfWidth must be >= 0");
        }
        if (edgeHalfWidth < 0) {
            throw new IllegalArgumentException("edgeHalfWidth must be >= 0");
        }
        if (endpointTolerance < 0.0) {
            throw new IllegalArgumentException("endpointTolerance must be >= 0");
        }
    }
}
