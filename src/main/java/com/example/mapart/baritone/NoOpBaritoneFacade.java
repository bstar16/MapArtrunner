package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;

public class NoOpBaritoneFacade implements BaritoneFacade {
    private static final String DEFAULT_REASON = "Baritone integration is unavailable. Install a compatible baritone-api-fabric mod for this Minecraft version.";

    private final String reason;

    public NoOpBaritoneFacade() {
        this(DEFAULT_REASON);
    }

    public NoOpBaritoneFacade(String reason) {
        this.reason = reason == null || reason.isBlank() ? DEFAULT_REASON : reason;
    }

    @Override
    public CommandResult goTo(BlockPos target) {
        return CommandResult.failure(reason);
    }

    @Override
    public CommandResult goNear(BlockPos target, int range) {
        return CommandResult.failure(reason);
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
