package com.example.mapart.plan.sweep;

public record SweepPassControllerSettings(
        int maxAttemptsPerTarget,
        int scarcityProgressStep,
        int maxTicksWithoutCompletion
) {
    public SweepPassControllerSettings {
        if (maxAttemptsPerTarget <= 0) {
            throw new IllegalArgumentException("maxAttemptsPerTarget must be > 0");
        }
        if (scarcityProgressStep <= 0) {
            throw new IllegalArgumentException("scarcityProgressStep must be > 0");
        }
        if (maxTicksWithoutCompletion <= 0) {
            throw new IllegalArgumentException("maxTicksWithoutCompletion must be > 0");
        }
    }

    public static SweepPassControllerSettings defaults() {
        return new SweepPassControllerSettings(2, 1, 256);
    }
}
