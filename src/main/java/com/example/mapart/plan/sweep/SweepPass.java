package com.example.mapart.plan.sweep;

public record SweepPass(
        int passIndex,
        BuildLane lane,
        SweepPassState state,
        PassProgressSnapshot progress
) {
    public SweepPass {
        if (passIndex < 0) {
            throw new IllegalArgumentException("passIndex must be >= 0");
        }
    }
}
