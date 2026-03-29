package com.example.mapart.plan.sweep;

import java.util.Optional;

public record PlacementAttemptResult(
        boolean placedBlock,
        boolean skipped,
        String detail
) {
    public PlacementAttemptResult {
        if (placedBlock && skipped) {
            throw new IllegalArgumentException("result cannot be placed and skipped simultaneously");
        }
    }

    public static PlacementAttemptResult placed() {
        return new PlacementAttemptResult(true, false, null);
    }

    public static PlacementAttemptResult skipped(String message) {
        return new PlacementAttemptResult(false, true, message);
    }

    public static PlacementAttemptResult failed(String message) {
        return new PlacementAttemptResult(false, false, message);
    }

    public Optional<String> detailMessage() {
        return Optional.ofNullable(detail);
    }
}
