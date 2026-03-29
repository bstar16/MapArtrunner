package com.example.mapart.plan.sweep;

public enum LaneDirection {
    FORWARD,
    REVERSE;

    public LaneDirection opposite() {
        return this == FORWARD ? REVERSE : FORWARD;
    }
}
