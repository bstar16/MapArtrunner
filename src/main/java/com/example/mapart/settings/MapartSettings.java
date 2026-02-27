package com.example.mapart.settings;

public record MapartSettings(
        boolean showHud,
        boolean showSchematicOverlay,
        boolean overlayCurrentRegionOnly,
        int overlayMaxRenderDistance,
        boolean overlayShowOnlyIncorrect,
        boolean hudCompact,
        int hudX,
        int hudY
) {
    public static MapartSettings defaults() {
        return new MapartSettings(true, true, true, 64, false, false, 8, 8);
    }
}
