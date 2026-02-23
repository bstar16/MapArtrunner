package com.example.mapart.plan;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;

public record Placement(BlockPos relativePos, Block block) {
}
