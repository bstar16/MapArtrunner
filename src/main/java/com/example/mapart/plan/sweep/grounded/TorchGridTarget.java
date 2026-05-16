package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

public record TorchGridTarget(BlockPos torchPos, BlockPos supportPos) {
    public TorchGridTarget {
        if (torchPos == null) {
            throw new IllegalArgumentException("torchPos must not be null");
        }
        if (supportPos == null) {
            throw new IllegalArgumentException("supportPos must not be null");
        }
    }
}
