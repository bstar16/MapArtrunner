package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.Vec3d;

import java.util.Objects;

public record GroundedRecoverySnapshot(
        GroundedSweepLane activeLane,
        GroundedSingleLaneDebugRunner.SweepPassPhase passPhase,
        GroundedLaneDirection laneDirection,
        double lastKnownSafeProgressCoordinate,
        Vec3d playerPosition,
        GroundedRecoveryReason reason
) {
    public GroundedRecoverySnapshot {
        Objects.requireNonNull(activeLane, "activeLane");
        Objects.requireNonNull(passPhase, "passPhase");
        Objects.requireNonNull(laneDirection, "laneDirection");
        Objects.requireNonNull(playerPosition, "playerPosition");
        Objects.requireNonNull(reason, "reason");
    }
}
