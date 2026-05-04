package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;

public interface BaritoneFacade {
    CommandResult goTo(BlockPos target);

    CommandResult goNear(BlockPos target, int range);

    CommandResult pause();

    CommandResult resume();

    CommandResult cancel();

    boolean isBusy();

    default java.util.Optional<String> diagnosticsLastIssuedGoal() { return java.util.Optional.empty(); }

    default java.util.Optional<Integer> diagnosticsLastIssuedGoalRange() { return java.util.Optional.empty(); }

    default java.util.Optional<Boolean> diagnosticsConstraintsApplied() { return java.util.Optional.empty(); }

    record CommandResult(boolean success, String message) {
        public static CommandResult success(String message) {
            return new CommandResult(true, message);
        }

        public static CommandResult failure(String message) {
            return new CommandResult(false, message);
        }
    }
}
