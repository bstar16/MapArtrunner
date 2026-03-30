package com.example.mapart.plan.sweep;

public record SweepPlacementControllerSettings(
        double maxCandidateDistance,
        int nearProgressWindow,
        int aheadProgressWindow,
        int trivialBehindWindow,
        int farBehindDeferralThreshold,
        int maxRankedCandidates
) {
    public SweepPlacementControllerSettings {
        if (maxCandidateDistance <= 0.0) {
            throw new IllegalArgumentException("maxCandidateDistance must be > 0");
        }
        if (nearProgressWindow < 0) {
            throw new IllegalArgumentException("nearProgressWindow must be >= 0");
        }
        if (aheadProgressWindow < nearProgressWindow) {
            throw new IllegalArgumentException("aheadProgressWindow must be >= nearProgressWindow");
        }
        if (trivialBehindWindow < 0) {
            throw new IllegalArgumentException("trivialBehindWindow must be >= 0");
        }
        if (farBehindDeferralThreshold <= trivialBehindWindow) {
            throw new IllegalArgumentException("farBehindDeferralThreshold must be > trivialBehindWindow");
        }
        if (maxRankedCandidates <= 0) {
            throw new IllegalArgumentException("maxRankedCandidates must be > 0");
        }
    }

    public static SweepPlacementControllerSettings defaults() {
        return new SweepPlacementControllerSettings(
                4.5,
                0,
                3,
                1,
                3,
                8
        );
    }
}
