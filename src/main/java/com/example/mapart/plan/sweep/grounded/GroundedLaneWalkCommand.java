package com.example.mapart.plan.sweep.grounded;

public record GroundedLaneWalkCommand(
        float yaw,
        boolean forwardPressed,
        boolean backPressed,
        boolean leftPressed,
        boolean rightPressed,
        boolean sprinting
) {
    public static GroundedLaneWalkCommand idle(float yaw) {
        return new GroundedLaneWalkCommand(yaw, false, false, false, false, false);
    }
}
