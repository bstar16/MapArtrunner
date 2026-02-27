package com.example.mapart.supply;

import net.minecraft.util.math.BlockPos;

public record SupplyPoint(int id, BlockPos pos, String dimensionKey, String name) {
}
