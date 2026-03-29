package com.example.mapart.plan.sweep;

public enum LaneAxis {
    X,
    Z;

    public LaneAxis perpendicular() {
        return this == X ? Z : X;
    }
}
