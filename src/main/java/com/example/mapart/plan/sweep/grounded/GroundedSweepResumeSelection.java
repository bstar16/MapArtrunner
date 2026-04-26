package com.example.mapart.plan.sweep.grounded;

import java.util.Objects;
import java.util.Optional;

public record GroundedSweepResumeSelection(
        Optional<GroundedSweepResumePoint> resumePoint,
        boolean buildComplete,
        int completePlacementCount,
        int unfinishedPlacementCount
) {
    public GroundedSweepResumeSelection {
        resumePoint = resumePoint == null ? Optional.empty() : resumePoint;
        if (completePlacementCount < 0) {
            throw new IllegalArgumentException("completePlacementCount must be >= 0");
        }
        if (unfinishedPlacementCount < 0) {
            throw new IllegalArgumentException("unfinishedPlacementCount must be >= 0");
        }
        Objects.requireNonNull(resumePoint, "resumePoint");
    }

    public static GroundedSweepResumeSelection complete(int completePlacements) {
        return new GroundedSweepResumeSelection(Optional.empty(), true, completePlacements, 0);
    }

    public static GroundedSweepResumeSelection fromPoint(GroundedSweepResumePoint point, int completeCount, int unfinishedCount) {
        return new GroundedSweepResumeSelection(Optional.of(point), false, completeCount, unfinishedCount);
    }
}
