package com.example.mapart.plan.sweep.grounded;

public enum GroundedLaneDirection {
    EAST(-90.0f, true, 1),
    WEST(90.0f, true, -1),
    SOUTH(0.0f, false, 1),
    NORTH(180.0f, false, -1);

    private final float yawDegrees;
    private final boolean alongX;
    private final int forwardSign;

    GroundedLaneDirection(float yawDegrees, boolean alongX, int forwardSign) {
        this.yawDegrees = yawDegrees;
        this.alongX = alongX;
        this.forwardSign = forwardSign;
    }

    public float yawDegrees() {
        return yawDegrees;
    }

    public boolean alongX() {
        return alongX;
    }

    public double progressCoordinate(double x, double z) {
        return alongX ? x : z;
    }

    public double lateralCoordinate(double x, double z) {
        return alongX ? z : x;
    }

    public int forwardSign() {
        return forwardSign;
    }

    public GroundedLaneDirection opposite() {
        return switch (this) {
            case EAST -> WEST;
            case WEST -> EAST;
            case SOUTH -> NORTH;
            case NORTH -> SOUTH;
        };
    }
}
