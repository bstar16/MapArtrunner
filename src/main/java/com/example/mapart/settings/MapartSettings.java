package com.example.mapart.settings;

public record MapartSettings(
        boolean showHud,
        boolean showSchematicOverlay,
        boolean overlayCurrentRegionOnly,
        boolean overlayShowOnlyIncorrect,
        boolean hudCompact,
        int clientTimerSpeed,
        int hudX,
        int hudY
) {
    public static MapartSettings defaults() {
        return new MapartSettings(true, true, true, false, false, 1, 8, 8);
    }
}
