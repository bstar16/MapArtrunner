package com.example.mapart.settings;

import com.example.mapart.inventory.HotbarSlotReservations;
import com.example.mapart.plan.sweep.grounded.TorchGridSettings;

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
        boolean torchGridEnabled,
        int torchGridSpacing,
        boolean torchGridWarnMissingTorches,
        int torchGridMaxPlacementsPerTick,
        boolean manualAirPlaceEnabled,
        boolean manualAirPlaceRender,
        boolean manualAirPlaceUseCustomRange,
        double manualAirPlaceCustomRange,
        boolean manualAirPlaceRequireSneak,
        boolean manualAirPlaceDisableWhileRunnerActive
) {
    public MapartSettings {
        torchGridSpacing = TorchGridSettings.FIXED_SPACING;
        HotbarSlotReservations.validateReservedHotbarSlots(reservedHotbarSlots);
        new TorchGridSettings(torchGridEnabled, torchGridSpacing, torchGridWarnMissingTorches, torchGridMaxPlacementsPerTick);
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
                TorchGridSettings.FIXED_SPACING,
                true,
                1,
                false,
                true,
                false,
                5.0,
                false,
                true
        );
    }
}
