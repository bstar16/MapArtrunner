package com.example.mapart.plan.sweep.grounded;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Objects;
import java.util.Set;

public record PausedGroundedRunSnapshot(
        GroundedSingleLaneDebugRunner.SweepRunMode runMode,
        GroundedSingleLaneDebugRunner.SweepPassPhase passPhase,
        int laneIndex,
        GroundedLaneDirection laneDirection,
        BlockPos laneStart,
        BlockPos laneEnd,
        int progressCoordinate,
        BlockPos standingPosition,
        Vec3d playerPosition,
        Integer lastPlacedProgressCoordinate,
        boolean awaitingStartApproach,
        boolean awaitingLaneShift,
        boolean awaitingTransitionSupport,
        Integer refillLaneProgressHint,
        Integer refillLaneCursorHint,
        Set<Integer> forwardLeftoverPlacements,
        Set<Integer> reversePlacementFilter
) {
    public PausedGroundedRunSnapshot {
        Objects.requireNonNull(runMode, "runMode");
        Objects.requireNonNull(passPhase, "passPhase");
        if (laneIndex < 0) {
            throw new IllegalArgumentException("laneIndex must be >= 0");
        }
        Objects.requireNonNull(laneDirection, "laneDirection");
        Objects.requireNonNull(laneStart, "laneStart");
        Objects.requireNonNull(laneEnd, "laneEnd");
        Objects.requireNonNull(standingPosition, "standingPosition");
        Objects.requireNonNull(playerPosition, "playerPosition");
        forwardLeftoverPlacements = forwardLeftoverPlacements == null ? Set.of() : Set.copyOf(forwardLeftoverPlacements);
        reversePlacementFilter = reversePlacementFilter == null ? Set.of() : Set.copyOf(reversePlacementFilter);
    }
}
