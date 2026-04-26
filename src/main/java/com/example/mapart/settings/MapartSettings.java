package com.example.mapart.settings;

public record MapartSettings(
        boolean showHud,
        boolean showSchematicOverlay,
        boolean overlayCurrentRegionOnly,
        boolean overlayShowOnlyIncorrect,
        boolean hudCompact,
        int clientTimerSpeed,
        int hudX,
        int hudY,
        boolean preferLongerAxis,
        int sweepHalfWidth,
        int sweepTotalWidth,
        int laneStride,
        int forwardLookaheadSteps,
        int trivialBehindCleanupSteps,
        boolean groundedSweepConstantSprint,
        boolean groundedDebugTrace
) {
    public static MapartSettings defaults() {
        return new MapartSettings(
                true,
                true,
                true,
                false,
                false,
                1,
                8,
                8,
                false,
                2,
                5,
                5,
                1,
                1,
                true,
                false
        );
    }
}
