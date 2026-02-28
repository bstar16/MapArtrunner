package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;

public interface BaritoneFacade {
    CommandResult goTo(BlockPos target);

    CommandResult goNear(BlockPos target, int range);

    CommandResult pause();

    CommandResult resume();

    CommandResult cancel();

    boolean isBusy();

    record CommandResult(boolean success, String message) {
        public static CommandResult success(String message) {
            return new CommandResult(true, message);
        }

        public static CommandResult failure(String message) {
            return new CommandResult(false, message);
        }
    }
}
