package com.example.mapart.plan.sweep.grounded;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.util.math.BlockPos;

public final class TorchGridPlanner {
    public List<TorchGridTarget> plan(GroundedSchematicBounds bounds, TorchGridSettings settings) {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            return List.of();
        }

        List<TorchGridTarget> targets = new ArrayList<>();
        int spacing = TorchGridSettings.FIXED_SPACING;
        int supportY = bounds.minY();
        int torchY = supportY + 1;
        for (int z = bounds.minZ(); z <= bounds.maxZ(); z += spacing) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x += spacing) {
                BlockPos torchPos = new BlockPos(x, torchY, z);
                targets.add(new TorchGridTarget(torchPos, new BlockPos(x, supportY, z)));
            }
        }
        return List.copyOf(targets);
    }
}
