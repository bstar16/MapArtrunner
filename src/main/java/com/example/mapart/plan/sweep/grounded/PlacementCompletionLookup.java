package com.example.mapart.plan.sweep.grounded;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

@FunctionalInterface
public interface PlacementCompletionLookup {
    boolean isExpectedBlockPlaced(BlockPos worldPosition, Block expectedBlock);
}
