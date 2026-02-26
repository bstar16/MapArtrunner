package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;

public interface BaritoneFacade {
    void goTo(BlockPos target);

    void cancel();

    boolean isBusy();
}
