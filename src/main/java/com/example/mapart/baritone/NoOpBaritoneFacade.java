package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;

public class NoOpBaritoneFacade implements BaritoneFacade {
    @Override
    public CommandResult goTo(BlockPos target) {
        return CommandResult.failure("Baritone integration is unavailable.");
    }

    @Override
    public CommandResult goNear(BlockPos target, int range) {
        return CommandResult.failure("Baritone integration is unavailable.");
    }

    @Override
    public CommandResult pause() {
        return CommandResult.success("No active Baritone movement to pause.");
    }

    @Override
    public CommandResult resume() {
        return CommandResult.failure("No paused Baritone movement to resume.");
    }

    @Override
    public CommandResult cancel() {
        return CommandResult.success("No active movement to cancel.");
    }

    @Override
    public boolean isBusy() {
        return false;
    }
}
