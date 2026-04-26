package com.example.mapart.plan.sweep.grounded;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

@FunctionalInterface
public interface WorldBlockLookup {
    boolean blockMatches(BlockPos worldPos, Block expectedBlock);
}
