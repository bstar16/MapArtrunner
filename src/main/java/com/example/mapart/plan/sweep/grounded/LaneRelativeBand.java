package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;

/**
 * Position of a block relative to the player's facing direction within a lane corridor.
 * RIGHT is always player-right (e.g., for WEST lanes heading -X, right is -Z).
 */
public enum LaneRelativeBand {
    LEFT_TWO, LEFT_ONE, CENTER, RIGHT_ONE, RIGHT_TWO;

    /**
     * Classifies worldPos relative to lane centerline.
     * Uses lateralStrafeSign() so RIGHT is always player-right regardless of direction.
     * Example: WEST lane (centerline Z=80), position Z=78 → rightwardOffset = -2 * -1 = 2 → RIGHT_TWO.
     */
    public static LaneRelativeBand classify(GroundedSweepLane lane, BlockPos worldPos) {
        int lateralCoord = lane.direction().alongX() ? worldPos.getZ() : worldPos.getX();
        int lateralOffset = lateralCoord - lane.centerlineCoordinate();
        int rightwardOffset = lateralOffset * lane.direction().lateralStrafeSign();
        if (rightwardOffset >= 2) return RIGHT_TWO;
        if (rightwardOffset == 1) return RIGHT_ONE;
        if (rightwardOffset == -1) return LEFT_ONE;
        if (rightwardOffset <= -2) return LEFT_TWO;
        return CENTER;
    }
}
