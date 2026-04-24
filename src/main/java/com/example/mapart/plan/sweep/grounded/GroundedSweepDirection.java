package com.example.mapart.plan.sweep.grounded;

public enum GroundedSweepDirection {
    EAST,
    WEST,
    SOUTH,
    NORTH;

    public boolean isPositive() {
        return this == EAST || this == SOUTH;
    }
}
