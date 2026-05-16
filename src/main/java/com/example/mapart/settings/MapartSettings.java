package com.example.mapart.settings;

import com.example.mapart.inventory.HotbarSlotReservations;

public record MapartSettings(
        boolean showHud,
        boolean showSchematicOverlay,
        boolean overlayCurrentRegionOnly,
        boolean overlayShowOnlyIncorrect,
        boolean hudCompact,
        int clientTimerSpeed,
        boolean clientTimerEnabled,
        int hudX,
        int hudY,
        int sweepHalfWidth,
        int sweepTotalWidth,
        int laneStride,
        int forwardLookaheadSteps,
        int trivialBehindCleanupSteps,
        boolean groundedSweepConstantSprint,
        int placementDelayTicks,
        int inventoryClickDelayTicks,
        int reservedHotbarSlots,
        boolean manualAirPlaceEnabled,
        boolean manualAirPlaceRender,
        boolean manualAirPlaceUseCustomRange,
        double manualAirPlaceCustomRange,
        boolean manualAirPlaceRequireSneak,
        boolean manualAirPlaceDisableWhileRunnerActive
) {
    public MapartSettings {
        HotbarSlotReservations.validateReservedHotbarSlots(reservedHotbarSlots);
    }

    public static MapartSettings defaults() {
        return new MapartSettings(
                true,
                true,
                true,
                false,
                false,
                1,
                true,
                8,
                8,
                2,
                5,
                5,
                1,
                1,
                true,
                0,
                0,
                0,
                false,
                true,
                false,
                5.0,
                false,
                true
        );
    }
}
