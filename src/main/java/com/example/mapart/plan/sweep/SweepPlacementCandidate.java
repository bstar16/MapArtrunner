package com.example.mapart.plan.sweep;

import net.minecraft.util.math.BlockPos;

public record SweepPlacementCandidate(
        int placementIndex,
        BlockPos relativePos,
        int laneIndex,
        int placementProgress,
        int signedProgressDelta,
        LaneBandClassification laneBand,
        ProgressRelation progressRelation,
        double distanceToPlayer
) {
}
