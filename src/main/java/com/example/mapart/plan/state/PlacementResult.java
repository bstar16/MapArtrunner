package com.example.mapart.plan.state;

public record PlacementResult(Status status, String message) {
    public enum Status {
        PLACED,
        ALREADY_CORRECT,
        MISSING_ITEM,
        MOVE_REQUIRED,
        RETRY,
        ERROR
    }

    static PlacementResult placed(String message) {
        return new PlacementResult(Status.PLACED, message);
    }

    static PlacementResult alreadyCorrect(String message) {
        return new PlacementResult(Status.ALREADY_CORRECT, message);
    }

    static PlacementResult missingItem(String message) {
        return new PlacementResult(Status.MISSING_ITEM, message);
    }

    static PlacementResult moveRequired(String message) {
        return new PlacementResult(Status.MOVE_REQUIRED, message);
    }

    static PlacementResult retry(String message) {
        return new PlacementResult(Status.RETRY, message);
    }

    static PlacementResult error(String message) {
        return new PlacementResult(Status.ERROR, message);
    }
}
