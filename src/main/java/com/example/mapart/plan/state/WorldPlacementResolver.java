package com.example.mapart.plan.state;

import com.example.mapart.plan.Placement;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public class WorldPlacementResolver {
    public Optional<BlockPos> resolveAbsolute(BlockPos origin, Placement placement) {
        if (origin == null || placement == null || placement.relativePos() == null) {
            return Optional.empty();
        }

        return Optional.of(origin.add(placement.relativePos()));
    }
}
