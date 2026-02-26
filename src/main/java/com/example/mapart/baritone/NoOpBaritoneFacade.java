package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;

public class NoOpBaritoneFacade implements BaritoneFacade {
    @Override
    public void goTo(BlockPos target) {
        // Milestone B stub: movement is not integrated yet.
    }

    @Override
    public void cancel() {
        // Milestone B stub: movement is not integrated yet.
    }

    @Override
    public boolean isBusy() {
        return false;
    }
}
