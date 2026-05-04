package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;

public interface BaritoneFacade {
    CommandResult goTo(BlockPos target);

    CommandResult goNear(BlockPos target, int range);

    CommandResult pause();

    CommandResult resume();

    CommandResult cancel();

    boolean isBusy();

    /**
     * Applies strict on-plane navigation constraints to prevent Baritone from leaving
     * the build surface during in-sweep navigation (refill returns, recovery, etc.).
     * Constraints prevent parkour, descending, and falling off edges.
     */
    void applyOnPlaneConstraints();

    /**
     * Clears on-plane navigation constraints, restoring default Baritone pathfinding behavior.
     */
    void clearOnPlaneConstraints();

    record CommandResult(boolean success, String message) {
        public static CommandResult success(String message) {
            return new CommandResult(true, message);
        }

        public static CommandResult failure(String message) {
            return new CommandResult(false, message);
        }
    }
}
