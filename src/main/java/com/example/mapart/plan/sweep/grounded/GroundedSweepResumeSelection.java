package com.example.mapart.plan.sweep.grounded;

import java.util.Objects;
import java.util.Optional;

public record GroundedSweepResumeSelection(
        Optional<GroundedSweepResumePoint> resumePoint,
        boolean buildComplete
) {
    public GroundedSweepResumeSelection {
        resumePoint = resumePoint == null ? Optional.empty() : resumePoint;
    }

    public static GroundedSweepResumeSelection complete() {
        return new GroundedSweepResumeSelection(Optional.empty(), true);
    }

    public static GroundedSweepResumeSelection resumeAt(GroundedSweepResumePoint point) {
        return new GroundedSweepResumeSelection(Optional.of(Objects.requireNonNull(point, "point")), false);
    }
}
