package com.example.mapart.plan.sweep;

public record PassProgressSnapshot(
        int laneIndex,
        SweepPassState state,
        int targetProgress,
        int currentProgress,
        int remainingPlacements
) {
    public PassProgressSnapshot {
        if (laneIndex < 0) {
            throw new IllegalArgumentException("laneIndex must be >= 0");
        }
        if (remainingPlacements < 0) {
            throw new IllegalArgumentException("remainingPlacements must be >= 0");
        }
    }
}
