package com.example.mapart.plan.sweep.grounded;

public record TorchGridSettings(
        boolean enabled,
        int spacing,
        boolean warnMissingTorches,
        int maxPlacementsPerTick
) {
    public static final int FIXED_SPACING = 12;
    public static final int MIN_MAX_PLACEMENTS_PER_TICK = 1;
    public static final int MAX_MAX_PLACEMENTS_PER_TICK = 4;

    public TorchGridSettings {
        spacing = FIXED_SPACING;
        if (maxPlacementsPerTick < MIN_MAX_PLACEMENTS_PER_TICK || maxPlacementsPerTick > MAX_MAX_PLACEMENTS_PER_TICK) {
            throw new IllegalArgumentException("torchGridMaxPlacementsPerTick must be between 1 and 4.");
        }
    }

    public static TorchGridSettings disabled() {
        return new TorchGridSettings(false, FIXED_SPACING, true, 1);
    }
}
