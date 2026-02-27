package com.example.mapart.plan.compare;

import com.example.mapart.plan.Placement;
import net.minecraft.util.math.BlockPos;

public record PlacementStatusSnapshot(
        int index,
        Placement placement,
        BlockPos absolutePos,
        PlacementStatus status,
        boolean nextTarget
) {
}
